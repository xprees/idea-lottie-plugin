package cz.xprees.lottie.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieFiles
import cz.xprees.lottie.dotlottie.DotLottieMigration
import cz.xprees.lottie.lang.LottieFiles
import java.util.function.Function
import javax.swing.JComponent

/** Converts a dotLottie 1.0 file to dotLottie 2.0 in place (one undoable change). Shown only for 1.0 files. */
class MigrateDotLottieAction : DumbAwareAction(
    LottieBundle.messagePointer("action.migrate.text"),
    LottieBundle.messagePointer("action.migrate.description"),
    AllIcons.Actions.Refresh,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && DotLottieVersions.isV1(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        migrate(project, file)
    }

    companion object {
        fun migrate(project: Project, file: VirtualFile) {
            val result = try {
                DotLottieMigration.toV2(file.contentsToByteArray(), LottieNotifications.GENERATOR) ?: return
            } catch (ex: Exception) {
                LottieNotifications.error(project, LottieBundle.message("action.migrate.failed", ex.message ?: ex.toString()))
                return
            }
            DotLottieFiles.rewrite(project, file, LottieBundle.message("action.migrate.command")) { result.bytes }
            EditorNotifications.getInstance(project).updateNotifications(file)
            val message = if (result.dropped.isEmpty()) {
                LottieBundle.message("action.migrate.done", file.name)
            } else {
                LottieBundle.message("action.migrate.done.dropped", file.name, result.dropped.joinToString(", "))
            }
            LottieNotifications.info(project, message)
        }
    }
}

/** The dotLottie version of `.lottie` files, read once per file modification. */
internal object DotLottieVersions {
    private val KEY = Key.create<Pair<Long, Int>>("cz.xprees.lottie.specVersion")

    fun isV1(file: VirtualFile): Boolean = LottieFiles.isDotLottie(file) && version(file) == 1

    fun version(file: VirtualFile): Int? {
        val stamp = file.modificationStamp
        file.getUserData(KEY)?.takeIf { it.first == stamp }?.let { return it.second }
        val version = try {
            DotLottieArchive.read(file.contentsToByteArray()).specVersion
        } catch (_: Exception) {
            return null
        }
        file.putUserData(KEY, stamp to version)
        return version
    }
}

/** The dotLottie version after the name in a `.lottie` file's editor tab (`toggle.lottie · v2`). */
class DotLottieTabTitleProvider : com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        if (!LottieFiles.isDotLottie(file)) return null
        val version = DotLottieVersions.version(file) ?: return null
        return LottieBundle.message("tab.title.version", file.presentableName, version)
    }
}

/** A banner over dotLottie 1.0 files offering the conversion to 2.0. */
class DotLottieV1NotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!DotLottieVersions.isV1(file)) return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = LottieBundle.message("migrate.banner.text")
                createActionLabel(LottieBundle.message("action.migrate.text")) { MigrateDotLottieAction.migrate(project, file) }
            }
        }
    }
}
