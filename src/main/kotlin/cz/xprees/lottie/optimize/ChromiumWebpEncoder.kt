package cz.xprees.lottie.optimize

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import cz.xprees.lottie.preview.JcefSupport
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encodes WebP in the IDE's embedded Chromium (the browser the preview uses): the image is decoded and scaled on a canvas and
 * written with `canvas.toBlob('image/webp', quality)`. No native library of our own, so the plugin stays restart-free. The page is
 * built from a string and loads nothing.
 *
 * [encode] blocks until the browser answers; call it off the EDT.
 */
internal class ChromiumWebpEncoder private constructor(parent: Disposable) : WebpEncoder, Disposable {

    private val browser = JBCefBrowser.createBuilder().setOffScreenRendering(true).build()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val loaded = CompletableFuture<Boolean>()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<String>>()
    private val ids = AtomicInteger()

    init {
        Disposer.register(parent, this)
        Disposer.register(this, browser)
        Disposer.register(this, query)
        query.addHandler { message ->
            message.substringBefore(':').toIntOrNull()?.let { pending.remove(it)?.complete(message.substringAfter(':')) }
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) loaded.complete(true)
            }
        }, browser.cefBrowser)
        browser.createImmediately()
        browser.loadHTML(page(query.inject("message")))
    }

    override fun encode(source: ByteArray, maxWidth: Int, maxHeight: Int, quality: Int): ByteArray? {
        val mime = ImageCompressor.formatOf(source) ?: return null
        try {
            if (!loaded.get(LOAD_TIMEOUT_S, TimeUnit.SECONDS)) return null
        } catch (e: Exception) {
            LOG.info("The WebP encoder page did not load", e)
            return null
        }
        val id = ids.incrementAndGet()
        val answer = CompletableFuture<String>()
        pending[id] = answer
        val base64 = Base64.getEncoder().encodeToString(source)
        browser.cefBrowser.executeJavaScript("encodeWebp($id, '$base64', '$mime', $maxWidth, $maxHeight, ${quality.coerceIn(1, 100) / 100.0})", "about:blank", 0)
        val result = try {
            answer.get(ENCODE_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pending.remove(id)
            LOG.info("WebP encoding did not finish", e)
            return null
        }
        return result.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    }

    override fun dispose() {
        pending.values.forEach { it.complete("") }
        pending.clear()
    }

    companion object {
        private val LOG = logger<ChromiumWebpEncoder>()
        private const val LOAD_TIMEOUT_S = 15L
        private const val ENCODE_TIMEOUT_S = 60L

        /** An encoder living as long as [parent], or null where the IDE has no embedded browser. Create it on the EDT. */
        fun create(parent: Disposable): ChromiumWebpEncoder? {
            if (!JcefSupport.isAvailable()) return null
            return try {
                ChromiumWebpEncoder(parent)
            } catch (e: LinkageError) {
                null
            } catch (e: Exception) {
                LOG.info("No WebP encoder: the embedded browser could not start", e)
                null
            }
        }

        private fun page(reply: String) = """
            <!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; script-src 'unsafe-inline'">
            <script>
            function send(id, data) { const message = id + ':' + data; $reply; }
            async function encodeWebp(id, base64, mime, maxW, maxH, quality) {
              try {
                const img = new Image();
                img.src = 'data:' + mime + ';base64,' + base64;
                await img.decode();
                let w = img.naturalWidth, h = img.naturalHeight;
                if (maxW > 0 && maxH > 0 && (w > maxW || h > maxH)) {
                  const r = Math.min(maxW / w, maxH / h);
                  w = Math.max(1, Math.floor(w * r));
                  h = Math.max(1, Math.floor(h * r));
                }
                const canvas = document.createElement('canvas');
                canvas.width = w;
                canvas.height = h;
                const g = canvas.getContext('2d');
                g.imageSmoothingQuality = 'high';
                g.drawImage(img, 0, 0, w, h);
                const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/webp', quality));
                if (!blob || blob.type !== 'image/webp') { send(id, ''); return; }
                const bytes = new Uint8Array(await blob.arrayBuffer());
                let s = '';
                for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
                send(id, btoa(s));
              } catch (e) {
                send(id, '');
              }
            }
            </script>
        """.trimIndent()
    }
}
