package cz.xprees.lottie.optimize

import cz.xprees.lottie.json.MiniJson
import kotlin.math.pow
import kotlin.math.roundToLong

/** What [LottieOptimizer] does. Every step is independent; the defaults are the lossless cleanup and 3-decimal rounding. */
data class OptimizeOptions(
    /** Lossless: editor-only data, unused assets, empty groups, never-changing keyframes, default values, duplicate images. */
    val cleanup: Boolean = true,
    /** Decimals numbers are rounded to (some kinds of values keep at least [MIN_FINE_DECIMALS]); null leaves them as they are. */
    val precision: Int? = 3,
    /** Removes hidden layers and layers that are never on screen (see [LottieOptimizer.removeHiddenLayers]). */
    val removeHiddenLayers: Boolean = false,
    val images: ImageOptions = ImageOptions(),
) {
    companion object {
        /** Keyframe times, frame ranges, colours and easing handles are not rounded below this. */
        const val MIN_FINE_DECIMALS = 3
    }
}

/**
 * The size of an animation's JSON after each step, cumulatively. [layersRemoved] names the layers the hidden-layer step removes,
 * [imagesChanged] how many images the image step re-encoded or resized.
 */
class JsonOptimization(
    val root: MutableMap<String, Any?>,
    val layersRemoved: List<String>,
    val imagesChanged: Int,
) {
    val json: String get() = MiniJson.write(root)
}

/**
 * Makes Lottie JSON smaller. Works on the parsed document ([MiniJson]) and keeps it a valid Lottie animation: markers, slots
 * (`sid`), layer names (`nm`), layer indexes and parents, and every name or asset id in [protectedNames] (what a dotLottie state
 * machine or theme refers to) are never removed.
 */
object LottieOptimizer {

    fun optimize(text: CharSequence, options: OptimizeOptions, protectedNames: Set<String> = emptySet(), webp: WebpEncoder? = null): JsonOptimization =
        optimize(MiniJson.parse(text), options, protectedNames, webp)

    fun optimize(parsed: Any?, options: OptimizeOptions, protectedNames: Set<String> = emptySet(), webp: WebpEncoder? = null): JsonOptimization {
        @Suppress("UNCHECKED_CAST")
        val root = mutableCopy(parsed) as? MutableMap<String, Any?> ?: throw IllegalArgumentException("Not a Lottie animation")
        val expressions = expressionText(root)
        var removed = emptyList<String>()
        var images = 0
        if (options.cleanup) cleanup(root, expressions, protectedNames)
        if (options.removeHiddenLayers) {
            removed = removeHiddenLayers(root, protectedNames + namesIn(expressions))
            if (options.cleanup) removeUnusedAssets(root, protectedNames)
        }
        if (options.images.enabled) images = ImageCompressor.compressEmbedded(root, options.images, webp)
        options.precision?.let { round(root, it) }
        return JsonOptimization(root, removed, images)
    }

    // ---- lossless cleanup -------------------------------------------------------------------------------------------------

    /** Editor-only keys: After Effects match names and property indexes, which only expressions use. */
    private val EDITOR_KEYS = setOf("mn", "ix", "cix")

    /** Keys whose default value can be left out: every runtime reads a missing key as this value. */
    private val DEFAULTS = mapOf<String, Any>("hd" to false, "ddd" to 0L, "bm" to 0L, "ao" to 0L)

    internal fun cleanup(root: MutableMap<String, Any?>, expressions: String, protectedNames: Set<String>) {
        root.remove("meta")
        // expressions find properties by match name and index: keep them when there are any
        val stripEditorKeys = expressions.isEmpty()
        walk(root) { map, inEffects ->
            if (stripEditorKeys && !inEffects) EDITOR_KEYS.forEach { map.remove(it) }
            for ((key, default) in DEFAULTS) if (map.containsKey(key) && sameValue(map[key], default)) map.remove(key)
            collapseStaticKeyframes(map)
            removeEmptyGroups(map)
        }
        dedupeEmbeddedImages(root, protectedNames)
        removeUnusedAssets(root, protectedNames)
    }

    /** `{"a": 1, "k": [keyframes]}` whose keyframes all hold the same value becomes `{"a": 0, "k": value}`. */
    private fun collapseStaticKeyframes(map: MutableMap<String, Any?>) {
        if (!sameValue(map["a"], 1L) || map.containsKey("x")) return
        val keyframes = map["k"] as? List<*> ?: return
        if (keyframes.isEmpty() || keyframes.any { it !is Map<*, *> || !it.containsKey("t") }) return
        val values = keyframes.flatMap { kf -> listOfNotNull((kf as Map<*, *>)["s"], kf["e"]) }
        val first = values.firstOrNull() ?: return
        if (values.any { !deepEquals(it, first) }) return
        map["a"] = 0L
        map["k"] = if (first is List<*> && first.size == 1) first[0] else first
    }

