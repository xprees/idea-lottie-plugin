package cz.xprees.lottie.mcp

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.lottie.actions.LottieNotifications
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieFiles
import cz.xprees.lottie.dotlottie.DotLottieMigration
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.editor.DotLottieMarkerEditor
import cz.xprees.lottie.editor.LottieMarkerEditor
import cz.xprees.lottie.editor.MarkerEditor
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.json.str
import cz.xprees.lottie.lang.LottieFiles
import cz.xprees.lottie.model.JsonPath
import cz.xprees.lottie.model.LottieInfo
import cz.xprees.lottie.model.LottieModel
import cz.xprees.lottie.palette.PaletteScanner
import cz.xprees.lottie.snippets.CodePlatform
import cz.xprees.lottie.snippets.SnippetContext
import cz.xprees.lottie.snippets.SnippetGenerator
import cz.xprees.lottie.snippets.SnippetKind
import cz.xprees.lottie.statemachine.InputType
import cz.xprees.lottie.statemachine.StateMachine
import cz.xprees.lottie.statemachine.StateMachineChecker
import cz.xprees.lottie.statemachine.StateMachineDocuments
import cz.xprees.lottie.statemachine.StateMachineInput
import cz.xprees.lottie.statemachine.StateMachineVirtualFile
import cz.xprees.lottie.preview.FrameRenderRequest
import java.io.File
import java.nio.file.Path

/** A tool call that can't be done, with a message that tells the agent what to change. */
class ToolError(message: String) : Exception(message)

/**
 * What the MCP tools of the plugin do, without the MCP server's API (so it is tested directly and loads without that plugin):
 * read Lottie and dotLottie files of the project and edit them, each edit one undoable command. Results are compact JSON.
 *
 * Paths are relative to [root] (the project directory); anything outside it is refused. Reads see unsaved editor changes. The read
 * functions need read access, the edits run on the EDT ([LottieToolset] arranges both); [resolve] runs before either, as it may
 * refresh the file system.
 */
class LottieMcpTools(internal val project: Project, internal val root: VirtualFile) {

    // ---- files -----------------------------------------------------------------------------------------------------------

