package cz.xprees.lottie.optimize

import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.json.MiniJson

/**
 * Optimizes every animation of a `.lottie` archive ([LottieOptimizer]) and its image files: identical images are stored once,
 * images no animation uses are dropped (with the cleanup), images are compressed (with [ImageOptions.enabled]), and the archive
 * is written at the highest Deflate level. The manifest, themes and state machines are kept as they are; layer names and ids they
 * refer to are protected.
 */
object DotLottieOptimizer {

    class Result(val bytes: ByteArray, val layersRemoved: List<String>, val imagesChanged: Int)

    fun optimize(archive: DotLottieArchive, options: OptimizeOptions, webp: WebpEncoder?): Result {
        val files = LinkedHashMap(archive.allFiles)
        val protectedNames = files.filterKeys { it.startsWith("s/") || it.startsWith("t/") || it.startsWith("states/") || it.startsWith("themes/") }
            .values.flatMap { strings(MiniJson.parseOrNull(it.toString(Charsets.UTF_8))) }.toSet()

        val animationPaths = files.keys.filter { (it.startsWith("a/") || it.startsWith("animations/")) && it.endsWith(".json") }
        val removed = ArrayList<String>()
        var imagesChanged = 0
        val roots = LinkedHashMap<String, MutableMap<String, Any?>>()
        for (path in animationPaths) {
            val r = LottieOptimizer.optimize(files.getValue(path).toString(Charsets.UTF_8), options, protectedNames, webp)
            roots[path] = r.root
            removed += r.layersRemoved
            imagesChanged += r.imagesChanged
        }

        val imagePaths = files.keys.filter { (it.startsWith("i/") || it.startsWith("images/")) && ImageCompressor.formatOf(files.getValue(it)) != null }
        if (options.cleanup) {
            // identical image files: keep the first, point assets at it
            val firstByContent = HashMap<String, String>()
            for (path in imagePaths) {
                val key = files.getValue(path).contentHashCode().toString() + ":" + files.getValue(path).size
                val kept = firstByContent[key]
                if (kept != null && files.getValue(kept).contentEquals(files.getValue(path))) {
                    rename(roots.values, path, kept)
                    files.remove(path)
                } else {
                    firstByContent[key] = path
                }
            }
            // image files no asset uses
            val used = roots.values.flatMap { root -> imageAssets(root).mapNotNull { resolve(it) } }.toSet()
            for (path in imagePaths) if (path in files && path !in used && path.substringAfterLast('/') !in protectedNames) files.remove(path)
        }
        if (options.images.enabled) {
            for (path in imagePaths.filter { it in files }) {
                val assets = roots.values.flatMap { imageAssets(it) }.filter { resolve(it) == path }
                val w = assets.mapNotNull { (it["w"] as? Number)?.toInt() }.maxOrNull()
                val h = assets.mapNotNull { (it["h"] as? Number)?.toInt() }.maxOrNull()
                val out = ImageCompressor.compress(files.getValue(path), w, h, options.images, webp) ?: continue
                val target = withExtension(path, ImageCompressor.extension(out.mimeType), files.keys)
                if (target != path) {
                    rename(roots.values, path, target)
                    files.remove(path)
                }
                files[target] = out.bytes
                imagesChanged++
            }
        }

        for ((path, root) in roots) files[path] = MiniJson.write(root).toByteArray(Charsets.UTF_8)
        return Result(DotLottieWriter.write(files), removed, imagesChanged)
    }

    /** Image assets (not precompositions) of an animation. */
    @Suppress("UNCHECKED_CAST")
    private fun imageAssets(root: Map<String, Any?>): List<MutableMap<String, Any?>> =
        (root["assets"] as? List<*>).orEmpty().mapNotNull { it as? MutableMap<String, Any?> }.filter { !it.containsKey("layers") && it["p"] is String }

    /** The archive path an asset points at (`u` + `p`), or null for embedded and external images. */
    private fun resolve(asset: Map<String, Any?>): String? {
        val p = asset["p"] as? String ?: return null
        if (p.startsWith("data:") || p.startsWith("http:") || p.startsWith("https:")) return null
        return ((asset["u"] as? String).orEmpty() + p).trimStart('/')
    }

    /** Points the assets that use [from] at [to] (same folder: only `p` changes). */
    private fun rename(roots: Collection<MutableMap<String, Any?>>, from: String, to: String) {
        for (root in roots) for (asset in imageAssets(root)) if (resolve(asset) == from) {
            asset["u"] = "/" + to.substringBeforeLast('/') + "/"
            asset["p"] = to.substringAfterLast('/')
        }
    }

    private fun withExtension(path: String, extension: String, taken: Set<String>): String {
        val current = path.substringAfterLast('.', "")
        if (current.equals(extension, ignoreCase = true) || (current.equals("jpeg", true) && extension == "jpg")) return path
        val base = path.substringBeforeLast('.')
        var candidate = "$base.$extension"
        var n = 1
        while (candidate in taken) candidate = "${base}_${n++}.$extension"
        return candidate
    }

    private fun strings(node: Any?): List<String> = when (node) {
        is String -> listOf(node)
        is Map<*, *> -> node.values.flatMap(::strings)
        is List<*> -> node.flatMap(::strings)
        else -> emptyList()
    }
}
