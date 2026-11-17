package cz.xprees.lottie.snippets

import cz.xprees.lottie.statemachine.InputType
import cz.xprees.lottie.statemachine.StateMachineInput

/**
 * A marker as the generated code uses it: the name runtimes look it up by (`cm`, which After Effects exports as JSON like
 * `{"name":"intro"}`), its start frame and duration (`tm`, `dr`), and the [label] shown for it.
 */
data class SnippetMarker(val name: String, val start: Double, val duration: Double, val label: String = name)

/**
 * What the snippets are generated for: the animation file ([path] relative to the project, or just its name), its markers and its
 * first frame ([inPoint], as frame callbacks of most players count from it), and the state machine shown in the preview with its
 * inputs (with [values], the values to put in the setter calls: the live ones while the machine runs) and custom events.
 */
data class SnippetContext(
    val path: String,
    val dotLottie: Boolean,
    val markers: List<SnippetMarker> = emptyList(),
    val inPoint: Double = 0.0,
    val stateMachineId: String? = null,
    val inputs: List<StateMachineInput> = emptyList(),
    val values: Map<String, Any?> = emptyMap(),
    val customEvents: List<String> = emptyList(),
) {
    val fileName: String get() = path.substringAfterLast('/')
    val baseName: String get() = fileName.substringBeforeLast('.')
    fun value(input: StateMachineInput): Any? = if (values.containsKey(input.name)) values[input.name] else input.value
}

enum class SnippetKind {
    /** Adding the player and loading the file. */
    SETUP,

    /** Playing one marker's segment ([Snippet.subject] is the marker's label). */
    PLAY_MARKER,

    /** Loading and starting the state machine ([Snippet.subject] is its id). */
    STATE_MACHINE,

    /** Setting one input, or firing it for events ([Snippet.subject] is the input). */
    INPUT,

    /** Listening to the custom events the state machine sends. */
    CUSTOM_EVENTS,
}

data class Snippet(val kind: SnippetKind, val subject: String?, val code: String)

/** Why some snippets are missing: the runtime plays only Lottie JSON, or has no state machines. */
enum class SnippetNote { JSON_ONLY, NO_STATE_MACHINES }

class Snippets(val snippets: List<Snippet>, val notes: List<SnippetNote>) {
    fun first(kind: SnippetKind, subject: String? = null): Snippet? = snippets.firstOrNull { it.kind == kind && (subject == null || it.subject == subject) }
}

/**
 * Code that uses an animation's markers and state machine from an app, for each [CodePlatform]. The calls follow the runtimes'
 * public APIs (dotlottie-web/react 0.8x/0.2x, dotlottie-android 0.5+, dotlottie-ios, lottie-web 5, lottie-android/-compose 6,
 * lottie-ios 4, lottie-react-native 7, lottie for Flutter 3).
 */
object SnippetGenerator {

    fun generate(platform: CodePlatform, context: SnippetContext): Snippets {
        val notes = ArrayList<SnippetNote>()
        if (context.dotLottie && !platform.dotLottieFiles) notes += SnippetNote.JSON_ONLY
        val machine = context.stateMachineId?.takeIf { context.dotLottie }
        if (machine != null && !platform.stateMachines) notes += SnippetNote.NO_STATE_MACHINES
        val g = generator(platform, context, machine?.takeIf { platform.stateMachines })
        val list = ArrayList<Snippet>()
        list += Snippet(SnippetKind.SETUP, null, g.setup())
        for (marker in context.markers) list += Snippet(SnippetKind.PLAY_MARKER, marker.label, g.playMarker(marker))
        if (machine != null && platform.stateMachines) {
            list += Snippet(SnippetKind.STATE_MACHINE, machine, g.stateMachine(machine))
            for (input in context.inputs) list += Snippet(SnippetKind.INPUT, input.name, g.input(input, context.value(input)))
            list += Snippet(SnippetKind.CUSTOM_EVENTS, null, g.customEvents())
        }
        return Snippets(list, notes)
    }

    /** The code that sets [input] to [value] (fires it for events) on [platform], for the state machine panel's copy buttons. */
    fun input(platform: CodePlatform, input: StateMachineInput, value: Any?): String? =
        if (platform.stateMachines) generator(platform, SnippetContext("animation.lottie", true), "").input(input, value) else null

