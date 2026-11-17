package cz.xprees.lottie.palette

import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.num
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.json.str
import cz.xprees.lottie.model.JsonPath

/** Where a color is used. */
enum class ColorKind(val label: String) {
    FILL("Fill"),
    STROKE("Stroke"),
    GRADIENT("Gradient"),
    SOLID("Solid layer"),
    TEXT_FILL("Text fill"),
    TEXT_STROKE("Text stroke"),
    EFFECT("Effect"),
    SLOT("Slot"),
}

/**
 * One color value in a Lottie document.
 *
 * [path] points at the JSON value holding the color: an `[r, g, b(, a)]` array, a `"#rrggbb"` string (solid layers), or for
 * gradients the whole flat stop array, with [gradientStop] telling which stop. [editable] is false for gradients, whose stops can't
 * be written back one at a time.
 */
data class ColorUsage(
    val color: LottieColor,
    val kind: ColorKind,
    val path: JsonPath,
    /** Name of the layer (and shape) the color belongs to, for tooltips. */
    val owner: String?,
    val animated: Boolean = false,
    val gradientStop: Int? = null,
    val slotId: String? = null,
    /** The number of stops of the gradient ([gradientStop]'s `p`), to find the stop's alpha. */
    val gradientStops: Int? = null,
    /** The color property (`{"a", "k", "sid"}`) holding the color, for fills, strokes, effect colors and slots; null otherwise. */
    val property: JsonPath? = null,
) {
    val editable get() = gradientStop == null
}

/** One distinct color of a document with every place it is used. */
data class PaletteEntry(val color: LottieColor, val usages: List<ColorUsage>) {
    val count get() = usages.size
    val kinds get() = usages.map { it.kind }.distinct()
}

/**
 * Collects the colors of a Lottie animation ([cz.xprees.lottie.json.MiniJson] tree): fills, strokes and gradients in shape
 * layers (including groups and precomposition assets), solid layer colors, text fill/stroke colors, color effect controls and
 * the color slots they reference.
 */
object PaletteScanner {

    fun scan(root: Any?): List<ColorUsage> {
        val r = root.obj() ?: return emptyList()
        val out = ArrayList<ColorUsage>()
        val slotIds = LinkedHashSet<String>()
        r["layers"].arr()?.let { layers(it, listOf("layers"), out, slotIds) }
        r["assets"].arr()?.forEachIndexed { i, asset ->
            asset.obj()?.get("layers").arr()?.let { layers(it, listOf("assets", i, "layers"), out, slotIds) }
        }
        // Slots referenced by color properties: their value overrides the property, so they are part of the palette too
        r["slots"].obj()?.forEach { (id, slot) ->
            if (id !in slotIds) return@forEach
            val prop = slot.obj()?.get("p").obj() ?: return@forEach
            colorProperty(prop, listOf("slots", id, "p"), ColorKind.SLOT, "Slot $id", out, slotIds, slotId = id)
        }
        return out
    }

    fun palette(usages: List<ColorUsage>): List<PaletteEntry> =
        usages.groupBy { it.color.rgba }.values
            .map { PaletteEntry(it.first().color, it) }
            .sortedWith(compareByDescending<PaletteEntry> { it.count }.thenBy { it.color.hex })

    /** The paths of every editable `[r, g, b(, a)]` color array (for gutter swatches). */
    fun editableArrayPaths(usages: List<ColorUsage>): Set<JsonPath> =
        usages.filter { it.editable && it.kind != ColorKind.SOLID }.mapTo(HashSet()) { it.path }

    private fun layers(list: List<Any?>, base: JsonPath, out: MutableList<ColorUsage>, slots: MutableSet<String>) {
        list.forEachIndexed { i, item ->
            val layer = item.obj() ?: return@forEachIndexed
            val path = base + i
            val name = layer["nm"].str()
            when (layer["ty"].num()?.toInt()) {
                1 -> layer["sc"].str()?.let { hex ->
                    LottieColor.fromHex(hex)?.let { out += ColorUsage(it, ColorKind.SOLID, path + "sc", name) }
                }
                4 -> layer["shapes"].arr()?.let { shapes(it, path + "shapes", name, out, slots) }
                5 -> text(layer, path, name, out)
            }
            layer["ef"].arr()?.let { effects(it, path + "ef", name, out, slots) }
        }
    }

