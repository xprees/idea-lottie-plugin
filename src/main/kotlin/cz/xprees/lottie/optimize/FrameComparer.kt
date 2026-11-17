package cz.xprees.lottie.optimize

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.model.LottieModel
import cz.xprees.lottie.preview.JcefSupport
import cz.xprees.lottie.preview.PreviewBrowser
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * The result of comparing frames of an animation before and after optimizing: [frames] frames were rendered both ways, and at the
 * worst of them [worstShare] of the pixels differ visibly (0..1). [original] and [optimized] are that frame.
 */
class FrameComparison(
    val frames: Int,
    val worstShare: Double,
    val worstFrame: Int,
    val worstAnimation: String?,
    val original: BufferedImage?,
    val optimized: BufferedImage?,
) {
    /** More than [THRESHOLD] of the pixels of a frame differ: worth a look before writing. */
    val significant: Boolean get() = worstShare > THRESHOLD

    companion object {
        const val THRESHOLD = 0.005
    }
}

/**
 * Renders evenly spaced frames of the original and the optimized file with the preview's player (in a hidden embedded browser,
 * `web/compare.html`) and compares them pixel by pixel.
 */
object FrameComparer {
    /** Frames compared per animation. */
    const val FRAMES = 8

    /** Longest side of the rendered frames. */
    private const val SIZE = 256

    fun isAvailable(): Boolean = JcefSupport.isAvailable()

    /** Blocks until the comparison is done (at most two minutes); call it off the EDT. */
    fun compare(original: ByteArray, optimized: ByteArray, json: Boolean): FrameComparison {
        val (animations, width, height) = layout(original, json)
        val answer = CompletableFuture<Map<String, Any?>>()
        val disposable = Disposer.newDisposable("Lottie frame comparison")
        try {
            ApplicationManager.getApplication().invokeAndWait({
                PreviewBrowser(
                    disposable,
                    initConfig = {
                        mapOf(
                            "wasmUrl" to "${PreviewBrowser.ORIGIN}web/player/dotlottie-player.wasm",
                            "kind" to if (json) "json" else "lottie",
                            "animations" to animations,
                            "frames" to FRAMES,
                            "width" to width,
                            "height" to height,
                        )
                    },
                    dataProvider = { name -> if (name == "a") original else if (name == "b") optimized else null },
                    onMessage = { if (it["type"] == "compare" || it["type"] == "compareError") answer.complete(it) },
                    page = "web/compare.html",
                    offscreen = true,
                )
            }, ModalityState.any())
            val message = answer.get(TIMEOUT_S, TimeUnit.SECONDS)
            if (message["type"] == "compareError") throw IllegalStateException(message["message"] as? String ?: "The frames could not be compared")
            val worst = message["worst"] as? Map<*, *>
            return FrameComparison(
                frames = (message["frames"] as? Number)?.toInt() ?: 0,
                worstShare = (worst?.get("share") as? Number)?.toDouble() ?: 0.0,
                worstFrame = (worst?.get("frame") as? Number)?.toInt() ?: 0,
                // naming the animation only helps when there are several
                worstAnimation = (worst?.get("animation") as? String)?.takeIf { animations.size > 1 },
                original = image(worst?.get("original")),
                optimized = image(worst?.get("optimized")),
            )
        } finally {
            ApplicationManager.getApplication().invokeLater({ Disposer.dispose(disposable) }, ModalityState.any())
        }
    }

    /** The animation ids to compare (none for JSON) and the frame size, fitting the first animation into [SIZE]. */
    private fun layout(original: ByteArray, json: Boolean): Triple<List<String>, Int, Int> {
        val (ids, text) = if (json) {
            emptyList<String>() to original.toString(Charsets.UTF_8)
        } else {
            val archive = DotLottieArchive.read(original)
            archive.manifest.animations.map { it.id } to archive.defaultAnimationId?.let(archive::animationText)
        }
        val info = text?.let { runCatching { LottieModel.read(MiniJson.parse(it).obj()) }.getOrNull() }
        val w = info?.width?.takeIf { it > 0 } ?: SIZE.toDouble()
        val h = info?.height?.takeIf { it > 0 } ?: SIZE.toDouble()
        val scale = SIZE / maxOf(w, h)
        return Triple(ids, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
    }

    private fun image(dataUrl: Any?): BufferedImage? {
        val url = dataUrl as? String ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(url.substringAfter(',')) }.getOrNull() ?: return null
        return runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
    }

    private const val TIMEOUT_S = 120L
}
