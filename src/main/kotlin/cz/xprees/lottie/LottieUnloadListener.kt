package cz.xprees.lottie

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.ProjectManager
import cz.xprees.lottie.statemachine.StateMachineDocuments

/** Lets the plugin be disabled, updated or uninstalled without an IDE restart: puts away what would outlive it. */
class LottieUnloadListener : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) project.serviceIfCreated<StateMachineDocuments>()?.closeAll()
        }
    }

    companion object {
        const val PLUGIN_ID = "cz.xprees.lottie"
    }
}
