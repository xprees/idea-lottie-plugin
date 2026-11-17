package cz.xprees.lottie.model

import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.num
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.json.str

/** A JSON location: object keys ([String]) and array indices ([Int]) from the document root. */
typealias JsonPath = List<Any>

data class LottieLayer(
    val path: JsonPath,
    /** Position in its layer list (0 = topmost). */
    val position: Int,
    val ind: Long?,
    val name: String?,
    val type: Int?,
    val inPoint: Double?,
    val outPoint: Double?,
    val parent: Long?,
    /** `hd`: not rendered. */
    val hidden: Boolean = false,
) {
    val typeName: String get() = LottieNames.layerType(type)
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: "$typeName ${position + 1}"
}

data class LottieMarker(val name: String, val start: Double, val duration: Double) {
    val end get() = start + duration
}

/** The header of a Lottie animation plus its root layers and markers. */
data class LottieInfo(
    val version: String?,
    val name: String?,
    val width: Double,
    val height: Double,
    val frameRate: Double,
    val inPoint: Double,
    val outPoint: Double,
    val layers: List<LottieLayer>,
    val markers: List<LottieMarker>,
    val slotIds: List<String>,
    val assetCount: Int,
) {
    val frames get() = (outPoint - inPoint).coerceAtLeast(0.0)
    val durationSeconds get() = if (frameRate > 0) frames / frameRate else 0.0
}

object LottieModel {

    fun read(text: CharSequence): LottieInfo? = read(MiniJson.parseOrNull(text))

    fun read(root: Any?): LottieInfo? {
        val r = root.obj() ?: return null
        val layers = r["layers"].arr().orEmpty().mapIndexedNotNull { index, item ->
            val l = item.obj() ?: return@mapIndexedNotNull null
            LottieLayer(
                path = listOf("layers", index),
                position = index,
                ind = (l["ind"] as? Number)?.toLong(),
                name = l["nm"].str(),
                type = l["ty"].num()?.toInt(),
                inPoint = l["ip"].num(),
                outPoint = l["op"].num(),
                parent = (l["parent"] as? Number)?.toLong(),
                hidden = l["hd"] == true,
            )
        }
        val markers = r["markers"].arr().orEmpty().mapNotNull { item ->
            val m = item.obj() ?: return@mapNotNull null
            val start = m["tm"].num() ?: return@mapNotNull null
            MarkerNames.parse(m["cm"])?.let { LottieMarker(it, start, m["dr"].num() ?: 0.0) }
        }
        return LottieInfo(
            version = r["v"].str(),
            name = r["nm"].str(),
            width = r["w"].num() ?: 0.0,
            height = r["h"].num() ?: 0.0,
            frameRate = r["fr"].num() ?: 0.0,
            inPoint = r["ip"].num() ?: 0.0,
            outPoint = r["op"].num() ?: 0.0,
            layers = layers,
            markers = markers,
            slotIds = r["slots"].obj()?.keys?.toList().orEmpty(),
            assetCount = r["assets"].arr()?.size ?: 0,
        )
    }
}

internal object MarkerNames {
    /** Marker comments are plain names, or (After Effects + Bodymovin) a JSON string like `{"name":"intro"}`. */
    fun parse(cm: Any?): String? {
        val s = cm.str() ?: return null
        if (s.startsWith("{")) MiniJson.parseOrNull(s).obj()?.get("name").str()?.let { return it }
        return s.ifBlank { null }
    }
}

/** Readable names for Lottie's numeric and short codes. */
object LottieNames {
    private val LAYER_TYPES = mapOf(
        0 to "Precomposition", 1 to "Solid", 2 to "Image", 3 to "Null", 4 to "Shape", 5 to "Text", 6 to "Audio",
        7 to "Video placeholder", 8 to "Image sequence", 9 to "Video", 10 to "Image placeholder", 11 to "Guide",
        12 to "Adjustment", 13 to "Camera", 14 to "Light", 15 to "Data",
    )

    private val SHAPE_TYPES = mapOf(
        "gr" to "Group", "rc" to "Rectangle", "el" to "Ellipse", "sr" to "Polystar", "sh" to "Path", "fl" to "Fill",
        "st" to "Stroke", "gf" to "Gradient fill", "gs" to "Gradient stroke", "tr" to "Transform", "tm" to "Trim paths",
        "rp" to "Repeater", "rd" to "Rounded corners", "mm" to "Merge paths", "tw" to "Twist", "op" to "Offset path",
        "pb" to "Pucker / bloat", "zz" to "Zig zag", "no" to "No style",
    )

    private val BLEND_MODES = listOf(
        "Normal", "Multiply", "Screen", "Overlay", "Darken", "Lighten", "Color dodge", "Color burn", "Hard light", "Soft light",
        "Difference", "Exclusion", "Hue", "Saturation", "Color", "Luminosity", "Add", "Hard mix",
    )

    private val MATTE_MODES = listOf("None", "Alpha", "Inverted alpha", "Luma", "Inverted luma")

    fun layerType(type: Int?): String = type?.let { LAYER_TYPES[it] } ?: "Layer"

    fun layerTypeOrNull(type: Int?): String? = type?.let { LAYER_TYPES[it] }

    fun shapeType(ty: String?): String? = ty?.let { SHAPE_TYPES[it] }

    fun blendMode(bm: Int?): String? = bm?.let { BLEND_MODES.getOrNull(it) }

    fun matteMode(tt: Int?): String? = tt?.let { MATTE_MODES.getOrNull(it) }
}
