package cz.xprees.lottie.preview

import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse

/** Answers one request with an in-memory body. */
internal class BytesResourceHandler(private val bytes: ByteArray, private val mimeType: String, private val status: Int = 200) : CefResourceHandler {
    private var offset = 0

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        callback.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
        response.status = status
        response.mimeType = mimeType
        response.setHeaderByName("Cache-Control", "no-store", true)
        response.setHeaderByName("Access-Control-Allow-Origin", PreviewBrowser.ORIGIN.trimEnd('/'), true)
        responseLength.set(bytes.size)
    }

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        if (offset >= bytes.size) {
            bytesRead.set(0)
            return false
        }
        val n = minOf(bytesToRead, bytes.size - offset)
        System.arraycopy(bytes, offset, dataOut, 0, n)
        offset += n
        bytesRead.set(n)
        return true
    }

    override fun cancel() {}
}
