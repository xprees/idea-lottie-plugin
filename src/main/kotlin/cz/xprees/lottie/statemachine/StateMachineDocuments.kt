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
import com.intellij.openapi.util.Key
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
 * State machine files (`s/<id>.json`) of `.lottie` archives, opened as JSON documents. They are plain [LightVirtualFile]s tagged
 * with their archive and id: a platform class, so that editors and undo history that outlive this plugin (when it is disabled or
 * updated without a restart) hold none of its classes.
 */
object StateMachineVirtualFile {

    private val ARCHIVE = Key.create<VirtualFile>("cz.xprees.lottie.stateMachine.archive")
    private val ID = Key.create<String>("cz.xprees.lottie.stateMachine.id")

    fun create(archive: VirtualFile, id: String, text: String): LightVirtualFile =
        LightVirtualFile("$id.json", JsonFileType.INSTANCE, text).apply {
            putUserData(ARCHIVE, archive)
            putUserData(ID, id)
            isWritable = archive.isWritable
            // the default creation trace is a stack trace through this plugin's code, which would keep it from unloading
            com.intellij.psi.PsiInvalidElementAccessException.setCreationTrace(this, "state machine $id of ${archive.path}")
        }

    /** The archive [file] was opened from, if it is a state machine file. */
    fun archive(file: VirtualFile?): VirtualFile? = file?.getUserData(ARCHIVE)

    /** The state machine id of [file], if it is a state machine file. */
    fun id(file: VirtualFile?): String? = file?.getUserData(ID)

    fun isStateMachine(file: VirtualFile?) = archive(file) != null

    /** Where [file] lives: the archive's path plus the entry (for the tab tooltip). */
    fun path(file: VirtualFile): String? {
        val archive = archive(file) ?: return null
        return "${archive.path}!/${entryPath(id(file) ?: return null)}"
    }

    fun entryPath(id: String) = "s/$id.json"
}

/**
 * Keeps state machine documents and their `.lottie` archives in step: an edit is written into the archive about a second after
 * typing stops (when the JSON is valid), and a change of the archive from elsewhere (the inputs editor, another tool) is loaded into
 * an open document that has no edits waiting to be written.
 */
@Service(Service.Level.PROJECT)
class StateMachineDocuments(private val project: Project) : Disposable {

    private class Open(val file: LightVirtualFile, val archive: VirtualFile, val id: String, val document: Document, val disposable: Disposable) {
        /** The text last written to or read from the archive. */
        var synced: String = document.text
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    }

    private val open = LinkedHashMap<VirtualFile, Open>()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (!StateMachineVirtualFile.isStateMachine(file) || source.isFileOpen(file)) return
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
                ApplicationManager.getApplication().invokeLater({ for (o in open.values.filter { it.archive in changed }) reload(o) }, project.disposed)
            }
        })
    }

    /** Opens state machine [id] of [archive] in an editor (the existing tab when it is already open). */
    fun open(archive: VirtualFile, id: String): VirtualFile? {
        val file = open.values.firstOrNull { it.archive == archive && it.id == id }?.file ?: create(archive, id) ?: return null
        OpenFileDescriptor(project, file).navigate(true)
        return file
    }

    /** Writes edits of [archive]'s open state machine documents that are still waiting (before another change of the archive). */
    fun flush(archive: VirtualFile) {
        for (o in open.values.filter { it.archive == archive }) {
            o.alarm.cancelAllRequests()
            write(o)
        }
    }

    /** The open document of state machine [id] of [archive], if any (for tests). */
    fun document(archive: VirtualFile, id: String): Document? =
        open.values.firstOrNull { it.archive == archive && it.id == id }?.document

    private fun create(archive: VirtualFile, id: String): VirtualFile? {
        val text = runCatching { DotLottieArchive.read(archive.contentsToByteArray()).stateMachineText(id) }.getOrNull() ?: return null
        val file = StateMachineVirtualFile.create(archive, id, text)
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val disposable = Disposer.newDisposable(this, "Lottie state machine ${StateMachineVirtualFile.path(file)}")
        val entry = Open(file, archive, id, document, disposable)
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
        if (text == o.synced || !o.archive.isValid || MiniJson.errorOf(text) != null) return
        o.synced = text
        DotLottieFiles.replaceEntries(
            project, o.archive, mapOf(StateMachineVirtualFile.entryPath(o.id) to text.toByteArray(Charsets.UTF_8)),
            LottieBundle.message("sm.command.edit.json"), undoable = false,
        )
    }

    private fun reload(o: Open) {
        if (!o.archive.isValid) return
        val text = runCatching { DotLottieArchive.read(o.archive.contentsToByteArray()).stateMachineText(o.id) }.getOrNull() ?: return
        if (text == o.synced) return
        // edits waiting to be written win; they are written next and replace the archive's version
        if (o.document.text != o.synced) return
        o.synced = text
        WriteCommandAction.writeCommandAction(project).withName(LottieBundle.message("sm.command.reload")).run<RuntimeException> {
            o.document.setText(text)
        }
    }

    /**
     * Writes edits still waiting and closes every state machine editor: once this plugin unloads, nothing would write them back into
     * their archives, and an editor opened by this plugin keeps its classes reachable.
     */
    fun closeAll() {
        val entries = open.values.toList()
        for (o in entries) {
            o.alarm.cancelAllRequests()
            write(o)
        }
        open.clear()
        val manager = FileEditorManager.getInstance(project)
        for (o in entries) {
            manager.closeFile(o.file)
            Disposer.dispose(o.disposable)
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
