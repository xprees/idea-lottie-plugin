package cz.xprees.lottie.preview

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.lottie.lang.LottieFiles

/** Lottie `.json` files open in a split editor: the JSON text editor next to the live preview, like SVG files. */
class LottieJsonEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = LottieFiles.isLottieJson(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val document = FileDocumentManager.getInstance().getDocument(file) ?: textEditor.editor.document
        return LottieSplitEditor(project, textEditor, LottiePreviewFileEditor(project, file, document))
    }

    override fun getEditorTypeId() = "lottie-json-split-editor"

    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** `.lottie` files are binary archives: they open in the preview only. */
class DotLottieEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = LottieFiles.isDotLottie(file)

    override fun acceptRequiresReadAction() = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = LottiePreviewFileEditor(project, file, null)

    override fun getEditorTypeId() = "dotlottie-preview-editor"

    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
