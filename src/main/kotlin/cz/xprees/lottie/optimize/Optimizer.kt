package cz.xprees.lottie.optimize

import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.obj

/** The steps of an optimization, in the order they run, as the dialog and the report list them. */
enum class OptimizeStep { MINIFY, CLEANUP, HIDDEN_LAYERS, IMAGES, PRECISION }

class OptimizeResult(
    val bytes: ByteArray,
    val sizeBefore: Long,
    /** Bytes saved by each enabled step (negative if a step grew the file, which the dialog shows as is). */
    val savings: List<Pair<OptimizeStep, Long>>,
    val layersRemoved: List<String>,
    val imagesChanged: Int,
    /** For Lottie JSON: the size of the result packed as a `.lottie`; null for `.lottie` files. */
    val packedSize: Long? = null,
) {
    val sizeAfter: Long get() = bytes.size.toLong()
    val saved: Long get() = sizeBefore - sizeAfter
    val percent: Double get() = if (sizeBefore == 0L) 0.0 else saved * 100.0 / sizeBefore
}

/** Optimizes Lottie JSON documents and `.lottie` archives, measuring what each step saves. */
object Optimizer {

    fun optimizeJson(text: CharSequence, options: OptimizeOptions, webp: WebpEncoder? = null): OptimizeResult {
        val before = text.toString().toByteArray(Charsets.UTF_8).size.toLong()
        val parsed = MiniJson.parse(text)
        return measure(before, options) { o ->
            val r = LottieOptimizer.optimize(parsed, o, webp = webp)
            Stage(r.json.toByteArray(Charsets.UTF_8), r.layersRemoved, r.imagesChanged, r.root)
        }.let { (stages, last) ->
            val packed = runCatching { DotLottieWriter.pack("animation", last.root!!, "Lottie for JetBrains IDEs").size.toLong() }.getOrNull()
            OptimizeResult(last.bytes, before, stages, last.layersRemoved, last.imagesChanged, packed)
        }
    }

    fun optimizeDotLottie(bytes: ByteArray, options: OptimizeOptions, webp: WebpEncoder? = null): OptimizeResult {
        val archive = DotLottieArchive.read(bytes)
        return measure(bytes.size.toLong(), options) { o ->
            val r = DotLottieOptimizer.optimize(archive, o, webp)
            Stage(r.bytes, r.layersRemoved, r.imagesChanged, null)
        }.let { (stages, last) -> OptimizeResult(last.bytes, bytes.size.toLong(), stages, last.layersRemoved, last.imagesChanged) }
    }

    private class Stage(val bytes: ByteArray, val layersRemoved: List<String>, val imagesChanged: Int, val root: Map<String, Any?>?)

    /**
     * Runs [run] with the steps switched on one after another (minify alone, then with the cleanup, and so on) and returns what
     * each enabled step saved, with the full run.
     */
    private fun measure(before: Long, options: OptimizeOptions, run: (OptimizeOptions) -> Stage): Pair<List<Pair<OptimizeStep, Long>>, Stage> {
        val none = OptimizeOptions(cleanup = false, precision = null, removeHiddenLayers = false, images = options.images.copy(enabled = false))
        val ladder = buildList {
            add(OptimizeStep.MINIFY to none)
            var current = none
            if (options.cleanup) { current = current.copy(cleanup = true); add(OptimizeStep.CLEANUP to current) }
            if (options.removeHiddenLayers) { current = current.copy(removeHiddenLayers = true); add(OptimizeStep.HIDDEN_LAYERS to current) }
            if (options.images.enabled) { current = current.copy(images = options.images); add(OptimizeStep.IMAGES to current) }
            if (options.precision != null) { current = current.copy(precision = options.precision); add(OptimizeStep.PRECISION to current) }
        }
        var previous = before
        var last: Stage? = null
        val savings = ladder.map { (step, o) ->
            val stage = run(o)
            last = stage
            val size = stage.bytes.size.toLong()
            (step to previous - size).also { previous = size }
        }
        return savings to last!!
    }

    /** The JSON of [text] as an object, or null (for callers checking input before optimizing). */
    fun isLottie(text: CharSequence): Boolean = runCatching { MiniJson.parse(text).obj()?.containsKey("layers") == true }.getOrDefault(false)
}
