package cz.xprees.lottie.optimize

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

enum class ImageTarget {
    /** Each image keeps its format (PNG stays PNG, JPEG is re-encoded at the quality). */
    KEEP,

    /** WebP at the quality (needs a [WebpEncoder]). */
    WEBP,

    /** JPEG at the quality for images without transparency; transparent images keep their format. */
    JPEG,
}

data class ImageOptions(
    val enabled: Boolean = false,
    val target: ImageTarget = ImageTarget.KEEP,
    /** 1..100, for JPEG and WebP. */
    val quality: Int = 80,
    /** Images larger than [maxScale] times the size they are drawn at (the asset's `w`/`h`) are scaled down; null keeps sizes. */
    val maxScale: Int? = 2,
)

/** Encodes an image ([source] in any format the encoder reads) as WebP, scaled to fit [maxWidth]×[maxHeight] (0: any size). */
fun interface WebpEncoder {
    fun encode(source: ByteArray, maxWidth: Int, maxHeight: Int, quality: Int): ByteArray?
}

/** Re-encodes and scales down images. A result is used only when it is smaller than the original. */
object ImageCompressor {

    class Output(val bytes: ByteArray, val mimeType: String)

    /** Compresses the images embedded as data URIs in [root]'s assets; returns how many changed. */
    fun compressEmbedded(root: MutableMap<String, Any?>, options: ImageOptions, webp: WebpEncoder?): Int {
        var changed = 0
        (root["assets"] as? List<*>)?.forEach { asset ->
            @Suppress("UNCHECKED_CAST")
            val a = asset as? MutableMap<String, Any?> ?: return@forEach
            val p = a["p"] as? String ?: return@forEach
            if (a.containsKey("layers") || !p.startsWith("data:")) return@forEach
            val bytes = decodeDataUri(p) ?: return@forEach
            val out = compress(bytes, (a["w"] as? Number)?.toInt(), (a["h"] as? Number)?.toInt(), options, webp) ?: return@forEach
            a["p"] = "data:${out.mimeType};base64," + Base64.getEncoder().encodeToString(out.bytes)
            a["e"] = 1L
            changed++
        }
        return changed
    }

    fun compress(bytes: ByteArray, assetWidth: Int?, assetHeight: Int?, options: ImageOptions, webp: WebpEncoder?): Output? {
        val source = formatOf(bytes) ?: return null
        val scale = options.maxScale
        val maxW = if (scale != null && assetWidth != null && assetWidth > 0) assetWidth * scale else 0
        val maxH = if (scale != null && assetHeight != null && assetHeight > 0) assetHeight * scale else 0
        val quality = options.quality.coerceIn(1, 100)

        val out = when {
            options.target == ImageTarget.WEBP || source == "image/webp" ->
                webp?.encode(bytes, maxW, maxH, quality)?.let { Output(it, "image/webp") }
            else -> {
                val decoded = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
                val image = fit(decoded, maxW, maxH)
                val resized = image !== decoded
                when {
                    options.target == ImageTarget.JPEG && isOpaque(image) -> Output(writeJpeg(image, quality), "image/jpeg")
                    source == "image/jpeg" -> Output(writeJpeg(image, quality), "image/jpeg")
                    source == "image/png" && resized -> Output(writePng(image), "image/png")
                    else -> null
                }
            }
        }
        return out?.takeIf { it.bytes.isNotEmpty() && it.bytes.size < bytes.size }
    }

    /** The format by its signature: PNG, JPEG, WebP or GIF; null for anything else. */
    fun formatOf(bytes: ByteArray): String? = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        bytes.size > 6 && String(bytes, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "image/gif"
        else -> null
    }

    fun extension(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }

    private fun decodeDataUri(uri: String): ByteArray? {
        val header = uri.substringBefore(',')
        if (!header.endsWith(";base64")) return null
        return runCatching { Base64.getMimeDecoder().decode(uri.substringAfter(',')) }.getOrNull()
    }

    /** [image] scaled down (area averaging) to fit [maxW]×[maxH], or [image] itself if it fits or there is no limit. */
    private fun fit(image: BufferedImage, maxW: Int, maxH: Int): BufferedImage {
        if (maxW <= 0 || maxH <= 0 || (image.width <= maxW && image.height <= maxH)) return image
        val ratio = minOf(maxW.toDouble() / image.width, maxH.toDouble() / image.height)
        val w = (image.width * ratio).toInt().coerceAtLeast(1)
        val h = (image.height * ratio).toInt().coerceAtLeast(1)
        val scaled = image.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING)
        return BufferedImage(w, h, if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB).also {
            val g = it.createGraphics()
            g.drawImage(scaled, 0, 0, null)
            g.dispose()
        }
    }

    private fun isOpaque(image: BufferedImage): Boolean {
        if (!image.colorModel.hasAlpha()) return true
        val raster = image.alphaRaster ?: return true
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            raster.getPixels(0, y, image.width, 1, row)
            if (row.any { it != 255 }) return false
        }
        return true
    }

    private fun writeJpeg(image: BufferedImage, quality: Int): ByteArray {
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) image else BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also {
            val g = it.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.drawImage(image, 0, 0, null)
            g.dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality / 100f
                }
                writer.write(null, IIOImage(rgb, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    private fun writePng(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
