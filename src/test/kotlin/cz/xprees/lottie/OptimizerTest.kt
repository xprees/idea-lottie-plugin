package cz.xprees.lottie

import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.model.LottieModel
import cz.xprees.lottie.optimize.ImageCompressor
import cz.xprees.lottie.optimize.ImageOptions
import cz.xprees.lottie.optimize.ImageTarget
import cz.xprees.lottie.optimize.LottieOptimizer
import cz.xprees.lottie.optimize.OptimizeOptions
import cz.xprees.lottie.optimize.OptimizeStep
import cz.xprees.lottie.optimize.Optimizer
import cz.xprees.lottie.optimize.WebpEncoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

class OptimizerTest {

    private val cleanupOnly = OptimizeOptions(cleanup = true, precision = null)

    private fun optimize(json: String, options: OptimizeOptions = cleanupOnly, protectedNames: Set<String> = emptySet()) =
        LottieOptimizer.optimize(json, options, protectedNames).root

    private fun layer(name: String, ind: Int, extra: String = "") =
        """{"ty":3,"nm":"$name","ind":$ind,"ip":0,"op":60,"st":0,"ks":{"o":{"a":0,"k":100}}$extra}"""

    private fun anim(layers: String, assets: String = "[]", extra: String = "") =
        """{"v":"5.7.0","fr":30,"ip":0,"op":60,"w":100,"h":100,"assets":$assets,"layers":[$layers]$extra}"""

    @Suppress("UNCHECKED_CAST")
    private fun layers(root: Map<String, Any?>) = (root["layers"] as List<Map<String, Any?>>)

    // ---- cleanup ------------------------------------------------------------------------------------------------------------

    @Test
    fun `cleanup drops editor data, defaults and metadata`() {
        val root = optimize(anim(layer("A", 1, ""","hd":false,"ddd":0,"bm":0,"ao":0,"mn":"ADBE Layer","ks":{"o":{"a":0,"k":100,"ix":11}}"""), extra = ""","meta":{"g":"LottieFiles AE"}"""))
        assertThat(root).doesNotContainKey("meta")
        val a = layers(root).single()
        assertThat(a).doesNotContainKeys("hd", "ddd", "bm", "ao", "mn")
        @Suppress("UNCHECKED_CAST")
        assertThat((a["ks"] as Map<*, *>)["o"] as Map<String, Any?>).doesNotContainKey("ix")
        assertThat(a["nm"]).isEqualTo("A")
    }

    @Test
    fun `keeps match names and indexes when the animation has expressions`() {
        val root = optimize(anim(layer("A", 1, ""","mn":"ADBE Layer","ks":{"o":{"a":0,"k":100,"ix":11,"x":"thisComp.layer(1)"}}""")))
        val a = layers(root).single()
        assertThat(a["mn"]).isEqualTo("ADBE Layer")
        @Suppress("UNCHECKED_CAST")
        assertThat((a["ks"] as Map<*, *>)["o"] as Map<String, Any?>).containsKey("ix")
    }

    @Test
    fun `collapses keyframes that never change`() {
        val root = optimize(anim(layer("A", 1, ""","ks":{"o":{"a":1,"k":[{"t":0,"s":[50]},{"t":30,"s":[50]}]},"p":{"a":1,"k":[{"t":0,"s":[1,2]},{"t":9,"s":[3,4]}]}}""")))
        val ks = layers(root).single()["ks"] as Map<*, *>
        assertThat(ks["o"]).isEqualTo(mapOf("a" to 0L, "k" to 50L))
        assertThat((ks["p"] as Map<*, *>)["a"]).isEqualTo(1L)
    }

