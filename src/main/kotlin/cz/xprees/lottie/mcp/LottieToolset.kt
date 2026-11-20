package cz.xprees.lottie.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.lottie.preview.FrameRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

private const val PATH = "Path of the file, relative to the project directory"
private const val ANIMATION = "For .lottie files: id of the animation (default: the initial one)"
private const val STATE_MACHINE = "Id of the state machine; may be left out when the file has only one"

/**
 * The plugin's tools for AI agents connected to the IDE's MCP server (*Settings › Tools › MCP Server*): read Lottie and dotLottie
 * files and edit them with the plugin's model. Registered only when the MCP Server plugin is enabled (`lottie-mcp.xml`); the work is
 * done by [LottieMcpTools].
 */
class LottieToolset : McpToolset {

    @McpTool(name = "lottie_describe", title = "Describe Lottie animation")
    @McpDescription(
        """Describes a Lottie animation (a Lottie JSON file, or an animation of a .lottie file): size, frame rate, in/out points,
        frames and duration, markers (name, start frame, duration), layers (index, name, type, in/out points, parent), slot ids and
        the color palette with the number of uses of each color. Reads unsaved editor changes.""",
    )
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun lottie_describe(
        @McpDescription(PATH) path: String,
        @McpDescription(ANIMATION) animation: String? = null,
        @McpDescription("Most layers and palette colors to list (default 100)") limit: Int = LottieMcpTools.DEFAULT_LIMIT,
    ): String = read(path, "Describing $path") { describeLottie(it, animation, limit) }

    @McpTool(name = "dotlottie_describe", title = "Describe dotLottie file")
    @McpDescription(
        """Describes a dotLottie (.lottie) file: its version (1 or 2), generator, the animations with their size, frames and markers,
        the themes, the state machines, and every file in the archive with its size.""",
    )
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun dotlottie_describe(@McpDescription(PATH) path: String): String =
        read(path, "Describing $path") { describeDotLottie(it) }

    @McpTool(name = "dotlottie_state_machine", title = "Describe dotLottie state machine")
    @McpDescription(
        """Describes a state machine of a .lottie file: the initial state, the states with their transitions and guards, the inputs
        with their types and initial values, interactions, the custom events it sends, broken references, and the code that starts
        it, sets each input and listens to its custom events for a dotLottie player.""",
    )
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun dotlottie_state_machine(
        @McpDescription(PATH) path: String,
        @McpDescription(STATE_MACHINE) stateMachine: String? = null,
        @McpDescription(
            "Player to write the code for: dotlottie-web, dotlottie-react, dotlottie-vue, dotlottie-svelte, dotlottie-solid, " +
                "dotlottie-web-component, dotlottie-react-native, dotlottie-android-compose, dotlottie-android-view, dotlottie-ios, " +
                "dotlottie-flutter (default: the player picked or detected for the project)",
        ) platform: String? = null,
    ): String = read(path, "Reading state machine of $path") { describeStateMachine(it, stateMachine, platform) }

    @McpTool(name = "dotlottie_check", title = "Check dotLottie state machines")
    @McpDescription(
        """Checks the state machines of a .lottie file for references that point nowhere: unknown or mistyped inputs, states,
        animations, markers, themes and layers. Returns each problem with its JSON location.""",
    )
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun dotlottie_check(
        @McpDescription(PATH) path: String,
        @McpDescription("Id of the state machine to check (default: all)") stateMachine: String? = null,
    ): String = read(path, "Checking $path") { checkStateMachines(it, stateMachine) }

