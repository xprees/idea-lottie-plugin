package cz.xprees.lottie.statemachine

import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.json.str
import cz.xprees.lottie.model.JsonPath

/** The four input types of a dotLottie state machine. */
enum class InputType(val json: String) {
    BOOLEAN("Boolean"),
    NUMERIC("Numeric"),
    STRING("String"),
    EVENT("Event");

    val hasValue get() = this != EVENT

    companion object {
        fun of(json: String?): InputType? = entries.firstOrNull { it.json == json }
    }
}

/** An input as the state machine file defines it: its name, type and initial value (null for events). */
data class StateMachineInput(val name: String, val type: InputType, val value: Any?)

/**
 * A dotLottie 2.0 state machine (`s/<id>.json`): `initial`, `states`, `interactions` and `inputs`. See the *State Machines* section
 * of https://dotlottie.io/spec/2.0/.
 *
 * Reading goes through [MiniJson]; the edit functions return a changed copy of the document and leave everything they don't touch as
 * it was, so a state machine written by another tool keeps its unknown properties.
 */
class StateMachine(val root: Map<String, Any?>) {

    val initial: String? get() = root["initial"].str()

    val inputs: List<StateMachineInput>
        get() = root["inputs"].arr().orEmpty().mapNotNull { item ->
            val input = item.obj() ?: return@mapNotNull null
            val name = input["name"].str() ?: return@mapNotNull null
            val type = InputType.of(input["type"].str()) ?: return@mapNotNull null
            StateMachineInput(name, type, input["value"])
        }

    val stateNames: List<String> get() = root["states"].arr().orEmpty().mapNotNull { it.obj()?.get("name").str() }

    fun input(name: String): StateMachineInput? = inputs.firstOrNull { it.name == name }

    fun toJson(): String = MiniJson.write(root, pretty = true) + "\n"

    // ---- edits ----------------------------------------------------------------------------------------------------------

    /** Adds an input (at the end of `inputs`). */
    fun addInput(input: StateMachineInput): StateMachine {
        val entry = linkedMapOf<String, Any?>("type" to input.type.json, "name" to input.name)
        if (input.type.hasValue) entry["value"] = defaultValue(input.type, input.value)
        return copy("inputs", root["inputs"].arr().orEmpty() + entry)
    }

    /** Removes input [name]. References to it are left in place (the reference check reports them). */
    fun deleteInput(name: String): StateMachine =
        copy("inputs", root["inputs"].arr().orEmpty().filterNot { it.obj()?.get("name") == name })

    /** Sets the initial value of input [name]. */
    fun setInitialValue(name: String, value: Any?): StateMachine =
        copy("inputs", root["inputs"].arr().orEmpty().map { item ->
            val input = item.obj()
            if (input == null || input["name"] != name) item
            else LinkedHashMap(input).apply { put("value", defaultValue(InputType.of(input["type"].str()) ?: InputType.STRING, value)) }
        })

    /**
     * Renames input [from] to [to] everywhere: its definition, every `inputName` of guards and actions, and every `"$from"`
     * reference (guard `compareTo`, action values, URLs, themes).
     */
    fun renameInput(from: String, to: String): StateMachine {
        @Suppress("UNCHECKED_CAST")
        val renamed = rename(root, from, to, inInputs = false) as Map<String, Any?>
        return StateMachine(renamed)
    }

    private fun rename(value: Any?, from: String, to: String, inInputs: Boolean): Any? = when (value) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out ->
            for ((k, v) in value) {
                val key = k.toString()
                out[key] = when {
                    key == "inputName" && v == from -> to
                    key == "name" && inInputs && v == from -> to
                    key == "inputs" && value === root -> (v as? List<*>)?.map { rename(it, from, to, inInputs = true) } ?: v
                    else -> rename(v, from, to, inInputs = false)
                }
            }
        }
        is List<*> -> value.map { rename(it, from, to, inInputs) }
        "$$from" -> "$$to"
        else -> value
    }

    private fun copy(key: String, value: Any?) = StateMachine(LinkedHashMap(root).apply { put(key, value) })

    companion object {
        fun parse(text: CharSequence): StateMachine? = MiniJson.parseOrNull(text).obj()?.let(::StateMachine)

        /** A valid input name: not empty, no `$` (reserved for references) and no leading `@` (the player's own inputs). */
        fun isValidInputName(name: String) = name.isNotBlank() && '$' !in name && !name.startsWith("@") && name == name.trim()

        /** [value] converted to [type], or the type's default (false, 0, ""). */
        fun defaultValue(type: InputType, value: Any?): Any? = when (type) {
            InputType.BOOLEAN -> value as? Boolean ?: (value?.toString()?.toBooleanStrictOrNull() ?: false)
            InputType.NUMERIC -> when (value) {
                is Number -> value
                else -> value?.toString()?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull() } ?: 0L
            }
            InputType.STRING -> value?.toString() ?: ""
            InputType.EVENT -> null
        }
    }
}

/** What a state machine file can refer to outside itself, taken from its `.lottie` archive. */
data class StateMachineContext(
    val animationIds: Set<String>,
    val themeIds: Set<String>,
    /** Marker names per animation id. */
    val markers: Map<String, Set<String>>,
    /** Layer names of all animations (including precompositions). */
    val layerNames: Set<String>,
)

/** A broken reference in a state machine: where it is and what is wrong. */
data class StateMachineProblem(val path: JsonPath, val message: String)

/**
 * Finds references in a state machine that point nowhere: inputs that don't exist or have the wrong type for the guard or action
 * that uses them, unknown states, and (with a [StateMachineContext]) unknown animations, markers, themes and layers.
 */
object StateMachineChecker {

