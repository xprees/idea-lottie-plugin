package cz.xprees.lottie.preview

import com.intellij.openapi.command.undo.DocumentReference
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.DocumentReferenceProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.lottie.LottieBundle
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** The preview as a file editor: the right half of the Lottie JSON split editor, or the whole editor of a `.lottie` file. */
class LottiePreviewFileEditor(project: Project, private val file: VirtualFile, private val document: Document?) :
    UserDataHolderBase(), FileEditor, DocumentReferenceProvider {

    val panel = LottiePreviewPanel(project, file, document, this)

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    /**
     * What undo in this editor applies to: for Lottie JSON the document (the same stack as the text editor next to it, so marker
     * edits from the timeline and changes made in the JSON undo in one sequence), for `.lottie` archives the archive's change history.
     */
    override fun getDocumentReferences(): Collection<DocumentReference> =
        listOf(document?.let { DocumentReferenceManager.getInstance().create(it) } ?: cz.xprees.lottie.dotlottie.DotLottieUndoHistory.getInstance().reference(file))

    override fun getName(): String = LottieBundle.message("editor.preview.name")

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {}

    override fun isModified() = false

    override fun isValid() = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        Disposer.dispose(panel)
    }
}