    /**
     * The file [path] names: relative to the project directory, or absolute inside it. Files created on disk a moment ago are found
     * when [refresh] is on (not in a read action).
     */
    fun resolve(path: String, refresh: Boolean = false): VirtualFile {
        val relative = relativize(path)
        val file = (if (relative.isEmpty()) root else root.findFileByRelativePath(relative))
            ?: if (refresh && root.isInLocalFileSystem) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(root.path, relative)) else null
        if (file == null || !file.isValid) throw ToolError("No file '$path' in the project. Paths are relative to the project directory (${root.path}).")
        if (file.isDirectory) throw ToolError("'$path' is a directory; give the path of a Lottie JSON or .lottie file.")
        return file
    }

    /** [path] as a normalized path relative to [root], refusing paths that leave it. */
    internal fun relativize(path: String): String {
        val unified = path.trim().replace('\\', '/')
        if (unified.isEmpty()) throw ToolError("The path is empty; give the path of a Lottie JSON or .lottie file relative to the project directory.")
        val rootPath = root.path.trimEnd('/')
        val inner = when {
            unified == rootPath -> ""
            unified.startsWith("$rootPath/") -> unified.removePrefix("$rootPath/")
            unified.startsWith("/") || unified.matches(Regex("^[A-Za-z]:/.*")) -> throw outside(path)
            else -> unified
        }
        val parts = ArrayList<String>()
        for (part in inner.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) throw outside(path) else parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun outside(path: String) = ToolError("'$path' is outside the project; only files in ${root.path} can be used.")

    /** [file]'s path relative to the project directory, as results show it. */
    fun pathOf(file: VirtualFile): String = VfsUtilCore.getRelativePath(file, root) ?: file.path

    internal fun requireDotLottie(file: VirtualFile) {
        if (!LottieFiles.isDotLottie(file)) throw ToolError("'${pathOf(file)}' is not a dotLottie (.lottie) file.")
    }

    /** The file's text as the editor has it (unsaved changes included). */
    internal fun text(file: VirtualFile): String =
        FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)

    internal fun archive(file: VirtualFile): DotLottieArchive {
        requireDotLottie(file)
        return try {
            DotLottieArchive.read(file.contentsToByteArray())
        } catch (e: Exception) {
            throw ToolError("'${pathOf(file)}' can't be read as a dotLottie archive: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** The animation JSON tree of a Lottie JSON file, or of animation [animation] (default: the initial one) of a `.lottie` file. */
    internal fun animationRoot(file: VirtualFile, animation: String?): Pair<String?, Any?> {
        if (LottieFiles.isDotLottie(file)) {
            val archive = archive(file)
            val ids = archive.manifest.animations.map { it.id }
            val id = animation ?: archive.defaultAnimationId ?: throw ToolError("'${pathOf(file)}' has no animations.")
            val text = archive.animationText(id) ?: throw ToolError("No animation '$id' in '${pathOf(file)}'. Animations: ${ids.joinToString()}.")
            return id to (MiniJson.parseOrNull(text) ?: throw ToolError("Animation '$id' in '${pathOf(file)}' is not valid JSON."))
        }
        if (animation != null) throw ToolError("'animation' only applies to .lottie files; '${pathOf(file)}' is a single animation.")
        val text = text(file)
        val tree = try {
            MiniJson.parse(text)
        } catch (e: MiniJson.ParseException) {
            throw ToolError("'${pathOf(file)}' is not valid JSON: ${e.message}")
        }
        return null to tree
    }

    internal fun info(file: VirtualFile, tree: Any?): LottieInfo =
        LottieModel.read(tree)?.takeIf { tree.obj()?.containsKey("layers") == true }
            ?: throw ToolError("'${pathOf(file)}' is not a Lottie animation (a JSON object with 'layers', 'fr', 'ip', 'op', 'w' and 'h').")

    // ---- read tools ------------------------------------------------------------------------------------------------------

    /** Size, timing, layers (up to [limit]), markers, slots and the color palette of an animation. */
    fun describeLottie(file: VirtualFile, animation: String? = null, limit: Int = DEFAULT_LIMIT): String {
        val (id, tree) = animationRoot(file, animation)
        val info = info(file, tree)
        val max = limit.coerceAtLeast(0)
        val byInd = info.layers.filter { it.ind != null }.associateBy { it.ind }
        val result = linkedMapOf<String, Any?>("path" to pathOf(file))
        if (id != null) result["animation"] = id
        result += linkedMapOf(
            "name" to info.name,
            "lottieVersion" to info.version,
            "width" to info.width,
            "height" to info.height,
            "frameRate" to info.frameRate,
            "inPoint" to info.inPoint,
            "outPoint" to info.outPoint,
            "frames" to info.frames,
            "durationSeconds" to round(info.durationSeconds),
            "markers" to info.markers.map(::marker),
            "layerCount" to info.layers.size,
            "layers" to info.layers.take(max).map { layer ->
                linkedMapOf<String, Any?>(
                    "index" to layer.position,
                    "ind" to layer.ind,
                    "name" to layer.label,
                    "type" to layer.typeName,
                    "inPoint" to layer.inPoint,
                    "outPoint" to layer.outPoint,
                    "parent" to layer.parent?.let { p -> byInd[p]?.label ?: p },
                ).apply { if (layer.hidden) put("hidden", true) }.filterValues { it != null }
            },
        )
        if (info.layers.size > max) result["layersTruncated"] = "showing $max of ${info.layers.size}; pass a larger 'limit' for more"
        result["slots"] = info.slotIds
        result["assetCount"] = info.assetCount
        val palette = PaletteScanner.palette(PaletteScanner.scan(tree))
        result["palette"] = palette.take(max).map { entry ->
            linkedMapOf("color" to entry.color.hex, "uses" to entry.count, "kinds" to entry.kinds.map { it.label })
        }
        if (palette.size > max) result["paletteTruncated"] = "showing $max of ${palette.size}"
        return json(result)
    }

    /** The manifest of a `.lottie` file: version, animations, themes, state machines and the files of the archive. */
    fun describeDotLottie(file: VirtualFile): String {
        val archive = archive(file)
        val m = archive.manifest
        val result = linkedMapOf<String, Any?>(
            "path" to pathOf(file),
            "dotLottieVersion" to archive.specVersion,
            "generator" to m.generator,
            "author" to m.author,
            "initialAnimation" to archive.defaultAnimationId,
            "initialStateMachine" to m.initialStateMachine,
            "animations" to m.animations.map { a ->
                val info = archive.animationText(a.id)?.let(LottieModel::read)
                linkedMapOf<String, Any?>(
                    "id" to a.id,
                    "initialTheme" to a.initialTheme,
                    "themes" to a.themes.takeIf { it.isNotEmpty() },
                    "background" to a.background,
                    "width" to info?.width,
                    "height" to info?.height,
                    "frameRate" to info?.frameRate,
                    "frames" to info?.frames,
                    "markers" to info?.markers?.map { it.name },
                ).filterValues { it != null }
            },
            "themes" to m.themes.map { linkedMapOf("id" to it.id, "name" to it.name).filterValues { v -> v != null } },
            "stateMachines" to m.stateMachines.map { linkedMapOf("id" to it.id, "name" to it.name).filterValues { v -> v != null } },
            "files" to archive.entries.map { linkedMapOf("path" to it.path, "size" to it.size, "kind" to it.kind) },
        ).filterTo(LinkedHashMap<String, Any?>()) { it.value != null }
        if (archive.specVersion < 2) result["note"] = "dotLottie 1.0: themes and state machines need 2.0 (dotlottie_convert_to_v2)"
        return json(result)
    }

    /** States, transitions, inputs and custom events of a state machine, with the code that drives it from [platform]. */
    fun describeStateMachine(file: VirtualFile, stateMachine: String? = null, platform: String? = null): String {
        val archive = archive(file)
        val id = stateMachineId(file, archive, stateMachine)
        val machine = machine(file, archive, id)
        val player = codePlatform(platform)
        val context = SnippetContext(pathOf(file), true, stateMachineId = id, inputs = machine.inputs, customEvents = machine.customEvents)
        val snippets = SnippetGenerator.generate(player, context)
        val code = linkedMapOf<String, Any?>("platform" to player.packageName)
        snippets.first(SnippetKind.STATE_MACHINE)?.let { code["start"] = it.code }
        code["inputs"] = machine.inputs.associate { input -> input.name to snippets.first(SnippetKind.INPUT, input.name)?.code }
        if (machine.customEvents.isNotEmpty()) snippets.first(SnippetKind.CUSTOM_EVENTS)?.let { code["customEvents"] = it.code }
        val result = linkedMapOf<String, Any?>(
            "path" to pathOf(file),
            "stateMachine" to id,
            "initial" to machine.initial,
            "states" to machine.root["states"].arr().orEmpty().mapNotNull { it.obj() }.map { state ->
                linkedMapOf<String, Any?>(
                    "name" to state["name"].str(),
                    "type" to state["type"].str(),
                    "animation" to state["animation"].str(),
                    "segment" to state["segment"].str(),
                    "transitions" to state["transitions"].arr().orEmpty().mapNotNull { it.obj() }.map { t ->
                        linkedMapOf<String, Any?>("to" to t["toState"].str(), "type" to t["type"].str(), "guards" to t["guards"].arr()?.takeIf { it.isNotEmpty() })
                            .filterValues { it != null }
                    },
                ).filterValues { it != null }
            },
            "inputs" to machine.inputs.map(::input),
            "interactions" to machine.root["interactions"].arr().orEmpty(),
            "customEvents" to machine.customEvents,
            "problems" to problems(archive, machine),
            "code" to code,
        )
        return json(result)
    }

    /** The references of state machines that point nowhere (inputs, states, animations, markers, themes, layers). */
    fun checkStateMachines(file: VirtualFile, stateMachine: String? = null): String {
        val archive = archive(file)
        val ids = if (stateMachine != null) listOf(stateMachineId(file, archive, stateMachine)) else stateMachineIds(archive)
        val results = ids.associateWith { id -> problems(archive, machine(file, archive, id)) }
        return json(
            linkedMapOf(
                "path" to pathOf(file),
                "ok" to results.values.all { it.isEmpty() },
                "stateMachines" to results,
            ),
        )
    }

    // ---- edit tools ------------------------------------------------------------------------------------------------------

    /** Adds marker [name] at frame [start] (composition frames) lasting [duration] frames. */
    fun addMarker(file: VirtualFile, name: String, start: Double, duration: Double = 0.0, animation: String? = null): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw ToolError("The marker name is empty.")
        if (start < 0 || duration < 0 || !start.isFinite() || !duration.isFinite()) throw ToolError("'start' and 'duration' are frames and can't be negative.")
        val (id, tree) = animationRoot(file, animation)
        val info = info(file, tree)
        if (info.markers.any { it.name == trimmed || it.comment == trimmed }) {
            throw ToolError("'${pathOf(file)}' already has a marker '$trimmed'. Markers: ${info.markers.joinToString { it.name }}.")
        }
        markerEditor(file, id).add(trimmed, start, duration)
        return markersResult(file, id, "added", trimmed, warning = rangeWarning(info, start, duration))
    }

    /** Removes the marker named [name]. */
    fun removeMarker(file: VirtualFile, name: String, animation: String? = null): String {
        val (id, tree) = animationRoot(file, animation)
        val info = info(file, tree)
        val marker = info.markers.firstOrNull { it.name == name } ?: info.markers.firstOrNull { it.comment == name }
            ?: throw ToolError("No marker '$name' in '${pathOf(file)}'. Markers: ${info.markers.joinToString { it.name }.ifEmpty { "none" }}.")
        markerEditor(file, id).delete(marker.index)
        return markersResult(file, id, "removed", marker.name)
    }

    private fun markerEditor(file: VirtualFile, animation: String?): MarkerEditor = if (LottieFiles.isDotLottie(file)) {
        DotLottieMarkerEditor(project, file) { animation }
    } else {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: throw ToolError("'${pathOf(file)}' can't be edited as text.")
        if (!document.isWritable) throw ToolError("'${pathOf(file)}' is read-only.")
        LottieMarkerEditor(project, document)
    }

    /** The markers after an edit; JSON files are saved, so agents that read the file from disk see the change. */
    private fun markersResult(file: VirtualFile, animation: String?, action: String, name: String, warning: String? = null): String {
        if (!LottieFiles.isDotLottie(file)) FileDocumentManager.getInstance().getDocument(file)?.let(FileDocumentManager.getInstance()::saveDocument)
        val (_, tree) = animationRoot(file, animation)
        val markers = LottieModel.read(tree)?.markers.orEmpty()
        val present = markers.any { it.name == name }
        if (present != (action == "added")) throw ToolError("The marker couldn't be $action: '${pathOf(file)}' isn't a valid Lottie JSON object.")
        val result = linkedMapOf<String, Any?>("path" to pathOf(file), action to name)
        if (animation != null) result["animation"] = animation
        result["markers"] = markers.map(::marker)
        if (warning != null) result["warning"] = warning
        result["undo"] = "one undoable command (${if (action == "added") "Add" else "Delete"} marker)"
        return json(result)
    }

    private fun rangeWarning(info: LottieInfo, start: Double, duration: Double): String? =
        if (start < info.inPoint || start + duration > info.outPoint) {
            "frames ${num(start)}–${num(start + duration)} are outside the animation's ${num(info.inPoint)}–${num(info.outPoint)}"
        } else null

    /** Adds input [name] of [type] (Boolean, Numeric, String or Event) with initial [value] to a state machine. */
    fun addInput(file: VirtualFile, name: String, type: String, value: String? = null, stateMachine: String? = null): String {
        val inputType = InputType.entries.firstOrNull { it.json.equals(type.trim(), ignoreCase = true) }
            ?: throw ToolError("Unknown input type '$type'. Types: ${InputType.entries.joinToString { it.json }}.")
        if (!StateMachine.isValidInputName(name)) throw ToolError("'$name' is not a valid input name: not empty, no '$', no leading '@', no surrounding spaces.")
        if (value != null && inputType == InputType.EVENT) throw ToolError("Event inputs have no value; leave 'value' out.")
        if (value != null && inputType == InputType.BOOLEAN && value.trim().lowercase() !in setOf("true", "false")) throw ToolError("A Boolean value is true or false, not '$value'.")
        if (value != null && inputType == InputType.NUMERIC && value.trim().toDoubleOrNull() == null) throw ToolError("A Numeric value is a number, not '$value'.")
        return editMachine(file, stateMachine, "Add input") { machine ->
            if (machine.input(name) != null) throw ToolError("The state machine already has an input '$name'.")
            machine.addInput(StateMachineInput(name, inputType, StateMachine.defaultValue(inputType, value)))
        }
    }

    /** Renames input [from] to [to] and every reference to it (guards, actions). */
    fun renameInput(file: VirtualFile, from: String, to: String, stateMachine: String? = null): String {
        if (!StateMachine.isValidInputName(to)) throw ToolError("'$to' is not a valid input name: not empty, no '$', no leading '@', no surrounding spaces.")
        return editMachine(file, stateMachine, "Rename input") { machine ->
            requireInput(machine, from)
            if (from == to) throw ToolError("The input is already named '$to'.")
            if (machine.input(to) != null) throw ToolError("The state machine already has an input '$to'.")
            machine.renameInput(from, to)
        }
    }

    /** Deletes input [name]; references to it remain and are listed as problems. */
    fun deleteInput(file: VirtualFile, name: String, stateMachine: String? = null): String =
        editMachine(file, stateMachine, "Delete input") { machine ->
            requireInput(machine, name)
            machine.deleteInput(name)
        }

    private fun requireInput(machine: StateMachine, name: String) {
        if (machine.input(name) == null) throw ToolError("No input '$name'. Inputs: ${machine.inputs.joinToString { it.name }.ifEmpty { "none" }}.")
    }

    private fun editMachine(file: VirtualFile, stateMachine: String?, command: String, change: (StateMachine) -> StateMachine): String {
        requireDotLottie(file)
        // edits of the state machine still waiting in its JSON editor go first
        StateMachineDocuments.getInstance(project).flush(file)
        val archive = archive(file)
        val id = stateMachineId(file, archive, stateMachine)
        val machine = archiveMachine(file, archive, id)
        val changed = change(machine)
        DotLottieFiles.replaceEntries(project, file, mapOf(StateMachineVirtualFile.entryPath(id) to changed.toJson().toByteArray(Charsets.UTF_8)), command)
        val after = archive(file)
        val saved = archiveMachine(file, after, id)
        return json(
            linkedMapOf(
                "path" to pathOf(file),
                "stateMachine" to id,
                "inputs" to saved.inputs.map(::input),
                "problems" to problems(after, saved),
                "undo" to "one undoable command ($command)",
            ),
        )
    }

    /** Converts a dotLottie 1.0 file to 2.0 in place. */
    fun convertToV2(file: VirtualFile): String {
        requireDotLottie(file)
        val result = try {
            DotLottieMigration.toV2(file.contentsToByteArray(), LottieNotifications.GENERATOR)
        } catch (e: Exception) {
            throw ToolError("'${pathOf(file)}' can't be converted: ${e.message ?: e.javaClass.simpleName}")
        } ?: return json(linkedMapOf("path" to pathOf(file), "converted" to false, "reason" to "already dotLottie 2.0"))
        DotLottieFiles.rewrite(project, file, "Convert to dotLottie 2.0") { result.bytes }
        return json(
            linkedMapOf(
                "path" to pathOf(file),
                "converted" to true,
                "dropped" to result.dropped,
                "undo" to "one undoable command (Convert to dotLottie 2.0)",
            ),
        )
    }

    /** Writes animation [animation] (default: the initial one) of a `.lottie` file as a Lottie JSON file next to it. */
    fun extract(file: VirtualFile, animation: String? = null): String {
        val archive = archive(file)
        val ids = archive.manifest.animations.map { it.id }
        val id = animation ?: archive.defaultAnimationId ?: throw ToolError("'${pathOf(file)}' has no animations.")
        val json = archive.extractAnimation(id) ?: throw ToolError("No animation '$id' in '${pathOf(file)}'. Animations: ${ids.joinToString()}.")
        val base = if (ids.size == 1) file.nameWithoutExtension else "${file.nameWithoutExtension}-${DotLottieWriter.sanitizeId(id)}"
        val created = create(file, base, "json", "Extract Lottie JSON") { VfsUtilCore.saveText(it, json) }
        return json(linkedMapOf("created" to pathOf(created), "animation" to id))
    }

    /** Packs a Lottie JSON file (unsaved changes included) and the images it references into a `.lottie` file next to it. */
    fun pack(file: VirtualFile): String {
        if (LottieFiles.isDotLottie(file)) throw ToolError("'${pathOf(file)}' is already a dotLottie file; pack takes a Lottie JSON file.")
        val (_, tree) = animationRoot(file, null)
        info(file, tree)
        val base = file.name.removeSuffix(".json").removeSuffix(".JSON").removeSuffix(".lottie")
        val folder = file.parent?.let { runCatching { it.toNioPath().toFile() }.getOrNull() }
        val bytes = DotLottieWriter.pack(DotLottieWriter.sanitizeId(base), tree.obj()!!, LottieNotifications.GENERATOR) { u, p ->
            if (folder == null || u.startsWith("http:") || u.startsWith("https:")) return@pack null
            val candidate = File(File(folder, u), p).canonicalFile
            // only files inside the animation's folder tree
            if (candidate.path.startsWith(folder.canonicalPath) && candidate.isFile) candidate.readBytes() else null
        }
        val created = create(file, base, "lottie", "Pack as dotLottie") { it.setBinaryContent(bytes) }
        return json(linkedMapOf("created" to pathOf(created), "size" to bytes.size))
    }

    /** Creates a file named [base].[extension] (or `base-2`, ...) next to [file], as one undoable command. */
    internal fun create(file: VirtualFile, base: String, extension: String, command: String, write: (VirtualFile) -> Unit): VirtualFile {
        val directory = file.parent ?: throw ToolError("'${pathOf(file)}' has no folder to write into.")
        return WriteCommandAction.writeCommandAction(project).withName(command).compute<VirtualFile, Exception> {
            directory.createChildData(this, LottieFiles.uniqueName(directory, base, extension)).also(write)
        }
    }

    // ---- rendering -------------------------------------------------------------------------------------------------------

    /** A frame to render: what the renderer gets, and what the result reports. */
    class RenderJob(
        val request: FrameRenderRequest,
        val file: VirtualFile,
        val animation: String?,
        val frame: Double,
        val marker: String?,
        val output: String?,
    )

    /**
     * Checks a render call and collects what the renderer needs (read access): the frame [frame] (composition frames) or the start
     * of marker [marker] (default: the in point), the longest side [size], [background] and dotLottie [theme].
     */
    fun prepareRender(
        file: VirtualFile,
        frame: Double? = null,
        marker: String? = null,
        size: Int = DEFAULT_SIZE,
        background: String? = null,
        theme: String? = null,
        animation: String? = null,
        output: String? = null,
    ): RenderJob {
        if (frame != null && marker != null) throw ToolError("Pass either 'frame' or 'marker', not both.")
        if (size !in 16..MAX_SIZE) throw ToolError("'size' is the longest side in pixels, 16 to $MAX_SIZE.")
        val color = background?.trim()?.takeUnless { it.isEmpty() || it.equals("transparent", ignoreCase = true) }?.let { b ->
            val hex = b.removePrefix("#")
            if (!hex.matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) throw ToolError("'background' is a color like #ffffff or #00000080, or 'transparent'.")
            "#" + hex.lowercase()
        }
        if (output != null && !output.endsWith(".png", ignoreCase = true)) throw ToolError("'output' is a .png path inside the project.")
        val (id, tree) = animationRoot(file, animation)
        val info = info(file, tree)
        if (theme != null) {
            if (!LottieFiles.isDotLottie(file)) throw ToolError("'theme' only applies to .lottie files.")
            val themes = archive(file).stateMachineContext().themeIds
            if (theme !in themes) throw ToolError("No theme '$theme' in '${pathOf(file)}'. Themes: ${themes.joinToString().ifEmpty { "none" }}.")
        }
        val at = when {
            marker != null -> (info.markers.firstOrNull { it.name == marker } ?: info.markers.firstOrNull { it.comment == marker }
                ?: throw ToolError("No marker '$marker' in '${pathOf(file)}'. Markers: ${info.markers.joinToString { it.name }.ifEmpty { "none" }}.")).start
            frame != null -> frame
            else -> info.inPoint
        }
        if (!at.isFinite() || at < info.inPoint || at > info.outPoint) {
            throw ToolError("Frame ${num(at)} is outside the animation's ${num(info.inPoint)}–${num(info.outPoint)}.")
        }
        val scale = size / maxOf(info.width, info.height, 1.0)
        val data = if (LottieFiles.isDotLottie(file)) file.contentsToByteArray() else text(file).toByteArray(Charsets.UTF_8)
        val request = FrameRenderRequest(
            data = data,
            json = !LottieFiles.isDotLottie(file),
            animationId = id,
            frames = listOf(at - info.inPoint),
            width = (info.width * scale).toInt().coerceAtLeast(1),
            height = (info.height * scale).toInt().coerceAtLeast(1),
            background = color,
            themeId = theme,
        )
        return RenderJob(request, file, id, at, marker, output?.let(::relativize))
    }

    /**
     * Saves a rendered frame: at the job's output path inside the project (a new file, as one undoable command; on the EDT), or in
     * the system's temporary folder.
     */
    fun saveRender(job: RenderJob, png: ByteArray): String {
        val base = "${job.file.nameWithoutExtension}${job.animation?.let { "-${DotLottieWriter.sanitizeId(it)}" } ?: ""}-${num(job.frame)}" +
            (job.request.themeId?.let { "-$it" } ?: "") + (job.request.background?.let { "-${it.removePrefix("#")}" } ?: "")
        val saved = if (job.output != null) {
            val dir = job.output.substringBeforeLast('/', "")
            val directory = (if (dir.isEmpty()) root else root.findFileByRelativePath(dir))?.takeIf { it.isDirectory }
                ?: throw ToolError("No folder '$dir' in the project for the image.")
            val name = job.output.substringAfterLast('/')
            WriteCommandAction.writeCommandAction(project).withName("Render Lottie frame").compute<VirtualFile, Exception> {
                directory.createChildData(this, LottieFiles.uniqueName(directory, name.substringBeforeLast('.'), "png")).also { it.setBinaryContent(png) }
            }.let(::pathOf)
        } else {
            val folder = File(System.getProperty("java.io.tmpdir"), "lottie-render").apply { mkdirs() }
            File(folder, "$base.png").apply { writeBytes(png) }.absolutePath
        }
        val result = linkedMapOf<String, Any?>("image" to saved, "path" to pathOf(job.file))
        if (job.animation != null) result["animation"] = job.animation
        result["frame"] = job.frame
        if (job.marker != null) result["marker"] = job.marker
        if (job.request.themeId != null) result["theme"] = job.request.themeId
        result["width"] = job.request.width
        result["height"] = job.request.height
        return json(result)
    }

    // ---- state machines --------------------------------------------------------------------------------------------------

    private fun stateMachineIds(archive: DotLottieArchive): List<String> =
        (archive.manifest.stateMachines.map { it.id } + archive.entries.map { it.path }.filter { it.startsWith("s/") && it.endsWith(".json") }
            .map { it.removePrefix("s/").removeSuffix(".json") }).distinct()

    internal fun stateMachineId(file: VirtualFile, archive: DotLottieArchive, requested: String?): String {
        val ids = stateMachineIds(archive)
        if (requested != null) {
            if (requested in ids) return requested
            throw ToolError("No state machine '$requested' in '${pathOf(file)}'. State machines: ${ids.joinToString().ifEmpty { "none" }}.")
        }
        return when (ids.size) {
            0 -> throw ToolError("'${pathOf(file)}' has no state machines.")
            1 -> ids.single()
            else -> throw ToolError("'${pathOf(file)}' has several state machines; pass 'stateMachine': ${ids.joinToString()}.")
        }
    }

    /** The state machine as its open JSON editor has it (unsaved edits included), else as the archive has it. */
    internal fun machine(file: VirtualFile, archive: DotLottieArchive, id: String): StateMachine =
        StateMachineDocuments.getInstance(project).document(file, id)?.text?.let(StateMachine::parse) ?: archiveMachine(file, archive, id)

    private fun archiveMachine(file: VirtualFile, archive: DotLottieArchive, id: String): StateMachine =
        archive.stateMachineText(id)?.let(StateMachine::parse) ?: throw ToolError("State machine '$id' in '${pathOf(file)}' is not valid JSON.")

    private fun problems(archive: DotLottieArchive, machine: StateMachine): List<Map<String, String>> =
        StateMachineChecker.check(machine, archive.stateMachineContext()).map { linkedMapOf("at" to jsonPath(it.path), "message" to it.message) }

    internal fun codePlatform(name: String?): CodePlatform {
        if (name == null) {
            val current = cz.xprees.lottie.snippets.CodePlatforms.getInstance(project).current
            return if (current.stateMachines) current else CodePlatform.DOTLOTTIE_WEB
        }
        val wanted = name.trim().lowercase().replace('_', '-')
        return CodePlatform.entries.firstOrNull { it.name.lowercase().replace('_', '-') == wanted || it.packageName.lowercase() == wanted }
            ?.takeIf { it.stateMachines }
            ?: throw ToolError("Unknown or state-machine-less platform '$name'. Platforms: ${PLATFORMS.joinToString()}.")
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val DEFAULT_SIZE = 512
        const val MAX_SIZE = 2048

        /** The platforms whose code [describeStateMachine] can write, as the tool takes them. */
        val PLATFORMS: List<String> = CodePlatform.entries.filter { it.stateMachines }.map { it.name.lowercase().replace('_', '-') }

        internal fun json(value: Map<String, Any?>): String = MiniJson.write(value, pretty = false)

        internal fun marker(m: cz.xprees.lottie.model.LottieMarker) = linkedMapOf<String, Any?>("name" to m.name, "start" to m.start, "duration" to m.duration)
            .apply { if (m.comment != m.name) put("comment", m.comment) }

        private fun input(i: StateMachineInput) = linkedMapOf<String, Any?>("name" to i.name, "type" to i.type.json).apply { if (i.type.hasValue) put("value", i.value) }

        /** `states[1].transitions[0].toState` for a JSON path. */
        fun jsonPath(path: JsonPath): String = buildString {
            for (part in path) if (part is Int) append('[').append(part).append(']') else { if (isNotEmpty()) append('.'); append(part) }
        }

        private fun round(d: Double) = Math.round(d * 1000) / 1000.0

        internal fun num(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else d.toString()
    }
}
