package cz.xprees.lottie.dotlottie

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.DocumentReference
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Undo and redo of `.lottie` file changes, recorded without putting any of this plugin's classes on the IDE's undo stacks (which
 * outlive the plugin and would keep it from unloading without a restart).
 *
 * Each archive gets a small history document, plain platform text that grows by one character per change. A change of the archive
 * is recorded as that one-character insertion in the same command, so the platform undoes and redoes it like any text edit; this
 * service listens to the document and puts back the archive bytes of the revision it now stands at. When the plugin unloads, the
 * service and its listeners go away and the stacks only hold the platform's own document.
 */
@Service(Service.Level.APP)
class DotLottieUndoHistory : Disposable {

    private class History(val document: Document) {
        /** The archive before and after each change, by the history document's length when it was made. */
        val revisions = HashMap<Int, Pair<ByteArray, ByteArray>>()
        /** The document length the archive's bytes correspond to. */
        var applied = 0
        var recording = false
    }

    private val histories = ConcurrentHashMap<VirtualFile, History>()

    /** The history document of [file], to list among an editor's undo references so its changes undo there. */
    fun reference(file: VirtualFile): DocumentReference = DocumentReferenceManager.getInstance().create(history(file).document)

    /** Records the change of [file] from [before] to [after] as part of the running command (inside a write action). */
    fun record(file: VirtualFile, before: ByteArray, after: ByteArray) {
        val history = history(file)
        val index = history.document.textLength
        history.recording = true
        try {
            history.document.insertString(index, "•")
        } finally {
            history.recording = false
        }
        // a new change after undo replaces what could have been redone
        history.revisions.keys.removeIf { it >= index || it < index - MAX_REVISIONS }
        history.revisions[index] = before to after
        history.applied = index + 1
    }

    private fun history(file: VirtualFile): History = histories.computeIfAbsent(file) {
        val history = History(EditorFactory.getInstance().createDocument(""))
        history.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (history.recording) return
                val length = history.document.textLength
                val bytes = when {
                    // undone back to revision `length`: the archive as it was before that change
                    length < history.applied -> history.revisions[length]?.first
                    // redone up to `length`: the archive after its last change
                    length > history.applied -> history.revisions[length - 1]?.second
                    else -> null
                }
                history.applied = length
                if (bytes != null && file.isValid) {
                    ApplicationManager.getApplication().runWriteAction { file.setBinaryContent(bytes) }
                }
            }
        }, this)
        history
    }

    override fun dispose() {
        histories.clear()
    }

    companion object {
        /** Changes kept per archive for undo, as many as the IDE keeps for a document by default. */
        const val MAX_REVISIONS = 100

        fun getInstance(): DotLottieUndoHistory = ApplicationManager.getApplication().getService(DotLottieUndoHistory::class.java)
    }
}