    /** Shape groups (`"ty": "gr"`) left with nothing but their transform draw nothing. */
    private fun removeEmptyGroups(map: MutableMap<String, Any?>) {
        for (key in listOf("shapes", "it")) {
            @Suppress("UNCHECKED_CAST")
            val items = map[key] as? MutableList<Any?> ?: continue
            items.removeAll { item ->
                val shape = item as? Map<*, *> ?: return@removeAll false
                shape["ty"] == "gr" && (shape["it"] as? List<*>).orEmpty().all { (it as? Map<*, *>)?.get("ty") == "tr" }
            }
        }
    }

    /** Image assets with the same embedded data are merged; layers point at the one kept. */
    private fun dedupeEmbeddedImages(root: MutableMap<String, Any?>, protectedNames: Set<String>) {
        @Suppress("UNCHECKED_CAST")
        val assets = root["assets"] as? MutableList<Any?> ?: return
        val firstByData = HashMap<String, String>()
        val replaced = HashMap<String, String>()
        for (asset in assets) {
            val a = asset as? Map<*, *> ?: continue
            val id = a["id"] as? String ?: continue
            val data = (a["p"] as? String)?.takeIf { it.startsWith("data:") && a["layers"] == null && a["sid"] == null } ?: continue
            val kept = firstByData.putIfAbsent(data + "|" + a["w"] + "x" + a["h"], id) ?: continue
            if (id !in protectedNames) replaced[id] = kept
        }
        if (replaced.isEmpty()) return
        assets.removeAll { (it as? Map<*, *>)?.get("id") in replaced.keys }
        forEachLayer(root) { layer -> (layer["refId"] as? String)?.let { ref -> replaced[ref]?.let { layer["refId"] = it } } }
    }

    /** Assets no layer refers to (directly or through precompositions) are dropped, unless they hold a slot or are protected. */
    internal fun removeUnusedAssets(root: MutableMap<String, Any?>, protectedNames: Set<String>) {
        @Suppress("UNCHECKED_CAST")
        val assets = root["assets"] as? MutableList<Any?> ?: return
        val byId = assets.mapNotNull { it as? Map<*, *> }.associateBy { it["id"] as? String }
        val used = HashSet<String>()
        fun visit(layers: Any?) {
            (layers as? List<*>)?.forEach { layer ->
                val ref = (layer as? Map<*, *>)?.get("refId") as? String ?: return@forEach
                if (used.add(ref)) visit(byId[ref]?.get("layers"))
            }
        }
        visit(root["layers"])
        // a slot may swap in any asset; protected ids are named by themes or state machines
        assets.filter { (it as? Map<*, *>)?.get("sid") != null || (it as? Map<*, *>)?.get("id") in protectedNames }
            .forEach { asset -> (asset as Map<*, *>)["id"]?.let { id -> if (used.add(id as String)) visit(asset["layers"]) } }
        assets.removeAll { val id = (it as? Map<*, *>)?.get("id"); id is String && id !in used }
    }

    // ---- hidden layers ------------------------------------------------------------------------------------------------------

    /**
     * Removes layers that never show: hidden ones (`hd`), root layers outside the composition's frame range and layers whose
     * opacity is always 0. Kept regardless: parents of other layers, track matte sources (`td`, or the `tp` of another layer),
     * and layers whose name is in [protectedNames] or appears in an expression. Returns the names of the removed layers.
     */
    internal fun removeHiddenLayers(root: MutableMap<String, Any?>, protectedNames: Set<String>): List<String> {
        val removed = ArrayList<String>()
        val compIn = (root["ip"] as? Number)?.toDouble()
        val compOut = (root["op"] as? Number)?.toDouble()
        fun prune(layers: MutableList<Any?>, isRoot: Boolean) {
            while (true) {
                val maps = layers.mapNotNull { it as? Map<*, *> }
                val referenced = maps.flatMap { listOfNotNull(it["parent"], it["tp"]) }.map { (it as? Number)?.toLong() }.toSet()
                val victim = maps.firstOrNull { layer ->
                    val name = layer["nm"] as? String
                    val kept = (layer["ind"] as? Number)?.toLong() in referenced || sameValue(layer["td"], 1L) || name in protectedNames
                    !kept && (sameValue(layer["hd"], true) || isRoot && outside(layer, compIn, compOut) || alwaysTransparent(layer))
                } ?: break
                layers.remove(victim)
                removed += victim["nm"] as? String ?: "#${victim["ind"]}"
            }
        }
        @Suppress("UNCHECKED_CAST")
        (root["layers"] as? MutableList<Any?>)?.let { prune(it, isRoot = true) }
        @Suppress("UNCHECKED_CAST")
        (root["assets"] as? List<Any?>)?.forEach { asset -> ((asset as? Map<*, *>)?.get("layers") as? MutableList<Any?>)?.let { prune(it, isRoot = false) } }
        return removed
    }

    private fun outside(layer: Map<*, *>, compIn: Double?, compOut: Double?): Boolean {
        val ip = (layer["ip"] as? Number)?.toDouble() ?: return false
        val op = (layer["op"] as? Number)?.toDouble() ?: return false
        return compIn != null && compOut != null && (op <= compIn || ip >= compOut)
    }

