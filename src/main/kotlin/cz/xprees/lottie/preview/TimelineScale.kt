package cz.xprees.lottie.preview

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Maps frames to pixels for the timeline. Frames run from 0 to [lastFrame] (the player's frame numbers, relative to the
 * animation's in point) across a track [width] pixels wide that starts at [left].
 */
internal class TimelineScale(val left: Int, val width: Int, val lastFrame: Double) {

    private val span get() = lastFrame.coerceAtLeast(1.0)

    fun x(frame: Double): Int = left + (frame.coerceIn(0.0, span) / span * width).roundToInt()

    /** The frame at pixel [x]; whole frames unless [exact], clamped to the timeline. */
    fun frame(x: Int, exact: Boolean = false): Double {
        val f = ((x - left).toDouble() / width.coerceAtLeast(1) * span).coerceIn(0.0, lastFrame.coerceAtLeast(0.0))
        return if (exact) f else Math.round(f).toDouble().coerceAtMost(lastFrame.coerceAtLeast(0.0))
    }

    /** Frame distance between labelled ticks so labels are at least [minPixels] apart: 1, 2, 5, 10, 20, 50, ... */
    fun tickStep(minPixels: Int): Int {
        val pixelsPerFrame = width / span
        if (pixelsPerFrame <= 0) return 1
        val needed = ceil(minPixels / pixelsPerFrame).toInt().coerceAtLeast(1)
        var magnitude = 1
        while (true) {
            for (m in intArrayOf(1, 2, 5)) if (m * magnitude >= needed) return m * magnitude
            magnitude *= 10
        }
    }
}
