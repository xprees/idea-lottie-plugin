package cz.xprees.lottie.mcp

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import cz.xprees.lottie.dotlottie.DotLottieFiles
import cz.xprees.lottie.lang.LottieFiles
import cz.xprees.lottie.mcp.LottieMcpTools.Companion.json
import cz.xprees.lottie.optimize.OptimizeOptions
import cz.xprees.lottie.optimize.Optimizer
import cz.xprees.lottie.palette.LottieColor
import cz.xprees.lottie.palette.PaletteScanner
import cz.xprees.lottie.palette.RecolorWriter
import cz.xprees.lottie.snippets.AgentPrompt
import cz.xprees.lottie.snippets.CodePlatform
import cz.xprees.lottie.snippets.CodePlatforms
import cz.xprees.lottie.snippets.SnippetContext
import cz.xprees.lottie.snippets.SnippetGenerator
import cz.xprees.lottie.snippets.SnippetMarker
import cz.xprees.lottie.model.LottieModel

// The authoring tools: integration code, recoloring (also into dotLottie themes) and optimizing.

/** Every player [codeFor] writes code for, as the tool takes them. */
val ALL_PLATFORMS: List<String> = CodePlatform.entries.map { it.name.lowercase().replace('_', '-') }

/**
 * The code that uses an animation in an app for [platform] (default: the project's player), as the preview's *Code* tab writes it:
 * the setup component, playing and following each marker, and for `.lottie` files the state machine [stateMachine] (default: the
 * only one) with its inputs and custom events. With [prompt], also the *Copy prompt for an AI agent* text.
 */
fun LottieMcpTools.codeFor(file: VirtualFile, platform: String? = null, typeScript: Boolean? = null, stateMachine: String? = null, prompt: Boolean = false): String {
    val player = if (platform == null) CodePlatforms.getInstance(project).current else {
        val wanted = platform.trim().lowercase().replace('_', '-')
        CodePlatform.entries.firstOrNull { it.name.lowercase().replace('_', '-') == wanted || it.packageName.lowercase() == wanted }
            ?: throw ToolError("Unknown platform '$platform'. Platforms: ${ALL_PLATFORMS.joinToString()}.")
    }
    val ts = typeScript ?: if (platform == null) CodePlatforms.getInstance(project).typeScript else true
    val dotLottie = LottieFiles.isDotLottie(file)
    val (_, tree) = animationRoot(file, null)
    val info = info(file, tree)
    var machineId: String? = null
    var machine: cz.xprees.lottie.statemachine.StateMachine? = null
    if (dotLottie) {
        val archive = archive(file)
        val ids = archive.manifest.stateMachines.map { it.id } + archive.entries.map { it.path }.filter { it.startsWith("s/") && it.endsWith(".json") }
            .map { it.removePrefix("s/").removeSuffix(".json") }
        if (stateMachine != null || ids.distinct().size == 1) {
            machineId = stateMachineId(file, archive, stateMachine)
            machine = machine(file, archive, machineId)
        } else if (ids.isNotEmpty() && archive.manifest.initialStateMachine in ids) {
            machineId = archive.manifest.initialStateMachine
            machine = machine(file, archive, machineId!!)
        }
    } else if (stateMachine != null) throw ToolError("'stateMachine' only applies to .lottie files.")
    val context = SnippetContext(
        path = pathOf(file),
        dotLottie = dotLottie,
        markers = info.markers.map { SnippetMarker(it.comment, it.start, it.duration, it.name) },
        inPoint = info.inPoint,
        stateMachineId = machineId,
        inputs = machine?.inputs.orEmpty(),
        customEvents = machine?.customEvents.orEmpty(),
    )
    val snippets = SnippetGenerator.generate(player, context, ts)
    val result = linkedMapOf<String, Any?>(
        "path" to pathOf(file),
        "platform" to player.name.lowercase().replace('_', '-'),
        "package" to player.packageName,
    )
    if (player.typeScript) result["language"] = if (ts) "TypeScript" else "JavaScript"
    if (machineId != null) result["stateMachine"] = machineId
    result["snippets"] = snippets.snippets.map { s ->
        linkedMapOf<String, Any?>("kind" to s.kind.name.lowercase(), "subject" to s.subject, "extension" to s.extension, "code" to s.code).filterValues { it != null }
    }
    if (snippets.notes.isNotEmpty()) result["notes"] = snippets.notes.map {
        when (it) {
            cz.xprees.lottie.snippets.SnippetNote.JSON_ONLY -> "this player plays Lottie JSON only: export the animation as JSON (lottie_extract) or use a dotLottie player"
            cz.xprees.lottie.snippets.SnippetNote.NO_STATE_MACHINES -> "this player has no state machines: use a dotLottie player for them"
            cz.xprees.lottie.snippets.SnippetNote.NO_MARKER_EVENTS -> "this player doesn't report the playback position, so entering a marker can't be followed"
        }
    }
    if (prompt) result["prompt"] = AgentPrompt.build(player, player.packageName, ts, context)
    return json(result)
}

