package cz.xprees.lottie.snippets

import cz.xprees.lottie.statemachine.InputType

/**
 * A prompt for an AI coding agent that integrates the open animation into the user's project: the file, the player to use, the
 * markers with their frames, the state machine with its inputs and custom events, and the generated setup code as a reference.
 * [platformName] is the player's display name.
 */
object AgentPrompt {

    fun build(platform: CodePlatform, platformName: String, typeScript: Boolean, c: SnippetContext): String = buildString {
        val language = when {
            !platform.typeScript -> ""
            typeScript -> ", TypeScript"
            else -> ", JavaScript"
        }
        append("Integrate the Lottie animation `${c.path}` (${if (c.dotLottie) "a dotLottie file" else "a Lottie JSON file"}) into this project.\n\n")
        append("Player: $platformName (`${platform.packageName}`)$language. Add the dependency if the project doesn't have it yet, ")
        append("and follow the structure and conventions this project already uses.\n")
        if (c.dotLottie && !platform.dotLottieFiles) {
            append("This player plays Lottie JSON only: use the animation exported as JSON, or switch to a dotLottie player.\n")
        }

        if (c.markers.isNotEmpty()) {
            append("\nMarkers (named segments, in frames):\n")
            for (m in c.markers) {
                val end = m.start + maxOf(m.duration, 1.0)
                append("- `${m.name}`: frames ${SnippetGenerator.number(m.start)}–${SnippetGenerator.number(end)}\n")
            }
        }

        val machine = c.stateMachineId?.takeIf { c.dotLottie }
        if (machine != null) {
            append("\nState machine `$machine`")
            append(if (platform.stateMachines) " (load it by this id):\n" else " (this player has no state machines; a dotLottie player is needed to use it):\n")
            if (c.inputs.isNotEmpty()) {
                append("- inputs: ")
                append(c.inputs.joinToString(", ") { input ->
                    when (input.type) {
                        InputType.EVENT -> "`${input.name}` (event, fired)"
                        InputType.STRING -> "`${input.name}` (string, now \"${c.value(input) ?: ""}\")"
                        InputType.BOOLEAN -> "`${input.name}` (boolean, now ${c.value(input) == true})"
                        InputType.NUMERIC -> "`${input.name}` (number, now ${c.value(input) ?: 0})"
                    }
                })
                append("\n")
            }
            if (c.customEvents.isNotEmpty()) append("- custom events it sends: ${c.customEvents.joinToString(", ") { "`$it`" }}\n")
        }

        append("\nPlease:\n")
        var step = 1
        append("${step++}. Add a component (or this platform's equivalent) that loads the file and gives typed access to the player instance.\n")
        if (c.markers.isNotEmpty()) {
            append("${step++}. Add a way to play each marker by name, and keep track of the marker the playhead is in, so the app can react when playback enters one.\n")
        }
        if (machine != null && platform.stateMachines) {
            append("${step++}. Start the state machine, add setters for its inputs")
            append(if (c.customEvents.isNotEmpty()) " and handle its custom events.\n" else ".\n")
        }
        append("$step. If it isn't clear where the animation belongs in the app, ask me before wiring it into a screen.\n")

        val setup = SnippetGenerator.generate(platform, c, typeScript).first(SnippetKind.SETUP)
        if (setup != null) {
            append("\nReference code for this player, generated from the file (adapt it, don't paste it blindly):\n\n")
            append("```${fence(setup.extension)}\n${setup.code}\n```\n")
        }
    }

    /** The language name of a code fence for a snippet's file extension. */
    private fun fence(extension: String) = when (extension) {
        "kt" -> "kotlin"
        "ts" -> "typescript"
        "js" -> "javascript"
        else -> extension
    }
}
