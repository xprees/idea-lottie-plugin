package cz.xprees.lottie

import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.statemachine.InputType
import cz.xprees.lottie.statemachine.StateMachine
import cz.xprees.lottie.statemachine.StateMachineChecker
import cz.xprees.lottie.statemachine.StateMachineInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class StateMachineTest {
    private val archive = DotLottieArchive.read(TestData.bytes("interactive.lottie"))
    private val machine = StateMachine.parse(archive.stateMachineText("toggle")!!)!!

    @Test
    fun `reads inputs and states`() {
        assertThat(machine.initial).isEqualTo("off")
        assertThat(machine.stateNames).containsExactly("off", "on")
        assertThat(machine.inputs).containsExactly(
            StateMachineInput("isOn", InputType.BOOLEAN, false),
            StateMachineInput("clicks", InputType.NUMERIC, 0L),
            StateMachineInput("label", InputType.STRING, "hello"),
            StateMachineInput("reset", InputType.EVENT, null),
        )
    }

    @Test
    fun `the example has no broken references`() {
        assertThat(StateMachineChecker.check(machine, archive.stateMachineContext())).isEmpty()
    }

    @Test
    fun `renaming an input updates every reference`() {
        val withRef = StateMachine.parse(
            """{"initial":"a","states":[{"name":"a","type":"PlaybackState","animation":"sample","transitions":[
               {"type":"Transition","toState":"a","guards":[{"type":"Numeric","inputName":"clicks","conditionType":"Equal","compareTo":"${'$'}clicks"}]}],
               "entryActions":[{"type":"Increment","inputName":"clicks","value":"${'$'}clicks"}]}],
               "inputs":[{"type":"Numeric","name":"clicks","value":0},{"type":"String","name":"clicksLabel","value":"${'$'}clicksX"}]}""",
        )!!
        val renamed = MiniJson.write(withRef.renameInput("clicks", "taps").root)
        assertThat(renamed).contains("\"inputName\":\"taps\"", "\"compareTo\":\"${'$'}taps\"", "\"value\":\"${'$'}taps\"", "\"name\":\"taps\"")
        // other names and values that merely contain the old name stay
        assertThat(renamed).contains("\"name\":\"clicksLabel\"", "\"value\":\"${'$'}clicksX\"").doesNotContain("clicks\"")
            .doesNotContain("\"inputName\":\"clicks\"")
        // a state named like the input is not an input
        val stateNamed = StateMachine.parse("""{"initial":"x","states":[{"name":"x","transitions":[]}],"inputs":[{"type":"Event","name":"x"}]}""")!!
        assertThat(stateNamed.renameInput("x", "y").stateNames).containsExactly("x")
        assertThat(stateNamed.renameInput("x", "y").inputs.map { it.name }).containsExactly("y")
    }

    @Test
    fun `adding, deleting and changing inputs`() {
        val added = machine.addInput(StateMachineInput("speed", InputType.NUMERIC, "1.5")).addInput(StateMachineInput("go", InputType.EVENT, "ignored"))
        assertThat(added.inputs.takeLast(2)).containsExactly(StateMachineInput("speed", InputType.NUMERIC, 1.5), StateMachineInput("go", InputType.EVENT, null))
        assertThat(MiniJson.write(added.root)).contains("{\"type\":\"Event\",\"name\":\"go\"}")
        assertThat(added.setInitialValue("isOn", "true").input("isOn")!!.value).isEqualTo(true)
        val deleted = machine.deleteInput("isOn")
        assertThat(deleted.inputs.map { it.name }).containsExactly("clicks", "label", "reset")
        // its uses are now reported
        assertThat(StateMachineChecker.check(deleted).map { it.message }).contains("Unknown input 'isOn'")
    }

    @Test
    fun `reports broken references`() {
        val broken = StateMachine.parse(
            """{"initial":"nowhere","states":[{"name":"a","type":"PlaybackState","animation":"missing","segment":"nope","transitions":[
               {"type":"Transition","toState":"b","guards":[{"type":"Boolean","inputName":"count","conditionType":"Equal","compareTo":true},
                {"type":"Numeric","inputName":"count","conditionType":"Equal","compareTo":"${'$'}ghost"}]}],
               "entryActions":[{"type":"Toggle","inputName":"count"},{"type":"SetTheme","value":"dark"},{"type":"Fire","inputName":"count"}]}],
               "interactions":[{"type":"Click","layerName":"Nope","actions":[]},{"type":"OnComplete","stateName":"zzz","actions":[]}],
               "inputs":[{"type":"Numeric","name":"count","value":0},{"type":"Numeric","name":"count","value":1},{"type":"Colour","name":"c"}]}""",
        )!!
        val messages = StateMachineChecker.check(broken, archive.stateMachineContext()).map { it.path.joinToString("/") + ": " + it.message }
        assertThat(messages).containsExactlyInAnyOrder(
            "initial: Unknown state 'nowhere'",
            "inputs/0/name: Duplicate input 'count'",
            "inputs/1/name: Duplicate input 'count'",
            "inputs/2/type: Unknown input type 'Colour'",
            "states/0/animation: Unknown animation 'missing'",
            "states/0/segment: Unknown marker 'nope' in animation 'sample'",
            "states/0/transitions/0/toState: Unknown state 'b'",
            "states/0/transitions/0/guards/0/inputName: A Boolean guard needs a Boolean input, 'count' is Numeric",
            "states/0/transitions/0/guards/1/compareTo: Unknown input 'ghost'",
            "states/0/entryActions/0/inputName: 'Toggle' needs a Boolean input, 'count' is Numeric",
            "states/0/entryActions/1/value: Unknown theme 'dark'",
            "states/0/entryActions/2/inputName: 'Fire' needs a Event input, 'count' is Numeric",
            "interactions/0/layerName: Unknown layer 'Nope'",
            "interactions/1/stateName: Unknown state 'zzz'",
        )
    }

    @Test
    fun `the context knows markers by both names and all layers`() {
        val context = archive.stateMachineContext()
        assertThat(context.animationIds).containsExactly("sample")
        assertThat(context.markers["sample"]).contains("intro", "outro")
        assertThat(context.layerNames).contains("Circle", "Background", "Square")
    }

    @Test
    fun `replacing an archive entry keeps the others`() {
        val bytes = TestData.bytes("interactive.lottie")
        val changed = DotLottieWriter.replace(bytes, mapOf("s/toggle.json" to "{}".toByteArray(), "t/new.json" to "{\"rules\":[]}".toByteArray()))
        val again = DotLottieArchive.read(changed)
        assertThat(again.entries.map { it.path }).containsExactly("a/sample.json", "manifest.json", "s/toggle.json", "t/new.json")
        assertThat(again.stateMachineText("toggle")).isEqualTo("{}")
        assertThat(again.animationText("sample")).isEqualTo(archive.animationText("sample"))
        val removed = DotLottieArchive.read(DotLottieWriter.replace(changed, mapOf("t/new.json" to null)))
        assertThat(removed.entries.map { it.path }).doesNotContain("t/new.json")
        assertThat(MiniJson.parse(removed.bytes("manifest.json")!!.decodeToString()).obj()!!["stateMachines"].arr()).hasSize(1)
    }

    @Test
    fun `code snippets drive the dotlottie-web player`() {
        assertThat(cz.xprees.lottie.statemachine.Snippets.forInput(StateMachineInput("isOn", InputType.BOOLEAN, false), true))
            .isEqualTo("dotLottie.stateMachineSetBooleanInput('isOn', true);")
        assertThat(cz.xprees.lottie.statemachine.Snippets.forInput(StateMachineInput("n", InputType.NUMERIC, 0L), 2.5))
            .isEqualTo("dotLottie.stateMachineSetNumericInput('n', 2.5);")
        assertThat(cz.xprees.lottie.statemachine.Snippets.forInput(StateMachineInput("it's", InputType.STRING, ""), "a\\b\n'c'"))
            .isEqualTo("dotLottie.stateMachineSetStringInput('it\\'s', 'a\\\\b\\n\\'c\\'');")
        assertThat(cz.xprees.lottie.statemachine.Snippets.forInput(StateMachineInput("go", InputType.EVENT, null), null))
            .isEqualTo("dotLottie.stateMachineFireEvent('go');")
    }
}
