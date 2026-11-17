package cz.xprees.lottie.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ui.jcef.utils.JBCefStreamResourceHandler
import cz.xprees.lottie.json.MiniJson
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.io.ByteArrayInputStream
import javax.swing.JComponent

/**
 * The embedded Chromium page that renders animations. Everything it loads comes from [ORIGIN], answered by the plugin: the page
 * and player from the plugin's resources, `data/<n>` from [dataProvider] (the current animation bytes). Other requests and
 * navigations are refused, so a Lottie file can't make the IDE fetch anything.
 *
 * Messages from the page arrive in [onMessage] on the EDT; [call] runs `lottiePreview.<method>(args)` once the page is ready.
 */
internal class PreviewBrowser(
    parent: Disposable,
    private val initConfig: () -> Map<String, Any?>,
    private val dataProvider: () -> ByteArray?,
    private val onMessage: (Map<String, Any?>) -> Unit,
) : Disposable {

    private val browser = JBCefBrowser.createBuilder()
        .setEnableOpenDevToolsMenuItem(ApplicationManager.getApplication().isInternal)
        .build()
    private val query = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private var ready = false
    private val pending = ArrayList<String>()

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, this)
        Disposer.register(this, browser)
        Disposer.register(this, query)

        query.addHandler { json ->
            val message = MiniJson.parseOrNull(json) as? Map<*, *>
            if (message != null) {
                @Suppress("UNCHECKED_CAST")
                ApplicationManager.getApplication().invokeLater({ if (!disposed) handle(message as Map<String, Any?>) }, { disposed })
            }
            null
        }

        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean): Boolean =
                request?.url?.startsWith(ORIGIN) != true

            override fun getResourceRequestHandler(
                browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, isNavigation: Boolean, isDownload: Boolean,
                requestInitiator: String?, disableDefaultHandling: BoolRef?,
            ): CefResourceRequestHandler = resourceHandler
        }, browser.cefBrowser)

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                val bridge = "window.__lottieBridge = function(msg) { ${query.inject("msg")} };"
                val init = "(function start() { if (window.lottiePreview) { lottiePreview.init(${MiniJson.write(initConfig())}); }" +
                    " else { setTimeout(start, 20); } })();"
                browser.cefBrowser.executeJavaScript(bridge + init, ORIGIN, 0)
            }
        }, browser.cefBrowser)

        browser.loadURL("${ORIGIN}web/preview.html")
    }

    private val resourceHandler = object : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler? {
            val url = request?.url ?: return null
            if (!url.startsWith(ORIGIN)) return blocked()
            val path = url.removePrefix(ORIGIN).substringBefore('?').substringBefore('#')
            val bytes: ByteArray? = when {
                path.startsWith("data/") -> dataProvider()
                path.startsWith("web/") && !path.contains("..") -> PreviewBrowser::class.java.getResourceAsStream("/$path")?.use { it.readBytes() }
                else -> null
            }
            if (bytes == null) {
                LOG.debug("Lottie preview: no resource for $path")
                return blocked()
            }
            return stream(bytes, mimeType(path))
        }
    }

    /** An empty response for anything the page may not load. */
    private fun blocked(): CefResourceHandler = stream(ByteArray(0), "text/plain")

    /**
     * The platform's handler, built against each IDE's own JCEF: newer JCEF versions add abstract methods to CefResourceHandler
     * (open/read/skip) that a handler compiled against the oldest supported IDE can't implement. It disposes itself once the
     * response has been read or the request is cancelled.
     */
    private fun stream(bytes: ByteArray, mimeType: String): CefResourceHandler =
        JBCefStreamResourceHandler(ByteArrayInputStream(bytes), mimeType, this, mapOf("Cache-Control" to "no-store"))

    private fun handle(message: Map<String, Any?>) {
        if (message["type"] == "ready") {
            ready = true
            pending.forEach { browser.cefBrowser.executeJavaScript(it, ORIGIN, 0) }
            pending.clear()
        }
        onMessage(message)
    }

    /** Calls `lottiePreview.method(args...)` in the page; queued until the page has initialized. */
    fun call(method: String, vararg args: Any?) {
        val code = "window.lottiePreview && lottiePreview.$method(${args.joinToString(",") { MiniJson.write(it) }});"
        if (ready) browser.cefBrowser.executeJavaScript(code, ORIGIN, 0) else pending += code
    }

    @Volatile
    private var disposed = false

    override fun dispose() {
        disposed = true
        pending.clear()
    }

    companion object {
        /** A private origin answered entirely by the plugin (`.invalid` never resolves on a real network). */
        const val ORIGIN = "https://lottie-preview.invalid/"

        private val LOG = logger<PreviewBrowser>()

        fun mimeType(path: String): String = when (path.substringAfterLast('.', "")) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "wasm" -> "application/wasm"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }
}