    @McpTool(name = "lottie_add_marker", title = "Add Lottie marker")
    @McpDescription(
        """Adds a named marker (a segment players can play by name) to a Lottie JSON file or an animation of a .lottie file. One
        undoable change; JSON files are saved. Fails when a marker of that name exists.""",
    )
    @McpToolHints(destructiveHint = McpToolHintValue.TRUE, idempotentHint = McpToolHintValue.FALSE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun lottie_add_marker(
        @McpDescription(PATH) path: String,
        @McpDescription("Name of the marker") name: String,
        @McpDescription("Start frame (composition frames, like the animation's in/out points)") start: Double,
        @McpDescription("Length in frames (default 0: a single-frame marker)") duration: Double = 0.0,
        @McpDescription(ANIMATION) animation: String? = null,
    ): String = write(path, "Adding marker '$name' to $path") { addMarker(it, name, start, duration, animation) }

    @McpTool(name = "lottie_remove_marker", title = "Remove Lottie marker")
    @McpDescription("Removes the marker with the given name from a Lottie JSON file or an animation of a .lottie file. One undoable change; JSON files are saved.")
    @McpToolHints(destructiveHint = McpToolHintValue.TRUE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun lottie_remove_marker(
        @McpDescription(PATH) path: String,
        @McpDescription("Name of the marker") name: String,
        @McpDescription(ANIMATION) animation: String? = null,
    ): String = write(path, "Removing marker '$name' from $path") { removeMarker(it, name, animation) }

    @McpTool(name = "dotlottie_add_input", title = "Add state machine input")
    @McpDescription("Adds an input to a state machine of a .lottie file. One undoable change. Returns the inputs and any broken references.")
    @McpToolHints(destructiveHint = McpToolHintValue.TRUE, idempotentHint = McpToolHintValue.FALSE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun dotlottie_add_input(
        @McpDescription(PATH) path: String,
        @McpDescription("Name of the new input") name: String,
        @McpDescription("Type: Boolean, Numeric, String or Event") type: String,
        @McpDescription("Initial value (true/false, a number or text; default false, 0 or empty; none for Event)") value: String? = null,
        @McpDescription(STATE_MACHINE) stateMachine: String? = null,
    ): String = write(path, "Adding input '$name' to $path") { addInput(it, name, type, value, stateMachine) }

    @McpTool(name = "dotlottie_rename_input", title = "Rename state machine input")
    @McpDescription("Renames an input of a state machine of a .lottie file and every reference to it (guards, actions). One undoable change.")
    @McpToolHints(destructiveHint = McpToolHintValue.TRUE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun dotlottie_rename_input(
        @McpDescription(PATH) path: String,
        @McpDescription("Current name of the input") from: String,
        @McpDescription("New name") to: String,
        @McpDescription(STATE_MACHINE) stateMachine: String? = null,
    ): String = write(path, "Renaming input '$from' in $path") { renameInput(it, from, to, stateMachine) }

    @McpTool(name = "dotlottie_delete_input", title = "Delete state machine input")
    @McpDescription("Deletes an input of a state machine of a .lottie file. One undoable change. References to it stay and are returned as problems.")
    @McpToolHints(destructiveHint = McpToolHintValue.TRUE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun dotlottie_delete_input(
        @McpDescription(PATH) path: String,
        @McpDescription("Name of the input") name: String,
        @McpDescription(STATE_MACHINE) stateMachine: String? = null,
    ): String = write(path, "Deleting input '$name' from $path") { deleteInput(it, name, stateMachine) }

    @McpTool(name = "dotlottie_convert_to_v2", title = "Convert to dotLottie 2.0")
    @McpDescription(
        """Converts a dotLottie 1.0 file to dotLottie 2.0 in place (2.0 folders and manifest), and lists the 1.0 settings 2.0 can't
        hold. Files that are 2.0 already are left as they are. One undoable change.""",
    )
    @McpToolHints(destructiveHint = McpToolHintValue.TRUE, idempotentHint = McpToolHintValue.TRUE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun dotlottie_convert_to_v2(@McpDescription(PATH) path: String): String =
        write(path, "Converting $path to dotLottie 2.0") { convertToV2(it) }

    @McpTool(name = "lottie_extract", title = "Extract Lottie JSON")
    @McpDescription("Writes an animation of a .lottie file, with its images embedded, as a Lottie JSON file next to it (a new file; existing files are kept).")
    @McpToolHints(destructiveHint = McpToolHintValue.FALSE, idempotentHint = McpToolHintValue.FALSE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun lottie_extract(
        @McpDescription(PATH) path: String,
        @McpDescription(ANIMATION) animation: String? = null,
    ): String = write(path, "Extracting Lottie JSON from $path") { extract(it, animation) }

    @McpTool(name = "lottie_pack", title = "Pack as dotLottie")
    @McpDescription("Packs a Lottie JSON file and the images it references from its folder into a dotLottie 2.0 file next to it (a new file; existing files are kept).")
    @McpToolHints(destructiveHint = McpToolHintValue.FALSE, idempotentHint = McpToolHintValue.FALSE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun lottie_pack(@McpDescription(PATH) path: String): String =
        write(path, "Packing $path as dotLottie") { pack(it) }

    @McpTool(name = "lottie_render_frame", title = "Render Lottie frame")
    @McpDescription(
        """Renders one frame of a Lottie JSON file or an animation of a .lottie file to a PNG image with the IDE's player, and
        returns the image's path (open it to look at the frame). Renders the given frame, the start of a marker, or the first
        frame. Needs the IDE's embedded browser (JCEF). Reads unsaved editor changes.""",
    )
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE, openWorldHint = McpToolHintValue.FALSE)
    suspend fun lottie_render_frame(
        @McpDescription(PATH) path: String,
        @McpDescription("Frame to render, in composition frames like the in/out points and markers (default: the in point)") frame: Double? = null,
        @McpDescription("Render the start frame of this marker instead") marker: String? = null,
        @McpDescription("Longest side of the image in pixels, 16 to 2048 (default 512)") size: Int = LottieMcpTools.DEFAULT_SIZE,
        @McpDescription("Background: a color like #ffffff, or transparent (default)") background: String? = null,
        @McpDescription("For .lottie files: id of a theme to apply") theme: String? = null,
        @McpDescription(ANIMATION) animation: String? = null,
        @McpDescription("Save the PNG at this path inside the project (a new file next to existing ones is kept); default: the system's temporary folder") output: String? = null,
    ): String = call(path, "Rendering $path") { tools, file ->
        if (!FrameRenderer.isAvailable()) throw ToolError("Rendering needs the IDE's embedded browser (JCEF), which this IDE runtime doesn't have.")
        val job = readAction { tools.prepareRender(file, frame, marker, size, background, theme, animation, output) }
        val png = withContext(Dispatchers.IO) {
            try {
                FrameRenderer.render(job.request).single()
            } catch (e: Exception) {
                throw ToolError("The frame couldn't be rendered: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        withContext(Dispatchers.EDT) { tools.saveRender(job, png) }
    }

    // ---- plumbing --------------------------------------------------------------------------------------------------------

    private suspend fun read(path: String, activity: String, tool: LottieMcpTools.(VirtualFile) -> String): String =
        call(path, activity) { tools, file -> readAction { tools.tool(file) } }

    private suspend fun write(path: String, activity: String, tool: LottieMcpTools.(VirtualFile) -> String): String =
        call(path, activity) { tools, file -> withContext(Dispatchers.EDT) { tools.tool(file) } }

    private suspend fun call(path: String, activity: String, run: suspend (LottieMcpTools, VirtualFile) -> String): String {
        val context = currentCoroutineContext()
        context.reportToolActivity(activity)
        val project = context.project
        val root = project.guessProjectDir() ?: throw McpExpectedError("The project '${project.name}' has no directory to resolve paths in.")
        val tools = LottieMcpTools(project, root)
        return try {
            run(tools, tools.resolve(path, refresh = true))
        } catch (e: ToolError) {
            throw McpExpectedError(e.message ?: "The tool failed.")
        }
    }
}