    private fun alwaysTransparent(layer: Map<*, *>): Boolean {
        val opacity = ((layer["ks"] as? Map<*, *>)?.get("o") as? Map<*, *>) ?: return false
        if (opacity.containsKey("x")) return false
        return when (val k = opacity["k"]) {
            is Number -> !sameValue(opacity["a"], 1L) && k.toDouble() == 0.0
            else -> false
        }
    }

    // ---- precision ------------------------------------------------------------------------------------------------------------

    /** Keys whose numbers are times or frame ranges. */
    private val TIME_KEYS = setOf("t", "ip", "op", "st", "sr", "fr", "tm")

    /** Properties holding colours (0..1 components) or gradient stops. */
    private val COLOR_KEYS = setOf("c", "g", "fc", "sc")

    internal fun round(root: Any?, decimals: Int) {
        val fine = maxOf(decimals, OptimizeOptions.MIN_FINE_DECIMALS)
        fun visit(node: Any?, places: Int): Any? = when (node) {
            is Double -> roundNumber(node, places)
            is Float -> roundNumber(node.toDouble(), places)
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = node as MutableMap<String, Any?>
                for (entry in map.entries) {
                    val value = entry.value
                    val p = when {
                        places > decimals -> places
                        entry.key in TIME_KEYS -> fine
                        entry.key in COLOR_KEYS -> fine
                        entry.key == "ef" -> fine // effect values: colours, angles and sliders read by expressions
                        (entry.key == "i" || entry.key == "o") && value is Map<*, *> -> fine // keyframe easing handles
                        else -> places
                    }
                    entry.setValue(visit(value, p))
                }
                map
            }
            is MutableList<*> -> {
                @Suppress("UNCHECKED_CAST")
                val list = node as MutableList<Any?>
                for (i in list.indices) list[i] = visit(list[i], places)
                list
            }
            else -> node
        }
        visit(root, decimals)
    }

    private fun roundNumber(value: Double, places: Int): Any {
        if (value.isNaN() || value.isInfinite()) return value
        val scale = 10.0.pow(places)
        val rounded = (value * scale).roundToLong() / scale
        return if (rounded == Math.rint(rounded) && kotlin.math.abs(rounded) < 1e15) rounded.toLong() else rounded
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------------

    /** The text of every expression in the animation (`"x"` strings), empty without any. */
    internal fun expressionText(root: Any?): String = buildString {
        fun visit(node: Any?) {
            when (node) {
                is Map<*, *> -> node.forEach { (k, v) -> if (k == "x" && v is String) append(v).append('\n') else visit(v) }
                is List<*> -> node.forEach(::visit)
            }
        }
        visit(root)
    }

    /** Quoted strings in [text] (layer names expressions refer to, like `thisComp.layer("Hand")`). */
    private fun namesIn(text: String): Set<String> = Regex("\"([^\"\\\\]*)\"|'([^'\\\\]*)'").findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()

    /** Visits every map; `inEffects` is true inside effect lists (`ef`), whose indexes and match names expressions may use. */
    private fun walk(node: Any?, inEffects: Boolean = false, action: (MutableMap<String, Any?>, Boolean) -> Unit) {
        when (node) {
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = node as MutableMap<String, Any?>
                for ((key, value) in map.entries.toList()) walk(value, inEffects || key == "ef", action)
                action(map, inEffects)
            }
            is List<*> -> node.forEach { walk(it, inEffects, action) }
        }
    }

    private fun forEachLayer(root: Map<String, Any?>, action: (MutableMap<String, Any?>) -> Unit) {
        fun layers(list: Any?) = (list as? List<*>)?.forEach { layer ->
            @Suppress("UNCHECKED_CAST")
            (layer as? MutableMap<String, Any?>)?.let(action)
        }
        layers(root["layers"])
        (root["assets"] as? List<*>)?.forEach { layers((it as? Map<*, *>)?.get("layers")) }
    }

    private fun sameValue(value: Any?, expected: Any): Boolean = when {
        value is Number && expected is Number -> value.toDouble() == expected.toDouble()
        else -> value == expected
    }

    private fun deepEquals(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is Map<*, *> && b is Map<*, *> -> a.size == b.size && a.all { (k, v) -> b.containsKey(k) && deepEquals(v, b[k]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { deepEquals(a[it], b[it]) }
        else -> a == b
    }

    /** A copy of a parsed document made of mutable maps and lists. */
    internal fun mutableCopy(node: Any?): Any? = when (node) {
        is Map<*, *> -> LinkedHashMap<String, Any?>(node.size).also { copy -> node.forEach { (k, v) -> copy[k.toString()] = mutableCopy(v) } }
        is List<*> -> ArrayList<Any?>(node.size).also { copy -> node.forEach { copy += mutableCopy(it) } }
        else -> node
    }
}
