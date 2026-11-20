package cz.xprees.lottie.preview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * What [FrameRenderer] renders: the animation [data] (Lottie JSON text bytes when [json], else a `.lottie` archive with
 * [animationId]), the player [frames] (counted from the animation's in point) at [width] × [height], over [background]
 * (`#rrggbb` or `#rrggbbaa`; transparent when null) with dotLottie theme [themeId].
 */
class FrameRenderRequest(
    val data: ByteArray,
    val json: Boolean,
    val animationId: String?,
    val frames: List<Double>,
    val width: Int,
    val height: Int,
    val background: String? = null,
    val themeId: String? = null,
)

/** Renders frames to PNG with the preview's player in a hidden embedded browser (`web/render.html`). */
object FrameRenderer {

    fun isAvailable(): Boolean = JcefSupport.isAvailable()

    /** The PNG bytes of each frame. Blocks until done (at most a minute); call it off the EDT and outside read actions. */
    fun render(request: FrameRenderRequest): List<ByteArray> {
        val answer = CompletableFuture<Map<String, Any?>>()
        val disposable = Disposer.newDisposable("Lottie frame render")
        try {
            ApplicationManager.getApplication().invokeAndWait({
                PreviewBrowser(
                    disposable,
                    initConfig = {
                        mapOf(
                            "wasmUrl" to "${PreviewBrowser.ORIGIN}web/player/dotlottie-player.wasm",
                            "kind" to if (request.json) "json" else "lottie",
                            "animationId" to request.animationId,
                            "themeId" to request.themeId,
                            "background" to request.background,
                            "frames" to request.frames,
                            "width" to request.width,
                            "height" to request.height,
                        )
                    },
                    dataProvider = { name -> if (name == "animation") request.data else null },
                    onMessage = { if (it["type"] == "rendered" || it["type"] == "renderError") answer.complete(it) },
                    page = "web/render.html",
                    offscreen = true,
                )
            }, ModalityState.any())
            val message = answer.get(TIMEOUT_S, TimeUnit.SECONDS)
            if (message["type"] == "renderError") throw IllegalStateException(message["message"] as? String ?: "The frame could not be rendered")
            return (message["images"] as? List<*>).orEmpty().map { Base64.getDecoder().decode((it as String).substringAfter(',')) }
        } finally {
            ApplicationManager.getApplication().invokeLater({ Disposer.dispose(disposable) }, ModalityState.any())
        }
    }

    private const val TIMEOUT_S = 60L
}