/** `#rrggbb` / `#rrggbbaa` as a color, or a [ToolError] naming [what]. */
internal fun parseColor(value: String, what: String): LottieColor {
    val hex = value.trim().removePrefix("#")
    if (!hex.matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) throw ToolError("'$what' is a color like #1e66f5 or #1e66f580, not '$value'.")
    fun c(i: Int) = hex.substring(i, i + 2).toInt(16) / 255.0
    return LottieColor(c(0), c(2), c(4), if (hex.length == 8) c(6) else 1.0, hasAlpha = hex.length == 8)
}

/**
 * Recolors every use of palette color [from] to [to] (fills, strokes, gradient stops, keyframes, solid layers, text) as one
 * undoable change. For a dotLottie 2.0 file with [theme], the change goes into that theme instead (created when missing, named
 * [themeName]): the colors become slots and the animation itself keeps its colors.
 */
fun LottieMcpTools.recolor(file: VirtualFile, from: String, to: String, animation: String? = null, theme: String? = null, themeName: String? = null): String {
    val source = parseColor(from, "from")
    val target = parseColor(to, "to")
    val (id, tree) = animationRoot(file, animation)
    info(file, tree)
    val palette = PaletteScanner.palette(PaletteScanner.scan(tree))
    val entry = palette.firstOrNull { it.color.hex.equals(source.hex, ignoreCase = true) }
        ?: palette.firstOrNull { it.color.hex.take(7).equals(source.hex.take(7), ignoreCase = true) }
        ?: throw ToolError("No color ${source.hex} in '${pathOf(file)}'. Palette: ${palette.take(20).joinToString { it.color.hex }}.")
    val result = linkedMapOf<String, Any?>("path" to pathOf(file))
    if (id != null) result["animation"] = id
    result["from"] = entry.color.hex
    result["to"] = target.hex
    if (theme != null) {
        if (!LottieFiles.isDotLottie(file)) throw ToolError("Themes are part of dotLottie files; '${pathOf(file)}' is Lottie JSON (lottie_pack makes a .lottie).")
        val archive = archive(file)
        if (archive.specVersion < 2) throw ToolError("Themes need dotLottie 2.0; convert the file first (dotlottie_convert_to_v2).")
        val themeId = theme.trim().replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { throw ToolError("The theme id is empty.") }
        val (themed, skipped) = RecolorWriter.toDotLottieTheme(project, file, id ?: archive.defaultAnimationId!!, entry.usages, entry.color, target, themeId, themeName)
        if (themed == 0) throw ToolError("None of the ${entry.count} uses of ${entry.color.hex} can be themed (gradients, solid layers and text can't); recolor without 'theme' to change them in the animation.")
        result["theme"] = themeId
        result["themed"] = themed
        if (skipped > 0) result["notThemed"] = "$skipped uses (gradients, solid layers or text) can't be themed and keep their color"
        result["themes"] = archive(file).stateMachineContext().themeIds.sorted()
        result["undo"] = "one undoable command"
        return json(result)
    }
    if (LottieFiles.isDotLottie(file)) {
        RecolorWriter.toDotLottieAnimation(project, file, id!!, entry.usages, target)
    } else {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: throw ToolError("'${pathOf(file)}' can't be edited as text.")
        if (RecolorWriter.toDocument(project, document, entry.usages, target) == 0) throw ToolError("The color couldn't be changed: '${pathOf(file)}' doesn't parse as JSON.")
        FileDocumentManager.getInstance().saveDocument(document)
    }
    result["recolored"] = entry.count
    val (_, after) = animationRoot(file, id.takeIf { LottieFiles.isDotLottie(file) })
    result["palette"] = PaletteScanner.palette(PaletteScanner.scan(after)).take(20).map { linkedMapOf("color" to it.color.hex, "uses" to it.count) }
    result["undo"] = "one undoable command"
    return json(result)
}