    private fun shapes(list: List<Any?>, base: JsonPath, owner: String?, out: MutableList<ColorUsage>, slots: MutableSet<String>) {
        list.forEachIndexed { i, item ->
            val shape = item.obj() ?: return@forEachIndexed
            val path = base + i
            val label = listOfNotNull(owner, shape["nm"].str()).joinToString(" › ").ifEmpty { null }
            when (shape["ty"].str()) {
                "gr" -> shape["it"].arr()?.let { shapes(it, path + "it", label, out, slots) }
                "fl" -> shape["c"].obj()?.let { colorProperty(it, path + "c", ColorKind.FILL, label, out, slots) }
                "st" -> shape["c"].obj()?.let { colorProperty(it, path + "c", ColorKind.STROKE, label, out, slots) }
                "gf", "gs" -> shape["g"].obj()?.let { gradient(it, path + "g", label, out) }
            }
        }
    }

    private fun text(layer: Map<String, Any?>, path: JsonPath, owner: String?, out: MutableList<ColorUsage>) {
        val keyframes = layer["t"].obj()?.get("d").obj()?.get("k").arr() ?: return
        keyframes.forEachIndexed { i, kf ->
            val doc = kf.obj()?.get("s").obj() ?: return@forEachIndexed
            val docPath = path + listOf("t", "d", "k", i, "s")
            for ((key, kind) in listOf("fc" to ColorKind.TEXT_FILL, "sc" to ColorKind.TEXT_STROKE)) {
                val color = doc[key].numbers()?.let(LottieColor::fromComponents) ?: continue
                out += ColorUsage(color, kind, docPath + key, owner, animated = keyframes.size > 1)
            }
        }
    }

    private fun effects(list: List<Any?>, base: JsonPath, owner: String?, out: MutableList<ColorUsage>, slots: MutableSet<String>) {
        list.forEachIndexed { i, item ->
            val effect = item.obj() ?: return@forEachIndexed
            val label = listOfNotNull(owner, effect["nm"].str()).joinToString(" › ").ifEmpty { null }
            effect["ef"].arr()?.forEachIndexed { j, param ->
                val p = param.obj() ?: return@forEachIndexed
                // effect value type 2 = color
                if (p["ty"].num()?.toInt() == 2) {
                    p["v"].obj()?.let { colorProperty(it, base + listOf(i, "ef", j, "v"), ColorKind.EFFECT, label, out, slots) }
                }
            }
        }
    }

    /** A color property: `{"a": 0, "k": [r, g, b, a]}` or animated `{"a": 1, "k": [{"s": [...], "e": [...]}, ...]}`. */
    private fun colorProperty(
        prop: Map<String, Any?>,
        path: JsonPath,
        kind: ColorKind,
        owner: String?,
        out: MutableList<ColorUsage>,
        slots: MutableSet<String>,
        slotId: String? = null,
    ) {
        prop["sid"].str()?.let { slots += it }
        val k = prop["k"]
        val static = k.numbers()?.let(LottieColor::fromComponents)
        if (static != null) {
            out += ColorUsage(static, kind, path + "k", owner, slotId = slotId, property = path)
            return
        }
        k.arr()?.forEachIndexed { i, kf ->
            val keyframe = kf.obj() ?: return@forEachIndexed
            for (key in listOf("s", "e")) {
                val color = keyframe[key].numbers()?.let(LottieColor::fromComponents) ?: continue
                out += ColorUsage(color, kind, path + listOf("k", i, key), owner, animated = true, slotId = slotId, property = path)
            }
        }
    }

    /** Gradient `{"p": stopCount, "k": {"a": 0, "k": [offset, r, g, b, ..., offset, alpha, ...]}}`. */
    private fun gradient(g: Map<String, Any?>, path: JsonPath, owner: String?, out: MutableList<ColorUsage>) {
        val stops = g["p"].num()?.toInt() ?: return
        val prop = g["k"].obj() ?: return
        val k = prop["k"]
        fun read(values: List<Double>, valuePath: JsonPath, animated: Boolean) {
            for (s in 0 until stops) {
                val base = s * 4
                if (base + 3 >= values.size) break
                val alphaIndex = stops * 4 + s * 2 + 1
                val alpha = values.getOrNull(alphaIndex)
                val comps = listOf(values[base + 1], values[base + 2], values[base + 3]) + listOfNotNull(alpha)
                val color = LottieColor.fromComponents(comps) ?: continue
                out += ColorUsage(color, ColorKind.GRADIENT, valuePath, owner, animated, gradientStop = s, gradientStops = stops)
            }
        }
        val static = k.numbers()
        if (static != null) {
            read(static, path + listOf("k", "k"), false)
            return
        }
        k.arr()?.forEachIndexed { i, kf ->
            kf.obj()?.get("s").numbers()?.let { read(it, path + listOf("k", "k", i, "s"), true) }
        }
    }

    private fun Any?.numbers(): List<Double>? {
        val list = arr() ?: return null
        if (list.isEmpty()) return null
        return list.map { (it as? Number)?.toDouble() ?: return null }
    }
}
