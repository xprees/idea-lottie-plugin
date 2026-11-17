package cz.xprees.lottie.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.LottieIcons
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.lang.LottieFiles
import java.io.File

/** Writes an animation of a `.lottie` file as a standalone Lottie JSON file next to it (images embedded) and opens it. */
class ExtractAnimationAction : DumbAwareAction(
    LottieBundle.messagePointer("action.extract.text"),
    LottieBundle.messagePointer("action.extract.description"),
    AllIcons.Actions.Download,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && LottieFiles.isDotLottie(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val archive = try {
            DotLottieArchive.read(file.contentsToByteArray())
        } catch (ex: Exception) {
            LottieNotifications.error(project, LottieBundle.message("action.extract.failed", ex.message ?: ex.toString()))
            return
        }
        val ids = archive.manifest.animations.map { it.id }
        if (ids.size == 1) {
            extract(project, file, archive, ids.single(), single = true)
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(ids)
            .setTitle(LottieBundle.message("action.extract.choose"))
            .setItemChosenCallback { extract(project, file, archive, it, single = false) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun extract(project: Project, file: VirtualFile, archive: DotLottieArchive, id: String, single: Boolean) {
        val json = archive.extractAnimation(id) ?: return
        val directory = file.parent ?: return
        val base = if (single) file.nameWithoutExtension else "${file.nameWithoutExtension}-${DotLottieWriter.sanitizeId(id)}"
        val created = WriteCommandAction.writeCommandAction(project).withName(LottieBundle.message("action.extract.text")).compute<VirtualFile, Exception> {
            val name = LottieFiles.uniqueName(directory, base, "json")
            directory.createChildData(this, name).also { VfsUtilCore.saveText(it, json) }
        }
        FileEditorManager.getInstance(project).openFile(created, true)
    }
}

/** Packs a Lottie JSON file (and the images it references) into a dotLottie 2.0 archive next to it, and opens it. */
class PackDotLottieAction : DumbAwareAction(
    LottieBundle.messagePointer("action.pack.text"),
    LottieBundle.messagePointer("action.pack.description"),
    LottieIcons.DOT_LOTTIE,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && LottieFiles.isLottieJson(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val directory = file.parent ?: return
        // unsaved edits count: pack what the editor shows
        val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: VfsUtilCore.loadText(file)
        val base = file.name.removeSuffix(".json").removeSuffix(".lottie").removeSuffix(".JSON")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, LottieBundle.message("action.pack.progress"), true) {
            private var bytes: ByteArray? = null

            override fun run(indicator: ProgressIndicator) {
                val root = try {
                    MiniJson.parse(text).obj()
                } catch (ex: MiniJson.ParseException) {
                    LottieNotifications.error(project, LottieBundle.message("action.pack.invalid", ex.message ?: ""))
                    return
                } ?: return
                val folder = file.parent?.toNioPath()?.toFile()
                bytes = DotLottieWriter.pack(base, root, generator()) { u, p ->
                    if (folder == null || u.startsWith("http:") || u.startsWith("https:")) return@pack null
                    val candidate = File(File(folder, u), p).canonicalFile
                    // only files inside the animation's folder tree
                    if (candidate.path.startsWith(folder.canonicalPath) && candidate.isFile) candidate.readBytes() else null
                }
            }

            override fun onSuccess() {
                val archive = bytes ?: return
                val created = WriteCommandAction.writeCommandAction(project).withName(LottieBundle.message("action.pack.text")).compute<VirtualFile, Exception> {
                    val name = LottieFiles.uniqueName(directory, base, "lottie")
                    directory.createChildData(this, name).also { it.setBinaryContent(archive) }
                }
                FileEditorManager.getInstance(project).openFile(created, true)
            }
        })
    }

    private fun generator(): String {
        val version = PluginManagerCore.getPlugin(PluginId.getId("cz.xprees.lottie"))?.version
        return listOfNotNull("Lottie for JetBrains IDEs", version).joinToString(" ")
    }
}

internal object LottieNotifications {
    fun error(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup("Lottie").createNotification(message, NotificationType.ERROR).notify(project)
        }
    }
}
