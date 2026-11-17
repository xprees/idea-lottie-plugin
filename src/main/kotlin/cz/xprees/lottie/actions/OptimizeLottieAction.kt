package cz.xprees.lottie.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.dotlottie.DotLottieFiles
import cz.xprees.lottie.lang.LottieFiles
import cz.xprees.lottie.optimize.OptimizeDialog
import cz.xprees.lottie.optimize.OptimizeResult

/** *Optimize Lottie…*: the optimize dialog for a Lottie JSON or `.lottie` file, then a smaller copy or the file rewritten in place. */
class OptimizeLottieAction : DumbAwareAction(
    LottieBundle.messagePointer("action.optimize.text"),
    LottieBundle.messagePointer("action.optimize.description"),
    AllIcons.Actions.Lightning,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && LottieFiles.isLottie(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val dialog = try {
            OptimizeDialog(project, file)
        } catch (ex: Exception) {
            LottieNotifications.error(project, LottieBundle.message("optimize.failed", ex.message ?: ex.toString()))
            return
        }
        if (!dialog.showAndGet()) return
        val result = dialog.result ?: return
        when (dialog.mode) {
            OptimizeDialog.Mode.COPY -> saveCopy(project, file, result)
            OptimizeDialog.Mode.IN_PLACE -> rewrite(project, file, result)
        }
        LottieNotifications.info(project, report(file.name, result))
    }

    private fun saveCopy(project: Project, file: VirtualFile, result: OptimizeResult) {
        val directory = file.parent ?: return
        val dotLottie = LottieFiles.isDotLottie(file)
        val extension = if (dotLottie) "lottie" else "json"
        val base = file.name.removeSuffix(".$extension").removeSuffix(".${extension.uppercase()}") + ".optimized"
        val created = WriteCommandAction.writeCommandAction(project).withName(LottieBundle.message("action.optimize.text")).compute<VirtualFile, Exception> {
            directory.createChildData(this, LottieFiles.uniqueName(directory, base, extension)).also { it.setBinaryContent(result.bytes) }
        }
        OpenFileDescriptor(project, created).navigate(true)
    }

    internal fun rewrite(project: Project, file: VirtualFile, result: OptimizeResult) {
        val command = LottieBundle.message("action.optimize.command")
        if (LottieFiles.isDotLottie(file)) {
            DotLottieFiles.rewrite(project, file, command) { result.bytes }
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val psiFile = PsiManager.getInstance(project).findFile(file)
        val editors = EditorFactory.getInstance().getEditors(document, project)
        WriteCommandAction.writeCommandAction(project, psiFile).withName(command).run<RuntimeException> {
            document.setText(result.bytes.toString(Charsets.UTF_8))
            // the caret where the change is (the whole document): undo restores this editor state before it reverts the change,
            // so with the caret left here a single Ctrl+Z undoes the optimization
            editors.forEach {
                it.caretModel.moveToOffset(0)
                it.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            }
        }
        // back to the text editor, so Ctrl+Z goes to it (the action ran from a popup and a dialog)
        editors.firstOrNull { it.component.isShowing }?.let { editor ->
            ApplicationManager.getApplication().invokeLater { IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true) }
        }
    }

    private fun report(name: String, r: OptimizeResult): String {
        val steps = r.savings.joinToString(", ") { (step, saved) -> "${OptimizeDialog.stepName(step)} ${OptimizeDialog.signed(saved)}" }
        val layers = if (r.layersRemoved.isEmpty()) "" else " " + LottieBundle.message("optimize.report.layers", r.layersRemoved.size)
        return LottieBundle.message(
            "optimize.report", name, StringUtil.formatFileSize(r.sizeBefore), StringUtil.formatFileSize(r.sizeAfter),
            OptimizeDialog.percent(r.saved, r.sizeBefore), steps,
        ) + layers
    }
}