    fun playMarker(platform: CodePlatform, marker: SnippetMarker): String =
        generator(platform, SnippetContext("animation.json", false, listOf(marker)), null).playMarker(marker)

    fun customEvents(platform: CodePlatform, events: List<String>): String? =
        if (platform.stateMachines) generator(platform, SnippetContext("animation.lottie", true, customEvents = events), "").customEvents() else null

    private fun generator(platform: CodePlatform, c: SnippetContext, machine: String?): Generator = when (platform) {
        CodePlatform.DOTLOTTIE_WEB -> DotLottieWeb(c, machine)
        CodePlatform.DOTLOTTIE_REACT -> DotLottieReact(c, machine)
        CodePlatform.DOTLOTTIE_VUE -> DotLottieVue(c, machine)
        CodePlatform.DOTLOTTIE_SVELTE -> DotLottieSvelte(c, machine)
        CodePlatform.DOTLOTTIE_SOLID -> DotLottieSolid(c, machine)
        CodePlatform.DOTLOTTIE_WEB_COMPONENT -> DotLottieWebComponent(c, machine)
        CodePlatform.DOTLOTTIE_REACT_NATIVE -> DotLottieReactNative(c, machine)
        CodePlatform.DOTLOTTIE_FLUTTER -> DotLottieFlutter(c, machine)
        CodePlatform.LOTTIE_WEB -> LottieWeb(c)
        CodePlatform.REACT_NATIVE -> ReactNative(c)
        CodePlatform.DOTLOTTIE_ANDROID_COMPOSE -> DotLottieCompose(c, machine)
        CodePlatform.DOTLOTTIE_ANDROID_VIEW -> DotLottieView(c, machine)
        CodePlatform.LOTTIE_COMPOSE -> LottieCompose(c)
        CodePlatform.LOTTIE_ANDROID -> LottieAndroid(c)
        CodePlatform.DOTLOTTIE_IOS -> DotLottieIos(c, machine)
        CodePlatform.LOTTIE_IOS -> LottieIos(c)
        CodePlatform.FLUTTER -> Flutter(c)
    }

    // ---- shared ----------------------------------------------------------------------------------------------------------

    private abstract class Generator(val c: SnippetContext) {
        abstract fun setup(): String
        abstract fun playMarker(marker: SnippetMarker): String
        open fun stateMachine(id: String): String = ""
        open fun input(input: StateMachineInput, value: Any?): String = ""
        open fun customEvents(): String = ""

        /** A marker's frames counted from the animation's first frame, the way frame callbacks report them; at least one frame long. */
        fun absolute(m: SnippetMarker) = number(m.start) to number(m.start + maxOf(m.duration, 1.0))

        /** The file's path below a web app's public folder, or its name at the root. */
        fun webPath(): String {
            val p = c.path
            for (root in listOf("public/", "static/", "assets/", "wwwroot/")) {
                val i = p.indexOf(root)
                if (i == 0 || (i > 0 && p[i - 1] == '/')) return "/" + p.substring(i + root.length)
            }
            return "/" + c.fileName
        }
    }

    internal fun number(value: Double): String =
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun numberOf(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        else -> value?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
    }

    /** A single-quoted JavaScript (or Dart, with [dollar]) string literal. */
    internal fun js(text: String, dollar: Boolean = false): String = quoted(text, '\'', dollar)

    /** A double-quoted Kotlin (`$` escaped) or Swift string literal. */
    internal fun kt(text: String): String = quoted(text, '"', true)
    internal fun swift(text: String): String = quoted(text, '"', false)

