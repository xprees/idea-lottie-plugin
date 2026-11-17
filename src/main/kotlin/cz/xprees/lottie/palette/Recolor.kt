package cz.xprees.lottie.palette

import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.num
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.json.str
import cz.xprees.lottie.model.JsonPath

/**
 * One change to a Lottie document: the JSON value at [path] becomes [value], written as [text]. Color components are changed one
 * number at a time (the path ends with the index in the array), so the file keeps its layout; a value that changes shape (an
 * alpha component added) replaces the whole array.
 */
data class ColorEdit(val path: JsonPath, val value: Any?, val text: String)

/** Recoloring every use of a palette color: as direct edits of the animation, or as a dotLottie theme. */
object Recolor {

    /** The edits that make every one of [usages] [to] (each keeps its own number scale; alpha is set where the value has one). */
    fun edits(usages: List<ColorUsage>, to: LottieColor): List<ColorEdit> {
        val out = ArrayList<ColorEdit>()
        for (usage in usages) {
            val stop = usage.gradientStop
            when {
                usage.kind == ColorKind.SOLID -> {
                    val hex = "#%02x%02x%02x".format(to.red, to.green, to.blue)
                    out += ColorEdit(usage.path, hex, MiniJson.write(hex))
                }
                stop != null -> {
                    val base = stop * 4
                    out += number(usage.path + (base + 1), to.r)
                    out += number(usage.path + (base + 2), to.g)
                    out += number(usage.path + (base + 3), to.b)
                    val stops = usage.gradientStops
                    if (usage.color.hasAlpha && stops != null) out += number(usage.path + (stops * 4 + stop * 2 + 1), to.a)
                }
                !usage.color.hasAlpha && to.alpha != 255 -> {
                    // an [r, g, b] value gains an alpha component: replace the whole array
                    val color = to.copy(scale = usage.color.scale, hasAlpha = true)
                    out += ColorEdit(usage.path, components(color), color.toJsonArray())
                }
                else -> {
                    val scale = usage.color.scale
                    out += number(usage.path + 0, to.r * scale)
                    out += number(usage.path + 1, to.g * scale)
                    out += number(usage.path + 2, to.b * scale)
                    if (usage.color.hasAlpha) out += number(usage.path + 3, to.a * scale)
                }
            }
        }
        return out
    }

    /** Applies [edits] to a [MiniJson] tree (its maps and lists are mutable). */
    fun apply(root: Any?, edits: List<ColorEdit>) {
        for (edit in edits) {
            val parent = resolve(root, edit.path.dropLast(1)) ?: continue
            when (val key = edit.path.last()) {
                is String -> @Suppress("UNCHECKED_CAST") (parent as? MutableMap<String, Any?>)?.put(key, edit.value)
                is Int -> @Suppress("UNCHECKED_CAST") (parent as? MutableList<Any?>)?.let { if (key in it.indices) it[key] = edit.value }
            }
        }
    }

    // ---- themes ----------------------------------------------------------------------------------------------------------

    /** The kinds a theme can recolor: color properties, which can become slots. */
    fun themable(usage: ColorUsage) = usage.property != null

    /**
     * The result of recoloring through a theme: the animation with the recolored properties turned into slots (their current
     * value as the slot's default, so the animation looks the same without the theme), the theme with a `Color` rule per slot,
     * how many uses the theme recolors, and the uses a theme can't reach (gradients, solid layers, text), which stay as they are.
     */
    class ThemeResult(val animation: Map<String, Any?>, val theme: Map<String, Any?>, val themed: Int, val skipped: List<ColorUsage>)

