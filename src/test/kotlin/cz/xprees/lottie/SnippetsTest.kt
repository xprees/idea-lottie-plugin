package cz.xprees.lottie

import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.snippets.CodePlatform
import cz.xprees.lottie.snippets.PlatformDetector
import cz.xprees.lottie.snippets.SnippetContext
import cz.xprees.lottie.snippets.SnippetGenerator
import cz.xprees.lottie.snippets.SnippetKind
import cz.xprees.lottie.snippets.SnippetMarker
import cz.xprees.lottie.snippets.SnippetNote
import cz.xprees.lottie.statemachine.InputType
import cz.xprees.lottie.statemachine.StateMachine
import cz.xprees.lottie.statemachine.StateMachineInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SnippetsTest {
    private val markers = listOf(SnippetMarker("intro", 0.0, 10.0), SnippetMarker("hit", 15.0, 0.0))
    private val json = SnippetContext("web/public/anim/hand_loading.json", dotLottie = false, markers = markers)
    private val inputs = listOf(
        StateMachineInput("isOn", InputType.BOOLEAN, false),
        StateMachineInput("clicks", InputType.NUMERIC, 0L),
        StateMachineInput("label", InputType.STRING, "hello"),
        StateMachineInput("reset", InputType.EVENT, null),
    )
    private val interactive = SnippetContext(
        "app/src/main/assets/toggle.lottie", dotLottie = true, markers = markers,
        stateMachineId = "toggle", inputs = inputs, values = mapOf("clicks" to 3.0), customEvents = listOf("switched-on"),
    )

    @Test
    fun `every platform gets setup and marker code, and state machine code where it has state machines`() {
        for (platform in CodePlatform.entries) {
            val snippets = SnippetGenerator.generate(platform, interactive)
            val kinds = snippets.snippets.map { it.kind }
            assertThat(kinds).describedAs(platform.name).contains(SnippetKind.SETUP, SnippetKind.PLAY_MARKER)
            assertThat(snippets.snippets.filter { it.kind == SnippetKind.PLAY_MARKER }.map { it.subject }).containsExactly("intro", "hit")
            if (platform.stateMachines) {
                assertThat(snippets.snippets.filter { it.kind == SnippetKind.INPUT }.map { it.subject }).containsExactly("isOn", "clicks", "label", "reset")
                assertThat(kinds).contains(SnippetKind.STATE_MACHINE, SnippetKind.CUSTOM_EVENTS)
                assertThat(snippets.notes).doesNotContain(SnippetNote.NO_STATE_MACHINES)
            } else {
                assertThat(kinds).doesNotContain(SnippetKind.INPUT, SnippetKind.STATE_MACHINE)
                assertThat(snippets.notes).contains(SnippetNote.NO_STATE_MACHINES)
            }
            assertThat(snippets.notes.contains(SnippetNote.JSON_ONLY)).describedAs(platform.name).isEqualTo(!platform.dotLottieFiles)
            snippets.snippets.forEach { assertThat(it.code).describedAs("${platform.name} ${it.kind}").isNotBlank() }
            // following the markers: in the setup of the web players, a snippet of its own, or a note saying why there is none
            val follows = snippets.first(SnippetKind.SETUP)!!.code.contains("currentMarker") || SnippetKind.MARKER_EVENTS in kinds
            assertThat(follows || SnippetNote.NO_MARKER_EVENTS in snippets.notes).describedAs(platform.name).isTrue()
        }
    }

    @Test
    fun `web players get typed components that play and follow markers`() {
        val vue = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_VUE, interactive).first(SnippetKind.SETUP)!!
        assertThat(vue.extension).isEqualTo("vue")
        assertThat(vue.code).contains(
            "<script setup lang=\"ts\">",
            "import { DotLottieVue, type DotLottie, type DotLottieVueInstance } from '@lottiefiles/dotlottie-vue';",
            "type MarkerName = 'intro' | 'hit';",
            "const playerRef = useTemplateRef<DotLottieVueInstance>('player');",
            "const dotLottie = shallowRef<DotLottie | null>(null);",
            "dotLottie.value = instance;",
            "currentMarker.value = instance.markers().find((m) => currentFrame >= m.start && currentFrame < m.end)?.name;",
            "case 'intro':",
            "function playMarker(name: MarkerName) {",
        )
        assertThat(vue.code).doesNotContain("// const")
        val react = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_REACT, interactive).first(SnippetKind.SETUP)!!
        assertThat(react.extension).isEqualTo("tsx")
        assertThat(react.code).contains("useState<DotLottie | null>(null)", "type FrameEvent", "dotLottie.addEventListener('frame', onFrame);", "}, [currentMarker]);")
        // the marker one-liners are code, not a component: highlighted as TypeScript
        assertThat(SnippetGenerator.generate(CodePlatform.DOTLOTTIE_VUE, interactive).first(SnippetKind.PLAY_MARKER)!!.extension).isEqualTo("ts")
        val web = SnippetGenerator.generate(CodePlatform.LOTTIE_WEB, json).first(SnippetKind.SETUP)!!
        assertThat(web.code).contains("{ name: 'hit', start: 15, end: 16 },", "animation.firstFrame + animation.currentFrame")
        val android = SnippetGenerator.generate(CodePlatform.LOTTIE_ANDROID, json)
        assertThat(android.first(SnippetKind.MARKER_EVENTS)!!.code).contains("animationView.composition?.markers", "\"intro\" -> {")
        assertThat(SnippetGenerator.generate(CodePlatform.LOTTIE_IOS, json).notes).contains(SnippetNote.NO_MARKER_EVENTS)
        // no markers, nothing about them
        val plain = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_REACT, json.copy(markers = emptyList()))
        assertThat(plain.first(SnippetKind.SETUP)!!.code).doesNotContain("Marker", "useEffect")
        assertThat(plain.notes).doesNotContain(SnippetNote.NO_MARKER_EVENTS)
    }

    @Test
    fun `javascript leaves the types out`() {
        for (platform in CodePlatform.entries.filter { it.typeScript }) {
            val js = SnippetGenerator.generate(platform, interactive, typeScript = false)
            for (snippet in js.snippets) {
                assertThat(snippet.code).describedAs("${platform.name} ${snippet.kind}").doesNotContain("type MarkerName", ": MarkerName", "<DotLottie | null>", "lang=\"ts\"", "type DotLottie")
                assertThat(snippet.extension).describedAs("${platform.name} ${snippet.kind}").isNotIn("ts", "tsx")
            }
        }
        assertThat(SnippetGenerator.generate(CodePlatform.DOTLOTTIE_VUE, interactive, typeScript = false).first(SnippetKind.SETUP)!!.code)
            .contains("<script setup>", "useTemplateRef('player')", "shallowRef(null)")
    }

    @Test
    fun `the agent prompt describes the animation and the player`() {
        val prompt = cz.xprees.lottie.snippets.AgentPrompt.build(CodePlatform.DOTLOTTIE_VUE, "dotLottie Vue", true, interactive)
        assertThat(prompt).contains(
            "`app/src/main/assets/toggle.lottie` (a dotLottie file)",
            "dotLottie Vue (`@lottiefiles/dotlottie-vue`), TypeScript",
            "- `intro`: frames 0–10",
            "- `hit`: frames 15–16",
            "State machine `toggle`",
            "`clicks` (number, now 3.0)",
            "`reset` (event, fired)",
            "custom events it sends: `switched-on`",
            "```vue\n<script setup lang=\"ts\">",
        )
        val ios = cz.xprees.lottie.snippets.AgentPrompt.build(CodePlatform.LOTTIE_IOS, "Lottie iOS", true, interactive)
        assertThat(ios).contains("has no state machines", "```swift").doesNotContain("TypeScript")
    }

    @Test
    fun `typescript is guessed from the project`() {
        assertThat(PlatformDetector.usesTypeScript(mapOf("package.json" to "{}", "tsconfig.json" to "{}"))).isTrue()
        assertThat(PlatformDetector.usesTypeScript(mapOf("web/package.json" to """{"devDependencies":{"typescript":"5"}}"""))).isTrue()
        assertThat(PlatformDetector.usesTypeScript(mapOf("package.json" to """{"dependencies":{"vue":"3"}}"""))).isFalse()
        assertThat(PlatformDetector.usesTypeScript(mapOf("node_modules/x/tsconfig.json" to "{}", "package.json" to "{}"))).isFalse()
        assertThat(PlatformDetector.usesTypeScript(mapOf("app/build.gradle" to ""))).isTrue()
    }

    @Test
    fun `dotlottie web code uses the file, markers and live input values`() {
        val s = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_WEB, interactive)
        assertThat(s.first(SnippetKind.SETUP)!!.code).contains("src: '/toggle.lottie'", "stateMachineId: 'toggle'")
        assertThat(s.first(SnippetKind.PLAY_MARKER, "hit")!!.code).isEqualTo("dotLottie.setMarker('hit');\ndotLottie.play();")
        // markers are played by name only: no frame numbers anywhere
        assertThat(s.snippets.filter { it.kind == SnippetKind.PLAY_MARKER }.map { it.code }).noneMatch { it.contains("15") }
        assertThat(s.first(SnippetKind.INPUT, "clicks")!!.code).isEqualTo("dotLottie.stateMachineSetNumericInput('clicks', 3);")
        assertThat(s.first(SnippetKind.CUSTOM_EVENTS)!!.code).contains("case 'switched-on':")
        assertThat(SnippetGenerator.generate(CodePlatform.DOTLOTTIE_WEB, json).first(SnippetKind.SETUP)!!.code).contains("src: '/anim/hand_loading.json'")
    }

    @Test
    fun `react code waits for the player instance`() {
        val s = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_REACT, interactive)
        assertThat(s.first(SnippetKind.SETUP)!!.code).contains("export function ToggleAnimation()", "stateMachineId=\"toggle\"", "dotLottieRefCallback={setDotLottie}", "import { useEffect, useState }")
        assertThat(s.first(SnippetKind.INPUT, "isOn")!!.code).isEqualTo("dotLottie?.stateMachineSetBooleanInput('isOn', false);")
        assertThat(s.first(SnippetKind.CUSTOM_EVENTS)!!.code).contains("removeEventListener('stateMachineCustomEvent', onEvent)")
    }

    @Test
    fun `android code uses assets and raw resources and each API's names`() {
        val compose = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_ANDROID_COMPOSE, interactive)
        assertThat(compose.first(SnippetKind.SETUP)!!.code).contains("DotLottieSource.Asset(\"toggle.lottie\")", "stateMachineId = \"toggle\"")
        assertThat(compose.first(SnippetKind.INPUT, "reset")!!.code).isEqualTo("controller.stateMachineFire(\"reset\")")
        assertThat(compose.first(SnippetKind.INPUT, "clicks")!!.code).isEqualTo("controller.stateMachineSetNumericInput(\"clicks\", 3f)")
        val view = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_ANDROID_VIEW, interactive)
        assertThat(view.first(SnippetKind.INPUT, "reset")!!.code).isEqualTo("dotLottieAnimationView.stateMachineFireEvent(\"reset\")")
        assertThat(view.first(SnippetKind.CUSTOM_EVENTS)!!.code).contains("dotLottieAnimationView.addStateMachineEventListener(")
        val raw = SnippetGenerator.generate(CodePlatform.LOTTIE_ANDROID, json.copy(path = "app/src/main/res/raw/Hand-Loading.json"))
        assertThat(raw.first(SnippetKind.SETUP)!!.code).contains("setAnimation(R.raw.hand_loading)")
        assertThat(raw.first(SnippetKind.PLAY_MARKER, "intro")!!.code).contains("setMinAndMaxFrame(\"intro\")")
        assertThat(SnippetGenerator.generate(CodePlatform.LOTTIE_COMPOSE, json).first(SnippetKind.PLAY_MARKER, "hit")!!.code)
            .contains("LottieClipSpec.Marker(\"hit\")")
    }

    @Test
    fun `ios, flutter and react native code`() {
        val ios = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_IOS, interactive)
        assertThat(ios.first(SnippetKind.SETUP)!!.code).contains("fileName: \"toggle\"", "stateMachineId: \"toggle\"")
        assertThat(ios.first(SnippetKind.INPUT, "label")!!.code).isEqualTo("_ = animation.stateMachineSetStringInput(key: \"label\", value: \"hello\")")
        assertThat(ios.first(SnippetKind.PLAY_MARKER, "hit")!!.code).isEqualTo("animation.setMarker(marker: \"hit\")\nanimation.play()")
        assertThat(SnippetGenerator.generate(CodePlatform.LOTTIE_IOS, interactive).first(SnippetKind.SETUP)!!.code).contains("LottieAnimationView(dotLottieName: \"toggle\")")
        assertThat(SnippetGenerator.generate(CodePlatform.FLUTTER, json).first(SnippetKind.SETUP)!!.code)
            .contains("Lottie.asset(", "'web/public/anim/hand_loading.json'", "class HandLoadingAnimation extends StatefulWidget")
        assertThat(SnippetGenerator.generate(CodePlatform.REACT_NATIVE, json).first(SnippetKind.PLAY_MARKER, "intro")!!.code)
            .contains("animation.current?.play(0, 10);")
    }

    @Test
    fun `dotLottie framework players get their own setup and instance access`() {
        val vue = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_VUE, interactive)
        assertThat(vue.first(SnippetKind.SETUP)!!.code).contains("<DotLottieVue ref=\"player\" src=\"/toggle.lottie\" autoplay loop state-machine-id=\"toggle\" />", "getDotLottieInstance()")
        assertThat(vue.first(SnippetKind.PLAY_MARKER, "intro")!!.code).isEqualTo("dotLottie.value?.setMarker('intro');\ndotLottie.value?.play();")
        val svelte = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_SVELTE, interactive)
        assertThat(svelte.first(SnippetKind.SETUP)!!.code).contains("dotLottieRefCallback={(instance) => (dotLottie = instance)}", "stateMachineId=\"toggle\"")
        assertThat(svelte.first(SnippetKind.INPUT, "isOn")!!.code).startsWith("dotLottie?.stateMachineSetBooleanInput")
        val solid = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_SOLID, interactive)
        assertThat(solid.first(SnippetKind.SETUP)!!.code).contains("createSignal<DotLottie | null>(null)", "<DotLottieSolid")
        assertThat(solid.first(SnippetKind.STATE_MACHINE)!!.code).isEqualTo("dotLottie()?.stateMachineLoad('toggle');\ndotLottie()?.stateMachineStart();")
        val wc = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_WEB_COMPONENT, interactive)
        assertThat(wc.first(SnippetKind.SETUP)!!.code).contains("<dotlottie-wc id=\"lottie\" src=\"/toggle.lottie\" autoplay loop statemachineid=\"toggle\">", ".dotLottie;")
        val rn = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_REACT_NATIVE, interactive)
        assertThat(rn.first(SnippetKind.SETUP)!!.code).contains("from '@lottiefiles/dotlottie-react-native'", "stateMachineId=\"toggle\"")
        assertThat(rn.first(SnippetKind.INPUT, "reset")!!.code).isEqualTo("ref.current?.stateMachineFire('reset');")
        assertThat(rn.first(SnippetKind.CUSTOM_EVENTS)!!.code).contains("onStateMachineCustomEvent={(message) => {", "switch (message)")
        val flutter = SnippetGenerator.generate(CodePlatform.DOTLOTTIE_FLUTTER, interactive)
        assertThat(flutter.first(SnippetKind.SETUP)!!.code).contains("DotLottieView(", "source: 'app/src/main/assets/toggle.lottie'", "stateMachineId: 'toggle'")
        assertThat(flutter.first(SnippetKind.INPUT, "clicks")!!.code).isEqualTo("_controller?.stateMachineSetNumericInput('clicks', 3.0);")
        assertThat(flutter.first(SnippetKind.CUSTOM_EVENTS)!!.code).contains("stateMachineOnCustomEvent: (message) {", "case 'switched-on':")
    }

    @Test
    fun `string literals are escaped for each language`() {
        val odd = SnippetMarker("it's \"\$x\"\n", 0.0, 1.0)
        assertThat(SnippetGenerator.playMarker(CodePlatform.DOTLOTTIE_WEB, odd)).startsWith("dotLottie.setMarker('it\\'s \"\$x\"\\n');")
        assertThat(SnippetGenerator.playMarker(CodePlatform.LOTTIE_ANDROID, odd)).startsWith("animationView.setMinAndMaxFrame(\"it's \\\"\\\$x\\\"\\n\")")
        assertThat(SnippetGenerator.playMarker(CodePlatform.LOTTIE_IOS, odd)).startsWith("animationView.play(marker: \"it's \\\"\$x\\\"\\n\"")
        assertThat(SnippetGenerator.playMarker(CodePlatform.FLUTTER, odd)).contains("getMarker('it\\'s \"\\\$x\"\\n')")
    }

    @Test
    fun `custom events are read from the state machine`() {
        val machine = StateMachine.parse(
            """{"initial":"a","states":[{"name":"a","type":"PlaybackState","entryActions":[{"type":"FireCustomEvent","value":"entered"}]}],
               "interactions":[{"type":"Click","actions":[{"type":"FireCustomEvent","value":"clicked"},{"type":"Fire","inputName":"x"}]}]}""",
        )!!
        assertThat(machine.customEvents).containsExactly("entered", "clicked")
        val sample = StateMachine.parse(DotLottieArchive.read(TestData.bytes("interactive.lottie")).stateMachineText("toggle")!!)!!
        assertThat(sample.customEvents).isNotNull
    }

    @Test
    fun `detects the runtime from build files`() {
        fun detect(vararg files: Pair<String, String>) = PlatformDetector.detect(files.toMap()).map { it.platform }

        assertThat(detect("package.json" to """{"dependencies":{"react":"19","@lottiefiles/dotlottie-react":"0.22"}}""").first())
            .isEqualTo(CodePlatform.DOTLOTTIE_REACT)
        assertThat(detect("package.json" to """{"dependencies":{"react":"19"}}""").first()).isEqualTo(CodePlatform.DOTLOTTIE_REACT)
        assertThat(detect("package.json" to """{"dependencies":{"react-native":"0.80","lottie-react-native":"7"}}""").first()).isEqualTo(CodePlatform.REACT_NATIVE)
        assertThat(detect("package.json" to """{"dependencies":{"lottie-web":"5"}}""").first()).isEqualTo(CodePlatform.LOTTIE_WEB)
        assertThat(detect("package.json" to """{"name":"site"}""")).containsExactly(CodePlatform.DOTLOTTIE_WEB)
        assertThat(
            detect(
                "build.gradle.kts" to "plugins { id(\"com.android.application\") }",
                "gradle/libs.versions.toml" to "compose-ui = { module = \"androidx.compose.ui:ui\" }\nlottie = { module = \"com.airbnb.android:lottie-compose\", version = \"6.6.0\" }",
            ).first(),
        ).isEqualTo(CodePlatform.LOTTIE_COMPOSE)
        assertThat(detect("app/build.gradle" to "implementation 'com.lottiefiles:dotlottie-android:0.5.0'").first()).isEqualTo(CodePlatform.DOTLOTTIE_ANDROID_VIEW)
        assertThat(detect("app/build.gradle.kts" to "android { }\nbuildFeatures { compose = true }").first()).isEqualTo(CodePlatform.DOTLOTTIE_ANDROID_COMPOSE)
        assertThat(detect("Podfile" to "pod 'lottie-ios'").first()).isEqualTo(CodePlatform.LOTTIE_IOS)
        assertThat(detect("ios/Package.resolved" to "\"location\" : \"https://github.com/LottieFiles/dotlottie-ios\"").first()).isEqualTo(CodePlatform.DOTLOTTIE_IOS)
        assertThat(detect("pubspec.yaml" to "dependencies:\n  flutter:\n    sdk: flutter\n  lottie: ^3.3.0\n").first()).isEqualTo(CodePlatform.FLUTTER)
        // a Lottie dependency anywhere beats a generic project kind; installed packages are ignored
        assertThat(detect("web/package.json" to """{"dependencies":{"react":"19"}}""", "android/app/build.gradle" to "implementation(\"com.airbnb.android:lottie:6.6.0\")").first())
            .isEqualTo(CodePlatform.LOTTIE_ANDROID)
        assertThat(detect("node_modules/x/package.json" to """{"dependencies":{"lottie-web":"5"}}""")).isEmpty()
        assertThat(detect("package.json" to """{"dependencies":{"vue":"3","@lottiefiles/dotlottie-vue":"0.13"}}""").first()).isEqualTo(CodePlatform.DOTLOTTIE_VUE)
        assertThat(detect("package.json" to """{"dependencies":{"svelte":"5"}}""").first()).isEqualTo(CodePlatform.DOTLOTTIE_SVELTE)
        assertThat(detect("package.json" to """{"dependencies":{"solid-js":"1"}}""").first()).isEqualTo(CodePlatform.DOTLOTTIE_SOLID)
        assertThat(detect("package.json" to """{"dependencies":{"@lottiefiles/dotlottie-wc":"0.12"}}""").first()).isEqualTo(CodePlatform.DOTLOTTIE_WEB_COMPONENT)
        assertThat(detect("package.json" to """{"dependencies":{"react-native":"0.80"}}""").first()).isEqualTo(CodePlatform.DOTLOTTIE_REACT_NATIVE)
        assertThat(detect("pubspec.yaml" to "dependencies:\n  dotlottie_flutter: ^0.3.0\n").first()).isEqualTo(CodePlatform.DOTLOTTIE_FLUTTER)
        assertThat(PlatformDetector.detect(mapOf("app/build.gradle" to "implementation 'com.lottiefiles:dotlottie-android:0.5.0'")).first().evidence).isEqualTo("app/build.gradle")
    }
}
