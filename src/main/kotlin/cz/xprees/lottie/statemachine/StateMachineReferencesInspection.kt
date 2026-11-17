package cz.xprees.lottie.statemachine

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.editor.LottiePsi

/**
 * Broken references in a dotLottie state machine opened from its `.lottie` file: unknown or wrongly typed inputs (also `$input`
 * references), unknown states, and animations, markers, themes and layers its archive doesn't have.
 */
class StateMachineReferencesInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val json = file as? JsonFile ?: return null
        val virtualFile = json.virtualFile as? StateMachineVirtualFile ?: json.originalFile.virtualFile as? StateMachineVirtualFile ?: return null
        val machine = StateMachine.parse(json.text) ?: return null
        val problems = StateMachineChecker.check(machine, context(virtualFile))
        return problems.mapNotNull { problem ->
            val element = LottiePsi.findByPath(json, problem.path) ?: return@mapNotNull null
            manager.createProblemDescriptor(element, problem.message, isOnTheFly, emptyArray(), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        }.toTypedArray()
    }

    private class CachedContext(val stamp: Long, val context: StateMachineContext?)

    /** The archive's animations, themes, markers and layers, cached until the archive changes. */
    private fun context(file: StateMachineVirtualFile): StateMachineContext? {
        val archive = file.archive
        if (!archive.isValid) return null
        archive.getUserData(CONTEXT)?.takeIf { it.stamp == archive.modificationStamp }?.let { return it.context }
        val context = runCatching { DotLottieArchive.read(archive.contentsToByteArray()).stateMachineContext() }.getOrNull()
        archive.putUserData(CONTEXT, CachedContext(archive.modificationStamp, context))
        return context
    }

    companion object {
        private val CONTEXT = Key.create<CachedContext>("cz.xprees.lottie.stateMachineContext")
    }
}