    /**
     * Recolors [usages] (of one palette color, [from]) of [animation] to [to] in [theme] (an existing theme document, or null for a
     * new one). [animationId] limits new rules to this animation when the archive has several ([limitToAnimation]).
     */
    fun toTheme(
        animation: Map<String, Any?>,
        usages: List<ColorUsage>,
        from: LottieColor,
        to: LottieColor,
        theme: Map<String, Any?>?,
        animationId: String?,
        limitToAnimation: Boolean,
    ): ThemeResult {
        @Suppress("UNCHECKED_CAST")
        val root = deepCopy(animation) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val themeDoc: MutableMap<String, Any?> = (theme?.let(::deepCopy) as? MutableMap<String, Any?>) ?: linkedMapOf<String, Any?>("rules" to ArrayList<Any?>())
        @Suppress("UNCHECKED_CAST")
        val rules = (themeDoc["rules"] as? MutableList<Any?>) ?: ArrayList<Any?>().also { themeDoc["rules"] = it }
        @Suppress("UNCHECKED_CAST")
        val slots = (root["slots"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { root["slots"] = it }

        val (themable, skipped) = usages.partition(::themable)
        var themed = 0
        for ((propertyPath, uses) in themable.groupBy { it.property!! }) {
            @Suppress("UNCHECKED_CAST")
            val prop = resolve(root, propertyPath) as? MutableMap<String, Any?> ?: continue
            val slotId = uses.first().slotId ?: prop["sid"].str() ?: newSlot(slots, prop, from).also { id ->
                prop["sid"] = id
                if (id !in slots) slots[id] = linkedMapOf("p" to deepCopy(prop.filterKeys { it != "sid" }))
            }
            // the value the rule starts from: the theme's current rule for the slot, else the slot's (or property's) own value
            @Suppress("UNCHECKED_CAST")
            val existing = rules.firstOrNull { (it as? Map<String, Any?>)?.get("id") == slotId } as? MutableMap<String, Any?>
            val slotValue = slots[slotId].obj()?.get("p").obj() ?: prop
            val rule = existing ?: linkedMapOf<String, Any?>("id" to slotId, "type" to "Color").also {
                if (limitToAnimation && animationId != null) it["animations"] = arrayListOf(animationId)
                rules += it
            }
            rule["type"] = "Color"
            val keyframes = (existing?.get("keyframes").arr())
            when {
                existing?.get("value") != null && keyframes == null -> rule["value"] = swap(existing["value"], from, to)
                keyframes != null -> rule["keyframes"] = keyframes.map { kf ->
                    @Suppress("UNCHECKED_CAST")
                    (deepCopy(kf) as MutableMap<String, Any?>).also { it["value"] = swap(it["value"], from, to) }
                }
                slotValue["k"].arr()?.firstOrNull() is Map<*, *> -> {
                    rule.remove("value")
                    rule["keyframes"] = themeKeyframes(slotValue["k"].arr().orEmpty(), from, to)
                }
                else -> {
                    rule.remove("keyframes")
                    rule["value"] = swap(slotValue["k"], from, to)
                }
            }
            themed += uses.size
        }
        if (slots.isEmpty()) root.remove("slots")
        return ThemeResult(root, themeDoc, themed, skipped)
    }

    /**
     * [animation] as [theme] shows it: every property bound to a slot the theme has a `Color` rule for (and the slot itself) gets
     * the rule's value, so the palette shows the colors on screen. Other rule types are left alone.
     */
    fun themed(animation: Any?, theme: Any?, animationId: String?): Any? {
        val rules = theme.obj()?.get("rules").arr().orEmpty().mapNotNull { it.obj() }
            .filter { it["type"] == "Color" && it["id"] is String }
            .filter { rule -> rule["animations"].arr()?.let { animationId == null || animationId in it } ?: true }
        if (rules.isEmpty()) return animation
        val byId = rules.associateBy { it["id"] as String }
        val copy = deepCopy(animation)
        fun value(rule: Map<String, Any?>): Pair<Int, Any?>? {
            rule["value"].arr()?.let { return 0 to ArrayList(it) }
            val kfs = rule["keyframes"].arr() ?: return null
            return 1 to kfs.mapNotNull { it.obj() }.map { kf ->
                linkedMapOf<String, Any?>("t" to kf["frame"], "s" to kf["value"]).apply {
                    kf["inTangent"]?.let { put("i", it) }
                    kf["outTangent"]?.let { put("o", it) }
                    if (kf["hold"] == true) put("h", 1L)
                }
            }
        }
        fun walk(node: Any?) {
            when (node) {
                is MutableMap<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = node as MutableMap<String, Any?>
                    val sid = map["sid"].str()
                    val rule = sid?.let(byId::get)
                    if (rule != null && map.containsKey("k")) value(rule)?.let { (a, k) -> map["a"] = a.toLong(); map["k"] = k }
                    map.values.forEach(::walk)
                }
                is List<*> -> node.forEach(::walk)
            }
        }
        walk(copy)
        // the slot definitions too: the palette lists them
        copy.obj()?.get("slots").obj()?.forEach { (id, slot) ->
            val rule = byId[id] ?: return@forEach
            @Suppress("UNCHECKED_CAST")
            val p = slot.obj()?.get("p") as? MutableMap<String, Any?> ?: return@forEach
            value(rule)?.let { (a, k) -> p["a"] = a.toLong(); p["k"] = k }
        }
        return copy
    }

    /**
     * The manifest with theme [id] listed (named [name] when new) and, when the animation lists its themes, added to that list.
     */
    fun manifestWithTheme(manifest: Map<String, Any?>, id: String, name: String?, animationId: String?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val m = deepCopy(manifest) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val themes = (m["themes"] as? MutableList<Any?>) ?: ArrayList<Any?>().also { m["themes"] = it }
        if (themes.none { it.obj()?.get("id") == id }) {
            themes += linkedMapOf<String, Any?>("id" to id).apply { if (!name.isNullOrBlank() && name != id) put("name", name) }
        }
        m["animations"].arr()?.forEach { a ->
            @Suppress("UNCHECKED_CAST")
            val animation = a as? MutableMap<String, Any?> ?: return@forEach
            if (animation["id"] != animationId) return@forEach
            @Suppress("UNCHECKED_CAST")
            val list = animation["themes"] as? MutableList<Any?> ?: return@forEach
            if (id !in list) list += id
        }
        return m
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------

    private fun number(path: JsonPath, value: Double): ColorEdit {
        val text = LottieColor.formatComponent(value)
        return ColorEdit(path, text.toLongOrNull() ?: text.toDouble(), text)
    }

    private fun components(c: LottieColor): List<Any> = listOf(c.r, c.g, c.b, c.a).map { v ->
        val text = LottieColor.formatComponent(v * c.scale)
        text.toLongOrNull() ?: text.toDouble()
    }

    /** A theme color (0..1, RGBA) for [c]. */
    private fun themeColor(c: LottieColor): List<Any> = listOf(c.r, c.g, c.b, c.a).map { v ->
        val text = LottieColor.formatComponent(v)
        text.toLongOrNull() ?: text.toDouble()
    }

    /** [value] (a color array, any scale) as a 0..1 theme color: [to] when it is [from], else itself. */
    private fun swap(value: Any?, from: LottieColor, to: LottieColor): Any? {
        val numbers = value.arr()?.map { (it as? Number)?.toDouble() ?: return value } ?: return value
        val color = LottieColor.fromComponents(numbers) ?: return value
        return themeColor(if (color.rgba == from.rgba) to else color)
    }

    /** Lottie keyframes (`t`, `s`, `i`, `o`, `h`) as theme keyframes, with [from] swapped for [to]. */
    private fun themeKeyframes(keyframes: List<Any?>, from: LottieColor, to: LottieColor): List<Any?> {
        var previousEnd: Any? = null
        return keyframes.mapNotNull { it.obj() }.mapNotNull { kf ->
            // old files leave the last keyframe's value out and give it as the previous one's "e"
            val start = kf["s"] ?: previousEnd ?: return@mapNotNull null
            previousEnd = kf["e"]
            linkedMapOf<String, Any?>("frame" to (kf["t"].num()?.let { t -> if (t == Math.rint(t)) t.toLong() else t } ?: 0L), "value" to swap(start, from, to)).apply {
                kf["i"]?.let { put("inTangent", deepCopy(it)) }
                kf["o"]?.let { put("outTangent", deepCopy(it)) }
                if (kf["h"].num() == 1.0) put("hold", true)
            }
        }
    }

    /** A slot id for a property of color [color]: `color_rrggbb`, shared by static properties of that color, else numbered. */
    private fun newSlot(slots: Map<String, Any?>, prop: Map<String, Any?>, color: LottieColor): String {
        val base = "color_" + "%02x%02x%02x".format(color.red, color.green, color.blue)
        val animated = prop["k"].arr()?.firstOrNull() is Map<*, *>
        if (!animated) {
            val existing = slots[base].obj()?.get("p").obj()
            if (existing == null || existing["k"] == prop["k"]) return base
        }
        return generateSequence(2) { it + 1 }.map { "${base}_$it" }.first { it !in slots }
    }

    internal fun resolve(root: Any?, path: JsonPath): Any? {
        var node = root
        for (key in path) {
            node = when (key) {
                is String -> (node as? Map<*, *>)?.get(key)
                is Int -> (node as? List<*>)?.getOrNull(key)
                else -> null
            } ?: return null
        }
        return node
    }

    internal fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> value.forEach { (k, v) -> m[k.toString()] = deepCopy(v) } }
        is List<*> -> value.mapTo(ArrayList()) { deepCopy(it) }
        else -> value
    }
}
