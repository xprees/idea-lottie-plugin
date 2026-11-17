package cz.xprees.lottie.palette

import java.util.Locale
import kotlin.math.roundToInt

/**
 * A color as Lottie stores it: components in 0..1 (`[r, g, b]` or `[r, g, b, a]`). Some old exporters wrote 0..255; [scale]
 * remembers which, so a color edited in the IDE is written back in the file's own scale.
 */
data class LottieColor(val r: Double, val g: Double, val b: Double, val a: Double = 1.0, val scale: Double = 1.0, val hasAlpha: Boolean = false) {

    val red get() = to8(r)
    val green get() = to8(g)
    val blue get() = to8(b)
    val alpha get() = to8(a)

    /** `#rrggbb`, or `#rrggbbaa` when not fully opaque. */
    val hex: String
        get() = if (alpha == 255) "#%02x%02x%02x".format(red, green, blue) else "#%02x%02x%02x%02x".format(red, green, blue, alpha)

    /** The 8-bit RGBA value colors are grouped by in the palette. */
    val rgba: Int get() = (red shl 24) or (green shl 16) or (blue shl 8) or alpha

    private fun to8(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()

    /** The JSON array text for this color in the file's scale, e.g. `[1, 0.5, 0, 1]`. */
    fun toJsonArray(): String {
        val parts = mutableListOf(r, g, b)
        if (hasAlpha) parts += a
        return parts.joinToString(", ", "[", "]") { formatComponent(it * scale) }
    }

    companion object {
        /** Reads `[r, g, b]` / `[r, g, b, a]`; null when the values aren't a color. */
        fun fromComponents(values: List<Double>): LottieColor? {
            if (values.size !in 3..4) return null
            if (values.any { it.isNaN() || it < 0 }) return null
            val scale = if (values.take(3).any { it > 1.0 }) 255.0 else 1.0
            if (values.any { it > scale }) return null
            val v = values.map { it / scale }
            return LottieColor(v[0], v[1], v[2], v.getOrElse(3) { 1.0 }, scale, values.size == 4)
        }

        /** Parses `#rgb`, `#rrggbb` or `#rrggbbaa` (solid layer colors and theme rules). */
        fun fromHex(text: String): LottieColor? {
            val h = text.trim().removePrefix("#")
            val full = when (h.length) {
                3 -> h.map { "$it$it" }.joinToString("")
                6, 8 -> h
                else -> return null
            }
            val n = full.toLongOrNull(16) ?: return null
            val hasAlpha = full.length == 8
            val rgb = if (hasAlpha) n shr 8 else n
            val a = if (hasAlpha) (n and 0xFF) / 255.0 else 1.0
            return LottieColor(((rgb shr 16) and 0xFF) / 255.0, ((rgb shr 8) and 0xFF) / 255.0, (rgb and 0xFF) / 255.0, a, 1.0, hasAlpha)
        }

        fun fromRgba8(red: Int, green: Int, blue: Int, alpha: Int, like: LottieColor? = null) = LottieColor(
            red / 255.0, green / 255.0, blue / 255.0, alpha / 255.0,
            scale = like?.scale ?: 1.0,
            hasAlpha = (like?.hasAlpha ?: false) || alpha != 255,
        )

        /** Shortest decimal for a component: at most 4 fraction digits, no trailing zeros (`1`, `0.5`, `0.2353`). */
        fun formatComponent(v: Double): String {
            val rounded = (v * 10_000).roundToInt() / 10_000.0
            if (rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
            return String.format(Locale.ROOT, "%.4f", rounded).trimEnd('0').trimEnd('.')
        }
    }
}
