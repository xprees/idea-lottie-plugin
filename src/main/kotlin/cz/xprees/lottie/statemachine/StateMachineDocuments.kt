package cz.xprees.lottie.statemachine

import com.intellij.json.JsonFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Alarm
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieFiles
import cz.xprees.lottie.json.MiniJson

/**
 * A state machine file (`s/<id>.json`) of a `.lottie` archive, opened as a JSON document. It has the archive's path plus the entry
 * as its path, so the tab tooltip and Copy Path name both.
 */
class StateMachineVirtualFile(val archive: VirtualFile, val stateMachineId: String, text: String) :
    LightVirtualFile("$stateMachineId.json", JsonFileType.INSTANCE, text) {

    val entryPath get() = entryPath(stateMachineId)

    override fun getPath(): String = "${archive.path}!/$entryPath"

    override fun isWritable() = archive.isValid && archive.isWritable

    companion object {
        fun entryPath(id: String) = "s/$id.json"
    }
}

/**
 * Keeps state machine documents and their `.lottie` archives in step: an edit is written into the archive about a second after
 * typing stops (when the JSON is valid), and a change of the archive from elsewhere (the inputs editor, another tool) is loaded into
 * an open document that has no edits waiting to be written.
 */
@Service(Service.Level.PROJECT)
class StateMachineDocuments(private val project: Project) : Disposable {

    private class Open(val file: StateMachineVirtualFile, val document: Document, val disposable: Disposable) {
        /** The text last written to or read from the archive. */
        var synced: String = document.text
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    }

    private val open = LinkedHashMap<StateMachineVirtualFile, Open>()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file !is StateMachineVirtualFile || source.isFileOpen(file)) return
                open.remove(file)?.let {
                    write(it)
                    Disposer.dispose(it.disposable)
                }
            }
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val changed = events.filterIsInstance<VFileContentChangeEvent>().map { it.file }.toSet()
                if (changed.isEmpty()) return
                ApplicationManager.getApplication().invokeLater({ for (o in open.values.filter { it.file.archive in changed }) reload(o) }, project.disposed)
            }
        })
    }

    /** Opens state machine [id] of [archive] in an editor (the existing tab when it is already open). */
    fun open(archive: VirtualFile, id: String): StateMachineVirtualFile? {
        val file = open.keys.firstOrNull { it.archive == archive && it.stateMachineId == id } ?: create(archive, id) ?: return null
        OpenFileDescriptor(project, file).navigate(true)
        return file
    }

    /** Writes edits of [archive]'s open state machine documents that are still waiting (before another change of the archive). */
    fun flush(archive: VirtualFile) {
        for (o in open.values.filter { it.file.archive == archive }) {
            o.alarm.cancelAllRequests()
            write(o)
        }
    }

    /** The open document of state machine [id] of [archive], if any (for tests). */
    fun document(archive: VirtualFile, id: String): Document? =
        open.entries.firstOrNull { it.key.archive == archive && it.key.stateMachineId == id }?.value?.document

    private fun create(archive: VirtualFile, id: String): StateMachineVirtualFile? {
        val text = runCatching { DotLottieArchive.read(archive.contentsToByteArray()).stateMachineText(id) }.getOrNull() ?: return null
        val file = StateMachineVirtualFile(archive, id, text)
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val disposable = Disposer.newDisposable(this, "Lottie state machine ${file.path}")
        val entry = Open(file, document, disposable)
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (entry.document.text == entry.synced) return
                entry.alarm.cancelAllRequests()
                entry.alarm.addRequest({ write(entry) }, WRITE_DELAY_MS)
            }
        }, disposable)
        open[file] = entry
        return file
    }

    private fun write(o: Open) {
        val text = o.document.text
        if (text == o.synced || !o.file.archive.isValid || MiniJson.errorOf(text) != null) return
        o.synced = text
        DotLottieFiles.replaceEntries(
            project, o.file.archive, mapOf(o.file.entryPath to text.toByteArray(Charsets.UTF_8)),
            LottieBundle.message("sm.command.edit.json"), undoable = false,
        )
    }

    private fun reload(o: Open) {
        if (!o.file.archive.isValid) return
        val text = runCatching { DotLottieArchive.read(o.file.archive.contentsToByteArray()).stateMachineText(o.file.stateMachineId) }.getOrNull() ?: return
        if (text == o.synced) return
        // edits waiting to be written win; they are written next and replace the archive's version
        if (o.document.text != o.synced) return
        o.synced = text
        WriteCommandAction.writeCommandAction(project).withName(LottieBundle.message("sm.command.reload")).run<RuntimeException> {
            o.document.setText(text)
        }
    }

    override fun dispose() {
        open.clear()
    }

    companion object {
        const val WRITE_DELAY_MS = 800

        fun getInstance(project: Project): StateMachineDocuments = project.getService(StateMachineDocuments::class.java)
    }
}
