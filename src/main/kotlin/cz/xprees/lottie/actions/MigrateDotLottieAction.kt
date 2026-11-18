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
import cz.xprees.lottie.statemachine.StateMachineVirtualFile
import java.util.function.Function
import javax.swing.JComponent

/**
 * Converts dotLottie 1.0 files to dotLottie 2.0 in place, each as one undoable change: the file in the editor, or every 1.0 file
 * among those selected in the Project view. Shown only when there is one.
 */
class MigrateDotLottieAction : DumbAwareAction(
    LottieBundle.messagePointer("action.migrate.text"),
    LottieBundle.messagePointer("action.migrate.description"),
    AllIcons.Actions.Refresh,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = v1Files(e)
        e.presentation.isEnabledAndVisible = e.project != null && files.isNotEmpty()
        if (files.size > 1) e.presentation.text = LottieBundle.message("action.migrate.text.many", files.size)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = v1Files(e)
        if (files.size == 1) migrate(project, files.single()) else migrateAll(project, files)
    }

    /** The selected dotLottie 1.0 files (the selection in the Project view, or the file of the editor). */
    private fun v1Files(e: AnActionEvent): List<VirtualFile> {
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        return selected.filter { DotLottieVersions.isV1(it) }
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

        /** Converts each of [files], with one notification listing what was converted and what failed. */
        fun migrateAll(project: Project, files: List<VirtualFile>) {
            val converted = ArrayList<String>()
            val failed = ArrayList<String>()
            val dropped = LinkedHashSet<String>()
            for (file in files) {
                val result = try {
                    DotLottieMigration.toV2(file.contentsToByteArray(), LottieNotifications.GENERATOR)
                } catch (ex: Exception) {
                    failed += "${file.name} (${ex.message ?: ex.javaClass.simpleName})"
                    continue
                } ?: continue
                DotLottieFiles.rewrite(project, file, LottieBundle.message("action.migrate.command")) { result.bytes }
                EditorNotifications.getInstance(project).updateNotifications(file)
                converted += file.name
                dropped += result.dropped
            }
            if (converted.isNotEmpty()) {
                val text = LottieBundle.message("action.migrate.done.many", converted.size, converted.joinToString(", ")) +
                    if (dropped.isEmpty()) "" else " " + LottieBundle.message("action.migrate.dropped.many", dropped.joinToString(", "))
                LottieNotifications.info(project, text)
            }
            if (failed.isNotEmpty()) LottieNotifications.error(project, LottieBundle.message("action.migrate.failed.many", failed.joinToString(", ")))
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

/**
 * Editor tabs: the dotLottie version after the name of a `.lottie` file (`toggle.lottie · v2`), and the archive after the name of a
 * state machine opened from one (`toggle.json · toggle.lottie`).
 */
class DotLottieTabTitleProvider : com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        StateMachineVirtualFile.archive(file)?.let { archive ->
            return LottieBundle.message("tab.title.state.machine", file.presentableName, archive.name)
        }
        if (!LottieFiles.isDotLottie(file)) return null
        val version = DotLottieVersions.version(file) ?: return null
        return LottieBundle.message("tab.title.version", file.presentableName, version)
    }

    /**
     * The tooltip of a state machine's tab: the archive's path plus the entry. Platforms from 2026.3 call this method (it replaces
     * the deprecated `getEditorTabTooltipText`); it is not marked `override` because the interface of the oldest supported platform
     * (2026.1) doesn't have it yet, so on 2026.1 and 2026.2 the tab shows the default tooltip and the title still names the archive.
     */
    @Suppress("unused")
    fun getEditorTabTooltipHtml(project: Project, file: VirtualFile): com.intellij.openapi.util.text.HtmlChunk? =
        StateMachineVirtualFile.path(file)?.let { com.intellij.openapi.util.text.HtmlChunk.text(it) }
}

/**
 * A banner over dotLottie 1.0 files offering the conversion to 2.0. It can be closed for the file (until the IDE restarts) or turned
 * off for good (Settings › Languages & Frameworks › Lottie brings it back); the version in the preview toolbar keeps the conversion
 * at hand.
 */
class DotLottieV1NotificationProvider : EditorNotificationProvider, com.intellij.openapi.project.DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!cz.xprees.lottie.settings.LottieSettings.getInstance().state.dotLottieV1Banner) return null
        if (file.getUserData(DISMISSED) == true || !DotLottieVersions.isV1(file)) return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = LottieBundle.message("migrate.banner.text")
                createActionLabel(LottieBundle.message("action.migrate.text")) { MigrateDotLottieAction.migrate(project, file) }
                createActionLabel(LottieBundle.message("migrate.banner.never")) {
                    cz.xprees.lottie.settings.LottieSettings.getInstance().update { dotLottieV1Banner = false }
                    EditorNotifications.updateAll()
                }
                setCloseAction {
                    file.putUserData(DISMISSED, true)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
            }
        }
    }

    private companion object {
        /** Closed for this file, until the IDE restarts. A Boolean: file user data outlives the plugin, so no plugin classes there. */
        val DISMISSED = Key.create<Boolean>("cz.xprees.lottie.v1BannerDismissed")
    }
}
