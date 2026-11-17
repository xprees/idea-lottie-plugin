package cz.xprees.lottie

import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieMigration
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.obj
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DotLottieMigrationTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((path, text) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun `moves a dotLottie 1 archive to the 2 layout`() {
        val result = DotLottieMigration.toV2(TestData.bytes("v1.lottie"), "test")!!
        val archive = DotLottieArchive.read(result.bytes)
        assertThat(archive.specVersion).isEqualTo(2)
        assertThat(archive.entries.map { it.path }).containsExactly("a/legacy.json", "i/img_0.png", "manifest.json")
        assertThat(archive.manifest.version).isEqualTo("2")
        assertThat(archive.manifest.generator).isEqualTo("test")
        assertThat(archive.manifest.animations.map { it.id }).containsExactly("legacy")
        // the image asset now points into i/ and still resolves (extracting embeds it)
        val animation = MiniJson.parse(archive.animationText("legacy")!!).obj()!!
        val image = animation["assets"].arr()!!.map { it.obj()!! }.first { it["id"] == "image_0" }
        assertThat(image["u"]).isEqualTo("/i/")
        assertThat(image["p"]).isEqualTo("img_0.png")
        val extracted = MiniJson.parse(archive.extractAnimation("legacy")!!).obj()!!
        assertThat(extracted["assets"].arr()!!.map { it.obj()!! }.first { it["id"] == "image_0" }["p"] as String).startsWith("data:image/png;base64,")
        // the animation itself is unchanged apart from the image path
        val before = MiniJson.parse(DotLottieArchive.read(TestData.bytes("v1.lottie")).animationText("legacy")!!).obj()!!
        assertThat(animation.filterKeys { it != "assets" }).isEqualTo(before.filterKeys { it != "assets" })
        // what 2.0 cannot hold is reported, unless it is the default (speed 1)
        assertThat(result.dropped).containsExactlyInAnyOrder("animations[].loop", "author")
    }

    @Test
    fun `keeps the active animation and reports 1_0 themes and states`() {
        val animation = """{"v":"5.7.0","fr":30,"ip":0,"op":10,"w":10,"h":10,"layers":[]}"""
        val v1 = zip(
            "manifest.json" to """{"version":"1.0","activeAnimationId":"second","animations":[{"id":"first","mode":"normal","direction":1},{"id":"second","speed":2}],"description":""}""",
            "animations/first.json" to animation,
            "animations/second.json" to animation,
            "themes/dark.lss" to "FillShape { fill-color: red; }",
            "audio/click.mp3" to "mp3",
        )
        val result = DotLottieMigration.toV2(v1, "test")!!
        val archive = DotLottieArchive.read(result.bytes)
        assertThat(archive.manifest.initialAnimation).isEqualTo("second")
        assertThat(archive.entries.map { it.path }).containsExactly("a/first.json", "a/second.json", "manifest.json", "u/click.mp3")
        // defaults and empty descriptive fields are not worth a mention
        assertThat(result.dropped).containsExactlyInAnyOrder("themes/ (dotLottie 1.0 themes)", "animations[].speed")
    }

    @Test
    fun `leaves dotLottie 2 archives alone`() {
        assertThat(DotLottieMigration.toV2(TestData.bytes("sample.lottie"), "test")).isNull()
    }
}