    private val GUARD_TYPES = mapOf("Numeric" to InputType.NUMERIC, "String" to InputType.STRING, "Boolean" to InputType.BOOLEAN, "Event" to InputType.EVENT)
    private val ACTION_TYPES = mapOf(
        "Increment" to InputType.NUMERIC, "Decrement" to InputType.NUMERIC, "SetNumeric" to InputType.NUMERIC,
        "Toggle" to InputType.BOOLEAN, "SetBoolean" to InputType.BOOLEAN,
        "SetString" to InputType.STRING,
        "Fire" to InputType.EVENT,
        "Reset" to null, // any type
    )
    /** Action properties that may hold a `$input` reference, with the type the reference must have (null: any). */
    private val ACTION_REFERENCES = mapOf(
        "Increment" to ("value" to InputType.NUMERIC), "Decrement" to ("value" to InputType.NUMERIC),
        "SetFrame" to ("value" to InputType.NUMERIC), "SetProgress" to ("value" to InputType.NUMERIC),
        "OpenUrl" to ("url" to InputType.STRING), "SetTheme" to ("value" to InputType.STRING),
    )

    fun check(machine: StateMachine, context: StateMachineContext? = null): List<StateMachineProblem> {
        val problems = ArrayList<StateMachineProblem>()
        val inputs = machine.inputs.associateBy { it.name }
        val states = machine.stateNames.toSet()

        machine.root["inputs"].arr().orEmpty().forEachIndexed { i, item ->
            val input = item.obj() ?: return@forEachIndexed
            val name = input["name"].str()
            if (InputType.of(input["type"].str()) == null) problems += StateMachineProblem(listOf("inputs", i, "type"), "Unknown input type '${input["type"]}'")
            if (name != null && machine.inputs.count { it.name == name } > 1) problems += StateMachineProblem(listOf("inputs", i, "name"), "Duplicate input '$name'")
        }

        val initial = machine.initial
        if (initial == null) problems += StateMachineProblem(listOf(), "Missing 'initial' state")
        else if (initial !in states) problems += StateMachineProblem(listOf("initial"), "Unknown state '$initial'")

        fun inputRef(path: JsonPath, name: String?, expected: InputType?, what: String) {
            if (name == null) return
            val input = inputs[name]
            when {
                input == null -> problems += StateMachineProblem(path, "Unknown input '$name'")
                expected != null && input.type != expected ->
                    problems += StateMachineProblem(path, "$what needs a ${expected.json} input, '$name' is ${input.type.json}")
            }
        }

        fun reference(path: JsonPath, value: Any?, expected: InputType?) {
            val s = value.str() ?: return
            if (s.startsWith("$")) inputRef(path, s.removePrefix("$"), expected, "This reference")
        }

        fun actions(list: Any?, base: JsonPath) {
            list.arr()?.forEachIndexed { i, item ->
                val action = item.obj() ?: return@forEachIndexed
                val path = base + i
                val type = action["type"].str()
                if (type in ACTION_TYPES) inputRef(path + "inputName", action["inputName"].str(), ACTION_TYPES[type], "'$type'")
                ACTION_REFERENCES[type]?.let { (key, expected) -> reference(path + key, action[key], expected) }
                if (type == "SetTheme" && context != null) {
                    val theme = action["value"].str()
                    if (theme != null && !theme.startsWith("$") && theme !in context.themeIds) problems += StateMachineProblem(path + "value", "Unknown theme '$theme'")
                }
            }
        }

        machine.root["states"].arr().orEmpty().forEachIndexed { s, item ->
            val state = item.obj() ?: return@forEachIndexed
            val path = listOf<Any>("states", s)
            val animation = state["animation"].str()
            if (context != null && state["type"] == "PlaybackState") {
                if (animation != null && animation.isNotEmpty() && animation !in context.animationIds) {
                    problems += StateMachineProblem(path + "animation", "Unknown animation '$animation'")
                }
                val segment = state["segment"].str()
                val markerSource = animation?.takeIf { it in context.animationIds } ?: context.animationIds.singleOrNull()
                if (segment != null && markerSource != null && segment !in context.markers[markerSource].orEmpty()) {
                    problems += StateMachineProblem(path + "segment", "Unknown marker '$segment' in animation '$markerSource'")
                }
            }
            actions(state["entryActions"], path + "entryActions")
            actions(state["exitActions"], path + "exitActions")
            state["transitions"].arr()?.forEachIndexed { t, tr ->
                val transition = tr.obj() ?: return@forEachIndexed
                val tPath = path + listOf("transitions", t)
                val to = transition["toState"].str()
                if (to != null && to !in states) problems += StateMachineProblem(tPath + "toState", "Unknown state '$to'")
                transition["guards"].arr()?.forEachIndexed { g, gu ->
                    val guard = gu.obj() ?: return@forEachIndexed
                    val gPath = tPath + listOf("guards", g)
                    val type = guard["type"].str()
                    inputRef(gPath + "inputName", guard["inputName"].str(), GUARD_TYPES[type], "A $type guard")
                    reference(gPath + "compareTo", guard["compareTo"], GUARD_TYPES[type])
                }
            }
        }

        machine.root["interactions"].arr().orEmpty().forEachIndexed { i, item ->
            val interaction = item.obj() ?: return@forEachIndexed
            val path = listOf<Any>("interactions", i)
            interaction["stateName"].str()?.let { if (it !in states) problems += StateMachineProblem(path + "stateName", "Unknown state '$it'") }
            val layer = interaction["layerName"].str()
            if (layer != null && context != null && layer !in context.layerNames) problems += StateMachineProblem(path + "layerName", "Unknown layer '$layer'")
            actions(interaction["actions"], path + "actions")
        }
        return problems
    }
}
