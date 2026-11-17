package cz.xprees.lottie

import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieException
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.obj
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DotLottieArchiveTest {

    @Test
    fun `reads a dotLottie 2 manifest`() {
        val archive = DotLottieArchive.read(TestData.bytes("sample.lottie"))
        assertThat(archive.specVersion).isEqualTo(2)
        val m = archive.manifest
        assertThat(m.animations.map { it.id }).containsExactly("sample", "second")
        assertThat(m.animations[0].initialTheme).isEqualTo("pink")
        assertThat(m.themes.map { it.label }).containsExactly("Pink")
        assertThat(archive.defaultAnimationId).isEqualTo("sample")
        assertThat(archive.themeText("pink")).contains("accent")
        assertThat(archive.entries.map { it.path }).containsExactly("a/sample.json", "a/second.json", "manifest.json", "t/pink.json")
        assertThat(archive.entries.first { it.path == "t/pink.json" }.kind).isEqualTo("Theme")
    }

    @Test
    fun `reads dotLottie 1 and embeds images when extracting`() {
        val archive = DotLottieArchive.read(TestData.bytes("v1.lottie"))
        assertThat(archive.specVersion).isEqualTo(1)
        assertThat(archive.manifest.author).isEqualTo("tester")
        val json = MiniJson.parse(archive.extractAnimation("legacy")!!).obj()!!
        val image = json["assets"].arr()!!.map { it.obj()!! }.first { it["id"] == "image_0" }
        assertThat(image["p"] as String).startsWith("data:image/png;base64,")
        assertThat(image["e"]).isEqualTo(1L)
        assertThat(image["u"]).isEqualTo("")
    }

    @Test
    fun `lists animation files when the manifest is missing`() {
        val archive = DotLottieArchive.read(zip("a/only.json" to TestData.bytes("sample.json")))
        assertThat(archive.manifest.animations.map { it.id }).containsExactly("only")
    }

    @Test
    fun `rejects archives without animations and non-zip data`() {
        assertThatThrownBy { DotLottieArchive.read(zip("manifest.json" to "{}".toByteArray())) }.isInstanceOf(DotLottieException::class.java)
        assertThatThrownBy { DotLottieArchive.read("not a zip".toByteArray()) }.isInstanceOf(DotLottieException::class.java)
    }

    @Test
    fun `ignores entries escaping the archive`() {
        val archive = DotLottieArchive.read(zip("../evil.json" to "{}".toByteArray(), "a/x.json" to TestData.bytes("sample.json")))
        assertThat(archive.entries.map { it.path }).containsExactly("a/x.json")
    }

    @Test
    fun `packs JSON into a dotLottie 2 archive with its images`() {
        val root = MiniJson.parse(TestData.text("sample.json")).obj()!!.toMutableMap()
        val png = TestData.bytes("v1.lottie").let { DotLottieArchive.read(it).bytes("images/img_0.png")!! }
        root["assets"] = root["assets"].arr()!! + listOf(
            mapOf("id" to "embedded", "w" to 1L, "h" to 1L, "u" to "", "p" to "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png), "e" to 1L),
            mapOf("id" to "external", "w" to 1L, "h" to 1L, "u" to "images/", "p" to "pic.png", "e" to 0L),
            mapOf("id" to "missing", "w" to 1L, "h" to 1L, "u" to "images/", "p" to "nope.png", "e" to 0L),
        )
        val bytes = DotLottieWriter.pack("my anim!", root, "test") { u, p -> if (u == "images/" && p == "pic.png") png else null }
        val archive = DotLottieArchive.read(bytes)
        assertThat(archive.specVersion).isEqualTo(2)
        assertThat(archive.manifest.version).isEqualTo("2")
        assertThat(archive.manifest.generator).isEqualTo("test")
        assertThat(archive.manifest.animations.map { it.id }).containsExactly("my anim_")
        assertThat(archive.entries.map { it.path }).contains("i/embedded.png", "i/external.png").doesNotContain("i/missing.png")
        val packed = MiniJson.parse(archive.animationText("my anim_")!!).obj()!!
        val assets = packed["assets"].arr()!!.map { it.obj()!! }.associateBy { it["id"] }
        assertThat(assets["embedded"]!!["u"]).isEqualTo("/i/")
        assertThat(assets["embedded"]!!["p"]).isEqualTo("embedded.png")
        assertThat(assets["missing"]!!["p"]).isEqualTo("nope.png")
        // and back: extracting embeds the image again
        val extracted = MiniJson.parse(archive.extractAnimation("my anim_")!!).obj()!!
        val again = extracted["assets"].arr()!!.map { it.obj()!! }.first { it["id"] == "external" }
        assertThat(again["p"] as String).startsWith("data:image/png;base64,")
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
        return out.toByteArray()
    }
}