    @Test
    fun `removes empty groups and unused assets`() {
        val shapes = ""","ty":4,"shapes":[{"ty":"gr","nm":"Empty","it":[{"ty":"tr"}]},{"ty":"gr","nm":"Full","it":[{"ty":"rc"},{"ty":"tr"}]}]"""
        val assets = """[{"id":"used","layers":[${layer("Inner", 1, ""","refId":"nested"""")}]},{"id":"nested","w":1,"h":1,"p":"data:image/png;base64,AA=="},{"id":"orphan","layers":[]},{"id":"slot","sid":"s","layers":[]}]"""
        val root = optimize(anim(layer("Shapes", 1, shapes) + "," + layer("Precomp", 2, ""","ty":0,"refId":"used""""), assets))
        assertThat((layers(root)[0]["shapes"] as List<*>).map { (it as Map<*, *>)["nm"] }).containsExactly("Full")
        assertThat((root["assets"] as List<*>).map { (it as Map<*, *>)["id"] }).containsExactly("used", "nested", "slot")
    }

    @Test
    fun `merges identical embedded images`() {
        val png = "data:image/png;base64,iVBORw0KGgo="
        val assets = """[{"id":"a","w":2,"h":2,"p":"$png","e":1},{"id":"b","w":2,"h":2,"p":"$png","e":1}]"""
        val root = optimize(anim(layer("One", 1, ""","ty":2,"refId":"a"""") + "," + layer("Two", 2, ""","ty":2,"refId":"b""""), assets))
        assertThat((root["assets"] as List<*>).map { (it as Map<*, *>)["id"] }).containsExactly("a")
        assertThat(layers(root).map { it["refId"] }).containsExactly("a", "a")
    }

    @Test
    fun `the sample keeps its markers, slots, layer links and renders the same model`() {
        val text = TestData.text("sample.json")
        val before = LottieModel.read(MiniJson.parse(text).obj()!!)!!
        val root = LottieOptimizer.optimize(text, OptimizeOptions()).root
        val after = LottieModel.read(root)!!
        assertThat(after.markers).isEqualTo(before.markers)
        assertThat(after.layers.map { it.label }).isEqualTo(before.layers.map { it.label })
        assertThat(root["slots"]).isEqualTo(MiniJson.parse(text).obj()!!["slots"])
        assertThat(layers(root).map { it["ind"] to it["parent"] }).isEqualTo(layers(MiniJson.parse(text).obj()!!).map { it["ind"] to it["parent"] })
    }

    // ---- precision ----------------------------------------------------------------------------------------------------------

    @Test
    fun `rounds numbers but keeps times, colours and easing finer`() {
        val fill = """{"ty":"fl","c":{"a":0,"k":[0.123456,0.5,0.987654,1]}}"""
        val anim = anim(
            layer("A", 1, ""","ip":0.123456,"ks":{"p":{"a":1,"k":[{"t":10.123456,"s":[1.23456,2.34567],"i":{"x":[0.123456],"y":[1]},"o":{"x":[0.33333],"y":[0]}},{"t":20,"s":[3,4]}]}},"ty":4,"shapes":[$fill]"""),
        )
        val root = LottieOptimizer.optimize(anim, OptimizeOptions(cleanup = false, precision = 1)).root
        val a = layers(root).single()
        val kf = ((a["ks"] as Map<*, *>)["p"] as Map<*, *>)["k"].arr()!!.first() as Map<*, *>
        assertThat(kf["s"]).isEqualTo(listOf(1.2, 2.3))
        assertThat(kf["t"]).isEqualTo(10.123)
        assertThat((kf["i"] as Map<*, *>)["x"]).isEqualTo(listOf(0.123))
        assertThat(a["ip"]).isEqualTo(0.123)
        val color = (((a["shapes"] as List<*>)[0] as Map<*, *>)["c"] as Map<*, *>)["k"]
        assertThat(color).isEqualTo(listOf(0.123, 0.5, 0.988, 1L))
    }

    // ---- hidden layers ------------------------------------------------------------------------------------------------------

    @Test
    fun `removes hidden and never visible layers but keeps parents, mattes and protected names`() {
        val layers = listOf(
            layer("Hidden", 1, ""","hd":true"""),
            layer("Late", 2, ""","ip":100,"op":200"""),
            layer("Clear", 3, ""","ks":{"o":{"a":0,"k":0}}"""),
            layer("HiddenParent", 4, ""","hd":true"""),
            layer("Child", 5, ""","parent":4"""),
            layer("Matte", 6, ""","hd":true,"td":1"""),
            layer("Named", 7, ""","hd":true"""),
            layer("Scripted", 8, ""","hd":true"""),
            layer("Driver", 9, ""","ks":{"o":{"a":0,"k":100,"x":"thisComp.layer(\"Scripted\").opacity"}}"""),
        ).joinToString(",")
        val result = LottieOptimizer.optimize(anim(layers), OptimizeOptions(cleanup = false, precision = null, removeHiddenLayers = true), setOf("Named"))
        assertThat(result.layersRemoved).containsExactlyInAnyOrder("Hidden", "Late", "Clear")
        assertThat(layers(result.root).map { it["nm"] }).containsExactly("HiddenParent", "Child", "Matte", "Named", "Scripted", "Driver")
    }

    // ---- images -------------------------------------------------------------------------------------------------------------

    private fun png(width: Int, height: Int, alpha: Boolean = false): ByteArray {
        val image = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        for (x in 0 until width step 4) for (y in 0 until height step 4) {
            g.color = java.awt.Color((x * 7) % 255, (y * 5) % 255, (x + y) % 255, if (alpha) 128 else 255)
            g.fillRect(x, y, 4, 4)
        }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun `scales images down to twice their drawn size and converts opaque ones to JPEG`() {
        val big = png(400, 400)
        val scaled = ImageCompressor.compress(big, 50, 50, ImageOptions(enabled = true), null)!!
        assertThat(scaled.mimeType).isEqualTo("image/png")
        assertThat(ImageIO.read(ByteArrayInputStream(scaled.bytes)).width).isEqualTo(100)

        val jpeg = ImageCompressor.compress(big, null, null, ImageOptions(enabled = true, target = ImageTarget.JPEG, quality = 70), null)!!
        assertThat(jpeg.mimeType).isEqualTo("image/jpeg")
        assertThat(jpeg.bytes.size).isLessThan(big.size)
        // transparent images keep their format; same size and format: nothing to gain
        assertThat(ImageCompressor.compress(png(64, 64, alpha = true), null, null, ImageOptions(enabled = true, target = ImageTarget.JPEG), null)).isNull()
    }

    @Test
    fun `WebP goes through the encoder and is used only when smaller`() {
        val source = png(64, 64)
        val calls = mutableListOf<String>()
        val small = WebpEncoder { _, w, h, q -> calls += "$w x $h @ $q"; ByteArray(10) }
        val out = ImageCompressor.compress(source, 16, 16, ImageOptions(enabled = true, target = ImageTarget.WEBP, quality = 60), small)!!
        assertThat(out.mimeType).isEqualTo("image/webp")
        assertThat(calls).containsExactly("32 x 32 @ 60")
        val huge = WebpEncoder { _, _, _, _ -> ByteArray(source.size + 1) }
        assertThat(ImageCompressor.compress(source, null, null, ImageOptions(enabled = true, target = ImageTarget.WEBP), huge)).isNull()
        // without an encoder WebP is not possible
        assertThat(ImageCompressor.compress(source, null, null, ImageOptions(enabled = true, target = ImageTarget.WEBP), null)).isNull()
    }

    @Test
    fun `compresses embedded images in place`() {
        val data = "data:image/png;base64," + Base64.getEncoder().encodeToString(png(300, 300))
        val root = LottieOptimizer.optimize(
            anim(layer("Img", 1, ""","ty":2,"refId":"img""""), """[{"id":"img","w":30,"h":30,"p":"$data","e":1}]"""),
            OptimizeOptions(cleanup = false, precision = null, images = ImageOptions(enabled = true)),
        )
        assertThat(root.imagesChanged).isEqualTo(1)
        val p = ((root.root["assets"] as List<*>)[0] as Map<*, *>)["p"] as String
        assertThat(p.length).isLessThan(data.length)
    }

    // ---- sizes ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `reports the size saved by each step`() {
        val text = TestData.text("sample.json")
        val result = Optimizer.optimizeJson(text, OptimizeOptions(removeHiddenLayers = true))
        assertThat(result.sizeBefore).isEqualTo(text.toByteArray().size.toLong())
        assertThat(result.savings.map { it.first }).containsExactly(OptimizeStep.MINIFY, OptimizeStep.CLEANUP, OptimizeStep.HIDDEN_LAYERS, OptimizeStep.PRECISION)
        assertThat(result.savings.sumOf { it.second }).isEqualTo(result.saved)
        assertThat(result.savings.first().second).isGreaterThan(0)
        assertThat(result.sizeAfter).isLessThan(result.sizeBefore)
        assertThat(result.packedSize).isNotNull()
        assertThat(LottieModel.read(MiniJson.parse(result.bytes.toString(Charsets.UTF_8)).obj()!!)).isNotNull()
    }

    @Test
    fun `optimizes every animation of a dotLottie and keeps what its state machine refers to`() {
        val original = TestData.bytes("interactive.lottie")
        val result = Optimizer.optimizeDotLottie(original, OptimizeOptions(removeHiddenLayers = true))
        val before = DotLottieArchive.read(original)
        val after = DotLottieArchive.read(result.bytes)
        assertThat(after.manifest).isEqualTo(before.manifest)
        assertThat(after.stateMachineText("toggle")).isEqualTo(before.stateMachineText("toggle"))
        // no layer of this animation is hidden: all of them (and so whatever the state machine names) are still there
        assertThat(after.stateMachineContext().layerNames).isEqualTo(before.stateMachineContext().layerNames)
        assertThat(result.layersRemoved).isEmpty()
        assertThat(LottieModel.read(MiniJson.parse(after.animationText("sample")!!).obj()!!)).isNotNull()
    }

    @Test
    fun `stores identical archive images once and drops unused ones`() {
        val image = png(8, 8)
        val anim = anim(
            layer("One", 1, ""","ty":2,"refId":"a"""") + "," + layer("Two", 2, ""","ty":2,"refId":"b""""),
            """[{"id":"a","w":8,"h":8,"u":"/i/","p":"a.png"},{"id":"b","w":8,"h":8,"u":"/i/","p":"b.png"}]""",
        )
        val archive = DotLottieWriter.write(
            linkedMapOf(
                "manifest.json" to """{"version":"2","animations":[{"id":"x"}]}""".toByteArray(),
                "a/x.json" to anim.toByteArray(),
                "i/a.png" to image,
                "i/b.png" to image.copyOf(),
                "i/unused.png" to png(4, 4),
            ),
        )
        val result = DotLottieArchive.read(Optimizer.optimizeDotLottie(archive, OptimizeOptions()).bytes)
        assertThat(result.entries.map { it.path }).containsExactlyInAnyOrder("manifest.json", "a/x.json", "i/a.png")
        val assets = MiniJson.parse(result.animationText("x")!!).obj()!!["assets"].arr()!!.map { it.obj()!!["p"] }
        assertThat(assets).containsExactly("a.png", "a.png")
    }

    // ---- how sizes are shown --------------------------------------------------------------------------------------------------

    @Test
    fun `formats savings for the dialog and the report`() {
        val p = cz.xprees.lottie.optimize.OptimizeDialog.Companion
        assertThat(p.percent(6_400, 10_000)).isEqualTo("−64%")
        assertThat(p.percent(9_970, 10_000)).isEqualTo("−99.7%")
        assertThat(p.percent(10_000, 10_000)).isEqualTo("−100%")
        assertThat(p.percent(5, 10_000)).isEqualTo("−<1%")
        assertThat(p.percent(-300, 10_000)).isEqualTo("+3%")
        assertThat(p.percent(0, 10_000)).isEqualTo("0%")
        assertThat(p.signed(0)).doesNotStartWith("−").doesNotStartWith("+")
        assertThat(p.signed(-2048)).startsWith("+")
        assertThat(p.share(0.0052)).isEqualTo("0.52%")
    }
}