/**
 * Optimizes a Lottie JSON or `.lottie` file: lossless cleanup, rounding to [precision] decimals (null keeps numbers) and, with
 * [removeHiddenLayers], hidden and never-visible layers. Saved as `name.optimized.ext` next to it, or with [inPlace] as one undoable
 * change of the file.
 */
fun LottieMcpTools.optimize(file: VirtualFile, inPlace: Boolean = false, precision: Int? = 3, removeHiddenLayers: Boolean = false): String {
    if (precision != null && precision !in 0..5) throw ToolError("'precision' is 0 to 5 decimals.")
    val dotLottie = LottieFiles.isDotLottie(file)
    val options = OptimizeOptions(cleanup = true, precision = precision, removeHiddenLayers = removeHiddenLayers)
    val result = try {
        if (dotLottie) Optimizer.optimizeDotLottie(file.contentsToByteArray(), options) else {
            val text = text(file)
            if (LottieModel.read(text) == null || !Optimizer.isLottie(text)) throw ToolError("'${pathOf(file)}' is not a Lottie animation.")
            Optimizer.optimizeJson(text, options)
        }
    } catch (e: ToolError) {
        throw e
    } catch (e: Exception) {
        throw ToolError("'${pathOf(file)}' couldn't be optimized: ${e.message ?: e.javaClass.simpleName}")
    }
    val out = linkedMapOf<String, Any?>(
        "path" to pathOf(file),
        "sizeBefore" to result.sizeBefore,
        "sizeAfter" to result.sizeAfter,
        "savedPercent" to Math.round(result.percent * 10) / 10.0,
        "steps" to result.savings.associate { (step, saved) -> step.name.lowercase() to saved },
    )
    if (result.layersRemoved.isNotEmpty()) out["layersRemoved"] = result.layersRemoved
    if (result.saved <= 0) {
        out["written"] = false
        out["reason"] = "nothing to save"
        return json(out)
    }
    if (inPlace) {
        if (dotLottie) {
            DotLottieFiles.rewrite(project, file, "Optimize Lottie") { result.bytes }
        } else {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: throw ToolError("'${pathOf(file)}' can't be edited as text.")
            WriteCommandAction.writeCommandAction(project, PsiManager.getInstance(project).findFile(file)).withName("Optimize Lottie").run<RuntimeException> {
                document.setText(result.bytes.toString(Charsets.UTF_8))
            }
            FileDocumentManager.getInstance().saveDocument(document)
        }
        out["written"] = pathOf(file)
        out["undo"] = "one undoable command"
    } else {
        val extension = if (dotLottie) "lottie" else "json"
        val base = file.name.removeSuffix(".$extension").removeSuffix(".${extension.uppercase()}") + ".optimized"
        out["written"] = pathOf(create(file, base, extension, "Optimize Lottie") { it.setBinaryContent(result.bytes) })
    }
    return json(out)
}

