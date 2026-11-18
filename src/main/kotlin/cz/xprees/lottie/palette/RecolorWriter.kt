package cz.xprees.lottie.palette

import com.intellij.json.psi.JsonFile
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieFiles
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.editor.LottiePsi
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.obj

/** Writes a recolor into a Lottie JSON document or a `.lottie` file, each as one undoable command. */
object RecolorWriter {

    /** Recolors [usages] in the JSON [document], changing only the numbers and strings involved (the file keeps its layout). */
    fun toDocument(project: Project, document: Document, usages: List<ColorUsage>, to: LottieColor): Int {
        val manager = PsiDocumentManager.getInstance(project)
        // Application.runReadAction(Computable): ReadAction.compute is deprecated in newer platforms
        val psi = com.intellij.openapi.application.ApplicationManager.getApplication()
            .runReadAction(com.intellij.openapi.util.Computable { manager.getPsiFile(document) as? JsonFile }) ?: return 0
        val edits = Recolor.edits(usages, to)
        var changed = 0
        // the document is committed and searched inside the command: a palette click is not a write-safe context
        WriteCommandAction.writeCommandAction(project, psi).withName(LottieBundle.message("recolor.command")).run<RuntimeException> {
            manager.commitDocument(document)
            val replacements = edits
                .mapNotNull { edit -> LottiePsi.findByPath(psi, edit.path)?.textRange?.let { it to edit.text } }
                .distinctBy { it.first.startOffset }
                .sortedByDescending { it.first.startOffset }
            for ((range, text) in replacements) document.replaceString(range.startOffset, range.endOffset, text)
            manager.commitDocument(document)
            changed = if (replacements.isEmpty()) 0 else usages.size
        }
        return changed
    }

    /** Recolors [usages] in animation [animationId] of the `.lottie` [file]. */
    fun toDotLottieAnimation(project: Project, file: VirtualFile, animationId: String, usages: List<ColorUsage>, to: LottieColor) {
        val edits = Recolor.edits(usages, to)
        DotLottieFiles.rewrite(project, file, LottieBundle.message("recolor.command")) { bytes ->
            val archive = DotLottieArchive.read(bytes)
            val path = animationPath(archive, animationId) ?: return@rewrite bytes
            val root = MiniJson.parse(archive.bytes(path)!!.toString(Charsets.UTF_8))
            Recolor.apply(root, edits)
            DotLottieWriter.replace(bytes, mapOf(path to MiniJson.write(root).toByteArray(Charsets.UTF_8)))
        }
    }

    /**
     * Recolors [usages] (of palette color [from]) of animation [animationId] through theme [themeId] of the `.lottie` [file]: the
     * properties become slots, the theme gets a rule per slot, and a new theme is listed in the manifest (named [themeName]).
     * Returns how many uses the theme recolors and how many it can't reach.
     */
    fun toDotLottieTheme(
        project: Project,
        file: VirtualFile,
        animationId: String,
        usages: List<ColorUsage>,
        from: LottieColor,
        to: LottieColor,
        themeId: String,
        themeName: String?,
    ): Pair<Int, Int> {
        var result = 0 to 0
        DotLottieFiles.rewrite(project, file, LottieBundle.message("recolor.command.theme", themeId)) { bytes ->
            val archive = DotLottieArchive.read(bytes)
            val path = animationPath(archive, animationId) ?: return@rewrite bytes
            val animation = MiniJson.parse(archive.bytes(path)!!.toString(Charsets.UTF_8)).obj() ?: return@rewrite bytes
            val theme = archive.themeText(themeId)?.let(MiniJson::parseOrNull).obj()
            val change = Recolor.toTheme(animation, usages, from, to, theme, animationId, limitToAnimation = archive.manifest.animations.size > 1)
            result = change.themed to change.skipped.size
            if (change.themed == 0) return@rewrite bytes
            val manifest = archive.bytes("manifest.json")?.toString(Charsets.UTF_8)?.let(MiniJson::parseOrNull).obj() ?: emptyMap()
            DotLottieWriter.replace(
                bytes,
                mapOf(
                    path to MiniJson.write(change.animation).toByteArray(Charsets.UTF_8),
                    "t/$themeId.json" to MiniJson.write(change.theme, pretty = true).toByteArray(Charsets.UTF_8),
                    "manifest.json" to MiniJson.write(Recolor.manifestWithTheme(manifest, themeId, themeName, animationId), pretty = true).toByteArray(Charsets.UTF_8),
                ),
            )
        }
        return result
    }

    private fun animationPath(archive: DotLottieArchive, id: String): String? =
        listOf("a/$id.json", "animations/$id.json").firstOrNull { archive.bytes(it) != null }
}