    private fun quoted(text: String, quote: Char, dollar: Boolean): String = buildString {
        append(quote)
        for (ch in text) {
            when {
                ch == '\\' -> append("\\\\")
                ch == quote -> append('\\').append(ch)
                ch == '$' && dollar -> append("\\$")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' || ch == '\u2028' || ch == '\u2029' -> append(if (quote == '"' && !dollar) "\\u{%x}".format(ch.code) else "\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append(quote)
    }

    /** An HTML attribute value in double quotes. */
    private fun attribute(text: String) = "\"" + text.replace("&", "&amp;").replace("\"", "&quot;") + "\""

    /** A JSX attribute value: a plain string when it needs no escaping, else an expression. */
    private fun jsxString(text: String) = if ('"' in text || '\\' in text || '{' in text) "{${js(text)}}" else "\"$text\""

    private fun indent(text: String, by: String) = text.lines().joinToString("\n") { if (it.isEmpty()) it else by + it }

    // ---- dotLottie web -----------------------------------------------------------------------------------------------------

    private open class DotLottieWeb(c: SnippetContext, val machine: String?) : Generator(c) {
        /** How the calls reach the player instance: `dotLottie.`, `dotLottie?.` (set once mounted) or `dotLottie()?.` (a signal). */
        open val access = "dotLottie."

        override fun setup(): String = buildString {
            append("import { DotLottie } from '@lottiefiles/dotlottie-web';\n\n")
            append("const dotLottie = new DotLottie({\n")
            append("  canvas: document.querySelector('#lottie'),\n")
            append("  src: ${js(webPath())},\n")
            append("  autoplay: true,\n")
            append("  loop: true,\n")
            if (machine != null) append("  stateMachineId: ${js(machine)},\n")
            append("});")
        }

        override fun playMarker(marker: SnippetMarker) = "${access}setMarker(${js(marker.name)});\n${access}play();"

        override fun stateMachine(id: String) = "${access}stateMachineLoad(${js(id)});\n${access}stateMachineStart();"

        override fun input(input: StateMachineInput, value: Any?): String = when (input.type) {
            InputType.BOOLEAN -> "${access}stateMachineSetBooleanInput(${js(input.name)}, ${value == true});"
            InputType.NUMERIC -> "${access}stateMachineSetNumericInput(${js(input.name)}, ${number(numberOf(value))});"
            InputType.STRING -> "${access}stateMachineSetStringInput(${js(input.name)}, ${js(value?.toString() ?: "")});"
            InputType.EVENT -> "${access}stateMachineFireEvent(${js(input.name)});"
        }

        override fun customEvents(): String = buildString {
            append("${access}addEventListener('stateMachineCustomEvent', ({ eventName }) => {\n")
            append(eventSwitch("  "))
            append("});")
        }

        /** The body of a custom event listener: a `switch` over the machine's events, or a log line when it names none. */
        fun eventSwitch(indent: String, variable: String = "eventName"): String = buildString {
            if (c.customEvents.isEmpty()) {
                append("${indent}console.log('State machine event:', $variable);\n")
            } else {
                append("${indent}switch ($variable) {\n")
                for (e in c.customEvents) append("$indent  case ${js(e)}: /* … */ break;\n")
                append("$indent}\n")
            }
        }
    }

    private class DotLottieReact(c: SnippetContext, machine: String?) : DotLottieWeb(c, machine) {
        override val access = "dotLottie?."

        override fun setup(): String = buildString {
            append(if (machine != null) "import { useEffect, useState } from 'react';\n" else "import { useState } from 'react';\n")
            append("import { DotLottieReact } from '@lottiefiles/dotlottie-react';\n\n")
            append("export function ${component(c.baseName)}() {\n")
            append("  // the player instance, for the calls below (null until the canvas is mounted)\n")
            append("  const [dotLottie, setDotLottie] = useState(null);\n")
            append("  return (\n")
            append("    <DotLottieReact\n")
            append("      src=${jsxString(webPath())}\n")
            append("      autoplay\n")
            append("      loop\n")
            if (machine != null) append("      stateMachineId=${jsxString(machine)}\n")
            append("      dotLottieRefCallback={setDotLottie}\n")
            append("    />\n")
            append("  );\n")
            append("}")
        }

        override fun customEvents(): String =
            "useEffect(() => {\n  if (!dotLottie) return;\n  const onEvent = ({ eventName }) => {\n" + eventSwitch("    ") +
                "  };\n  dotLottie.addEventListener('stateMachineCustomEvent', onEvent);\n" +
                "  return () => dotLottie.removeEventListener('stateMachineCustomEvent', onEvent);\n}, [dotLottie]);"
    }

    private class DotLottieVue(c: SnippetContext, machine: String?) : DotLottieWeb(c, machine) {
        override fun setup(): String = buildString {
            append("<script setup>\n")
            append("import { ref } from 'vue';\n")
            append("import { DotLottieVue } from '@lottiefiles/dotlottie-vue';\n\n")
            append("const player = ref(null);\n")
            append("// the player instance, for the calls below (once mounted):\n")
            append("// const dotLottie = player.value.getDotLottieInstance();\n")
            append("</script>\n\n")
            append("<template>\n")
            append("  <DotLottieVue ref=\"player\" src=${attribute(webPath())} autoplay loop")
            if (machine != null) append(" state-machine-id=${attribute(machine)}")
            append(" />\n")
            append("</template>")
        }
    }

    private class DotLottieSvelte(c: SnippetContext, machine: String?) : DotLottieWeb(c, machine) {
        override val access = "dotLottie?."

        override fun setup(): String = buildString {
            append("<script>\n")
            append("  import { DotLottieSvelte } from '@lottiefiles/dotlottie-svelte';\n\n")
            append("  // the player instance, for the calls below (null until the canvas is mounted)\n")
            append("  let dotLottie = null;\n")
            append("</script>\n\n")
            append("<DotLottieSvelte\n")
            append("  src=${attribute(webPath())}\n")
            append("  autoplay\n")
            append("  loop\n")
            if (machine != null) append("  stateMachineId=${attribute(machine)}\n")
            append("  dotLottieRefCallback={(ref) => (dotLottie = ref)}\n")
            append("/>")
        }
    }

    private class DotLottieSolid(c: SnippetContext, machine: String?) : DotLottieWeb(c, machine) {
        override val access = "dotLottie()?."

        override fun setup(): String = buildString {
            append("import { createSignal } from 'solid-js';\n")
            append("import { DotLottieSolid } from '@lottiefiles/dotlottie-solid';\n\n")
            append("export function ${component(c.baseName)}() {\n")
            append("  // the player instance, for the calls below (null until the canvas is mounted)\n")
            append("  const [dotLottie, setDotLottie] = createSignal(null);\n")
            append("  return (\n")
            append("    <DotLottieSolid\n")
            append("      src=${jsxString(webPath())}\n")
            append("      autoplay\n")
            append("      loop\n")
            if (machine != null) append("      stateMachineId=${jsxString(machine)}\n")
            append("      dotLottieRefCallback={setDotLottie}\n")
            append("    />\n")
            append("  );\n")
            append("}")
        }
    }

    private class DotLottieWebComponent(c: SnippetContext, machine: String?) : DotLottieWeb(c, machine) {
        override fun setup(): String = buildString {
            append("<dotlottie-wc id=\"lottie\" src=${attribute(webPath())} autoplay loop")
            if (machine != null) append(" statemachineid=${attribute(machine)}")
            append("></dotlottie-wc>\n\n")
            append("<script type=\"module\">\n")
            append("  import '@lottiefiles/dotlottie-wc';\n\n")
            append("  // the player instance, for the calls below\n")
            append("  const dotLottie = document.querySelector('#lottie').dotLottie;\n")
            append("</script>")
        }
    }

    // ---- lottie-web ----------------------------------------------------------------------------------------------------------

    private class LottieWeb(c: SnippetContext) : Generator(c) {
        override fun setup(): String = buildString {
            append("import lottie from 'lottie-web';\n\n")
            append("const animation = lottie.loadAnimation({\n")
            append("  container: document.querySelector('#lottie'),\n")
            append("  renderer: 'svg',\n")
            append("  loop: true,\n")
            append("  autoplay: true,\n")
            append("  path: ${js(if (c.dotLottie) webPath().substringBeforeLast('.') + ".json" else webPath())},\n")
            append("});")
        }

        override fun playMarker(marker: SnippetMarker) = "animation.goToAndPlay(${js(marker.name)}, true);"
    }

    // ---- React Native --------------------------------------------------------------------------------------------------------

    private class ReactNative(c: SnippetContext) : Generator(c) {
        override fun setup(): String = buildString {
            append("import { useRef } from 'react';\n")
            append("import LottieView from 'lottie-react-native';\n\n")
            append("export function ${component(c.baseName)}() {\n")
            append("  const animation = useRef(null);\n")
            append("  return (\n")
            append("    <LottieView\n")
            append("      ref={animation}\n")
            append("      source={require(${js("./" + c.path)})}\n")
            append("      autoPlay\n")
            append("      loop\n")
            append("      style={{ width: 200, height: 200 }}\n")
            append("    />\n")
            append("  );\n")
            append("}")
        }

        // lottie-react-native plays frame ranges, not marker names: the marker's frames from the file
        override fun playMarker(marker: SnippetMarker): String {
            val (start, end) = absolute(marker)
            return "// ${comment(marker.label)}: frames $start to $end\nanimation.current?.play($start, $end);"
        }
    }

    // ---- dotLottie Android -----------------------------------------------------------------------------------------------------

    private open class DotLottieView(c: SnippetContext, val machine: String?) : Generator(c) {
        open val player = "dotLottieAnimationView"

        /** The source of the file in an Android app: a raw resource when it is in `res/raw`, else an asset. */
        fun androidSource(): String {
            val p = c.path
            val raw = Regex("(^|/)res/raw/([^/]+)$").find(p)
            if (raw != null) return "DotLottieSource.Res(R.raw.${resourceName(raw.groupValues[2])})"
            val i = p.indexOf("assets/")
            val asset = if (i == 0 || (i > 0 && p[i - 1] == '/')) p.substring(i + "assets/".length) else c.fileName
            return "DotLottieSource.Asset(${kt(asset)})"
        }

        override fun setup(): String = buildString {
            append("import com.lottiefiles.dotlottie.core.model.Config\n")
            append("import com.lottiefiles.dotlottie.core.util.DotLottieSource\n")
            append("import com.lottiefiles.dotlottie.core.widget.DotLottieAnimation\n\n")
            append("val $player = findViewById<DotLottieAnimation>(R.id.lottie_view)\n")
            append("$player.load(\n")
            append("    Config.Builder()\n")
            append("        .source(${androidSource()})\n")
            append("        .autoplay(true)\n")
            append("        .loop(true)\n")
            append("        .build(),\n")
            append(")")
            if (machine != null) append("\n// once loaded (DotLottieEventListener.onLoad):\n${stateMachine(machine)}")
        }

        override fun playMarker(marker: SnippetMarker) = "$player.setMarker(${kt(marker.name)})\n$player.play()"

        override fun stateMachine(id: String) = "$player.stateMachineLoad(${kt(id)})\n$player.stateMachineStart()"

        override fun input(input: StateMachineInput, value: Any?): String = when (input.type) {
            InputType.BOOLEAN -> "$player.stateMachineSetBooleanInput(${kt(input.name)}, ${value == true})"
            InputType.NUMERIC -> "$player.stateMachineSetNumericInput(${kt(input.name)}, ${float(numberOf(value))})"
            InputType.STRING -> "$player.stateMachineSetStringInput(${kt(input.name)}, ${kt(value?.toString() ?: "")})"
            InputType.EVENT -> "$player.${fireMethod()}(${kt(input.name)})"
        }

        open fun fireMethod() = "stateMachineFireEvent"
        open fun addListener() = "$player.addStateMachineEventListener"

        override fun customEvents(): String = buildString {
            append("import com.lottiefiles.dotlottie.core.util.StateMachineEventListener\n\n")
            append("${addListener()}(object : StateMachineEventListener {\n")
            append("    override fun onCustomEvent(message: String) {\n")
            if (c.customEvents.isEmpty()) {
                append("        Log.d(\"Lottie\", \"State machine event: \$message\")\n")
            } else {
                append("        when (message) {\n")
                for (e in c.customEvents) append("            ${kt(e)} -> { /* … */ }\n")
                append("        }\n")
            }
            append("    }\n")
            append("})")
        }
    }

    private class DotLottieCompose(c: SnippetContext, machine: String?) : DotLottieView(c, machine) {
        override val player = "controller"

        override fun setup(): String = buildString {
            append("import androidx.compose.runtime.remember\n")
            append("import com.lottiefiles.dotlottie.core.compose.runtime.DotLottieController\n")
            append("import com.lottiefiles.dotlottie.core.compose.ui.DotLottieAnimation\n")
            append("import com.lottiefiles.dotlottie.core.util.DotLottieSource\n\n")
            append("@Composable\n")
            append("fun ${component(c.baseName)}() {\n")
            append("    // drives the player from your code (the calls below)\n")
            append("    val controller = remember { DotLottieController() }\n")
            append("    DotLottieAnimation(\n")
            append("        source = ${androidSource()},\n")
            append("        autoplay = true,\n")
            append("        loop = true,\n")
            if (machine != null) append("        stateMachineId = ${kt(machine)},\n")
            append("        controller = controller,\n")
            append("    )\n")
            append("}")
        }

        override fun fireMethod() = "stateMachineFire"
        override fun addListener() = "controller.stateMachineAddEventListener"
    }

    // ---- Lottie Android (Airbnb) ------------------------------------------------------------------------------------------------

    private class LottieAndroid(c: SnippetContext) : Generator(c) {
        override fun setup(): String = buildString {
            append("import com.airbnb.lottie.LottieAnimationView\n\n")
            append("val animationView = findViewById<LottieAnimationView>(R.id.animation_view)\n")
            append(androidLoad("animationView"))
            append("animationView.repeatCount = LottieDrawable.INFINITE\n")
            append("animationView.playAnimation()")
        }

        override fun playMarker(marker: SnippetMarker) = "animationView.setMinAndMaxFrame(${kt(marker.name)})\nanimationView.playAnimation()"

        private fun androidLoad(view: String): String {
            val raw = Regex("(^|/)res/raw/([^/]+)$").find(c.path)
            if (raw != null) return "$view.setAnimation(R.raw.${resourceName(raw.groupValues[2])})\n"
            val i = c.path.indexOf("assets/")
            val asset = if (i == 0 || (i > 0 && c.path[i - 1] == '/')) c.path.substring(i + "assets/".length) else c.fileName
            return "$view.setAnimation(${kt(asset)}) // from src/main/assets\n"
        }
    }

    private class LottieCompose(c: SnippetContext) : Generator(c) {
        private fun spec(): String {
            val raw = Regex("(^|/)res/raw/([^/]+)$").find(c.path)
            if (raw != null) return "LottieCompositionSpec.RawRes(R.raw.${resourceName(raw.groupValues[2])})"
            val i = c.path.indexOf("assets/")
            val asset = if (i == 0 || (i > 0 && c.path[i - 1] == '/')) c.path.substring(i + "assets/".length) else c.fileName
            return "LottieCompositionSpec.Asset(${kt(asset)})"
        }

        override fun setup(): String = buildString {
            append("import com.airbnb.lottie.compose.*\n\n")
            append("@Composable\n")
            append("fun ${component(c.baseName)}() {\n")
            append("    val composition by rememberLottieComposition(${spec()})\n")
            append("    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)\n")
            append("    LottieAnimation(composition, { progress })\n")
            append("}")
        }

        override fun playMarker(marker: SnippetMarker) =
            "val progress by animateLottieCompositionAsState(\n    composition,\n    clipSpec = LottieClipSpec.Marker(${kt(marker.name)}),\n)"

    }

    // ---- iOS ---------------------------------------------------------------------------------------------------------------------

    private class DotLottieIos(c: SnippetContext, val machine: String?) : Generator(c) {
        override fun setup(): String = buildString {
            append("import SwiftUI\n")
            append("import DotLottie\n\n")
            append("struct ${component(c.baseName)}View: View {\n")
            append("    // keep the animation to drive it from your code (the calls below)\n")
            append("    let animation = DotLottieAnimation(\n")
            append("        fileName: ${swift(c.baseName)},\n")
            append("        config: AnimationConfig(autoplay: true, loop: true")
            if (machine != null) append(", stateMachineId: ${swift(machine)}")
            append(")\n")
            append("    )\n\n")
            append("    var body: some View {\n")
            append("        animation.view()\n")
            append("    }\n")
            append("}")
        }

        override fun playMarker(marker: SnippetMarker) = "animation.setMarker(marker: ${swift(marker.name)})\nanimation.play()"

        override fun stateMachine(id: String) = "_ = animation.stateMachineLoad(id: ${swift(id)})\n_ = animation.stateMachineStart()"

        override fun input(input: StateMachineInput, value: Any?): String = when (input.type) {
            InputType.BOOLEAN -> "_ = animation.stateMachineSetBooleanInput(key: ${swift(input.name)}, value: ${value == true})"
            InputType.NUMERIC -> "_ = animation.stateMachineSetNumericInput(key: ${swift(input.name)}, value: ${number(numberOf(value))})"
            InputType.STRING -> "_ = animation.stateMachineSetStringInput(key: ${swift(input.name)}, value: ${swift(value?.toString() ?: "")})"
            InputType.EVENT -> "animation.stateMachineFire(event: ${swift(input.name)})"
        }

        override fun customEvents(): String = buildString {
            append("final class EventObserver: StateMachineObserver {\n")
            append("    func onCustomEvent(message: String) {\n")
            if (c.customEvents.isEmpty()) {
                append("        print(\"State machine event: \\(message)\")\n")
            } else {
                append("        switch message {\n")
                for (e in c.customEvents) append("        case ${swift(e)}: break // …\n")
                append("        default: break\n")
                append("        }\n")
            }
            append("    }\n\n")
            append("    func onBooleanInputValueChange(inputName: String, oldValue: Bool, newValue: Bool) {}\n")
            append("    func onError(message: String) {}\n")
            append("    func onInputFired(inputName: String) {}\n")
            append("    func onNumericInputValueChange(inputName: String, oldValue: Float, newValue: Float) {}\n")
            append("    func onStart() {}\n")
            append("    func onStateEntered(enteringState: String) {}\n")
            append("    func onStateExit(leavingState: String) {}\n")
            append("    func onStop() {}\n")
            append("    func onStringInputValueChange(inputName: String, oldValue: String, newValue: String) {}\n")
            append("    func onTransition(previousState: String, newState: String) {}\n")
            append("}\n\n")
            append("_ = animation.stateMachineSubscribe(EventObserver())")
        }
    }

    private class LottieIos(c: SnippetContext) : Generator(c) {
        override fun setup(): String = buildString {
            append("import Lottie\n\n")
            if (c.dotLottie) {
                append("let animationView = LottieAnimationView(dotLottieName: ${swift(c.baseName)})\n")
            } else {
                append("let animationView = LottieAnimationView(name: ${swift(c.baseName)})\n")
            }
            append("animationView.loopMode = .loop\n")
            append("animationView.play()")
        }

        override fun playMarker(marker: SnippetMarker) =
            "animationView.play(marker: ${swift(marker.name)}, loopMode: .playOnce) { finished in\n    // the segment ${comment(marker.label)} played to its end (finished) or was interrupted\n}"
    }

    // ---- Flutter -----------------------------------------------------------------------------------------------------------------

    private class Flutter(c: SnippetContext) : Generator(c) {
        override fun setup(): String = buildString {
            append("import 'package:flutter/material.dart';\n")
            append("import 'package:lottie/lottie.dart';\n\n")
            append("class ${component(c.baseName)} extends StatefulWidget {\n")
            append("  const ${component(c.baseName)}({super.key});\n\n")
            append("  @override\n")
            append("  State<${component(c.baseName)}> createState() => _${component(c.baseName)}State();\n")
            append("}\n\n")
            append("class _${component(c.baseName)}State extends State<${component(c.baseName)}> with TickerProviderStateMixin {\n")
            append("  late final AnimationController _controller = AnimationController(vsync: this);\n")
            append("  LottieComposition? _composition;\n\n")
            append("  @override\n")
            append("  void dispose() {\n")
            append("    _controller.dispose();\n")
            append("    super.dispose();\n")
            append("  }\n\n")
            append("  @override\n")
            append("  Widget build(BuildContext context) => Lottie.asset(\n")
            append("        ${js(if (c.dotLottie) c.path.substringBeforeLast('.') + ".json" else c.path, dollar = true)},\n")
            append("        controller: _controller,\n")
            append("        onLoaded: (composition) {\n")
            append("          _composition = composition;\n")
            append("          _controller\n")
            append("            ..duration = composition.duration\n")
            append("            ..repeat();\n")
            append("        },\n")
            append("      );\n")
            append("}")
        }

        override fun playMarker(marker: SnippetMarker) =
            "final marker = _composition?.getMarker(${js(marker.name, dollar = true)});\n" +
                "if (marker != null) {\n  _controller.value = marker.start;\n  _controller.animateTo(marker.end);\n}"

    }

    // ---- dotLottie React Native and Flutter -----------------------------------------------------------------------------

    private class DotLottieReactNative(c: SnippetContext, machine: String?) : DotLottieWeb(c, machine) {
        override val access = "ref.current?."

        override fun setup(): String = buildString {
            append("import { useRef } from 'react';\n")
            append("import { DotLottie } from '@lottiefiles/dotlottie-react-native';\n\n")
            append("export function ${component(c.baseName)}() {\n")
            append("  // drives the player from your code (the calls below)\n")
            append("  const ref = useRef(null);\n")
            append("  return (\n")
            append("    <DotLottie\n")
            append("      ref={ref}\n")
            append("      source={require(${js("./" + c.path)})}\n")
            append("      autoplay\n")
            append("      loop\n")
            if (machine != null) append("      stateMachineId=${jsxString(machine)}\n")
            append("      style={{ width: 200, height: 200 }}\n")
            append("    />\n")
            append("  );\n")
            append("}")
        }

        override fun input(input: StateMachineInput, value: Any?): String =
            if (input.type == InputType.EVENT) "${access}stateMachineFire(${js(input.name)});" else super.input(input, value)

        override fun customEvents(): String =
            "// a prop of <DotLottie>\nonStateMachineCustomEvent={(message) => {\n" + eventSwitch("  ", "message") + "}}"
    }

    private class DotLottieFlutter(c: SnippetContext, val machine: String?) : Generator(c) {
        override fun setup(): String = buildString {
            append("import 'package:dotlottie_flutter/dotlottie_flutter.dart';\n\n")
            append("// drives the player from your code (the calls below)\n")
            append("DotLottieViewController? _controller;\n\n")
            append("DotLottieView(\n")
            append("  sourceType: 'asset',\n")
            append("  source: ${js(c.path, dollar = true)},\n")
            append("  autoplay: true,\n")
            append("  loop: true,\n")
            if (machine != null) append("  stateMachineId: ${js(machine, dollar = true)},\n")
            append("  onViewCreated: (controller) => _controller = controller,\n")
            append(")")
        }

        override fun playMarker(marker: SnippetMarker) = "_controller?.setMarker(${js(marker.name, dollar = true)});\n_controller?.play();"

        override fun stateMachine(id: String) = "_controller?.stateMachineLoad(${js(id, dollar = true)});\n_controller?.stateMachineStart();"

        override fun input(input: StateMachineInput, value: Any?): String = when (input.type) {
            InputType.BOOLEAN -> "_controller?.stateMachineSetBooleanInput(${js(input.name, dollar = true)}, ${value == true});"
            InputType.NUMERIC -> "_controller?.stateMachineSetNumericInput(${js(input.name, dollar = true)}, ${dartDouble(numberOf(value))});"
            InputType.STRING -> "_controller?.stateMachineSetStringInput(${js(input.name, dollar = true)}, ${js(value?.toString() ?: "", dollar = true)});"
            InputType.EVENT -> "_controller?.stateMachineFire(${js(input.name, dollar = true)});"
        }

        override fun customEvents(): String = buildString {
            append("// a parameter of DotLottieView\n")
            append("stateMachineOnCustomEvent: (message) {\n")
            if (c.customEvents.isEmpty()) {
                append("  debugPrint('State machine event: \$message');\n")
            } else {
                append("  switch (message) {\n")
                for (e in c.customEvents) append("    case ${js(e, dollar = true)}: // …\n      break;\n")
                append("  }\n")
            }
            append("},")
        }

        private fun dartDouble(value: Double) = number(value).let { if ('.' in it || 'E' in it) it else "$it.0" }
    }

    /** A type name from a file name: `hand_loading` → `HandLoadingAnimation`. */
    internal fun component(baseName: String): String {
        val words = baseName.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        val name = words.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }.ifEmpty { "Lottie" }
        val safe = if (name.first().isDigit()) "Lottie$name" else name
        return if (safe.endsWith("Animation")) safe else safe + "Animation"
    }

    /** An Android resource name from a file name (`Hand-Loading.json` → `hand_loading`). */
    internal fun resourceName(fileName: String): String =
        fileName.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]"), "_").let { if (it.firstOrNull()?.isLetter() == true) it else "lottie_$it" }

    /** A Kotlin float literal. */
    private fun float(value: Double) = number(value) + "f"

    /** [text] made safe to put inside a code comment. */
    private fun comment(text: String) = "“" + text.replace("*/", "* /").replace("\n", " ") + "”"
}
