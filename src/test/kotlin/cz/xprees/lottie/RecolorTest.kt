package cz.xprees.lottie

import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.palette.LottieColor
import cz.xprees.lottie.palette.PaletteEntry
import cz.xprees.lottie.palette.PaletteScanner
import cz.xprees.lottie.palette.Recolor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class RecolorTest {
    private fun root() = MiniJson.parse(TestData.text("sample.json"))
    private fun palette(root: Any?) = PaletteScanner.palette(PaletteScanner.scan(root))
    private fun entry(root: Any?, hex: String): PaletteEntry = palette(root).single { it.color.hex == hex }
    private val green = LottieColor.fromHex("#00ff00")!!

    @Test
    fun `every use of a color changes, including keyframes, gradient stops and solid layers`() {
        for (hex in listOf("#ffffff", "#ff3333", "#ff0000", "#ffcc00")) {
            val root = root()
            val before = entry(root, hex)
            val greenBefore = palette(root).firstOrNull { it.color.hex == "#00ff00" }?.count ?: 0
            Recolor.apply(root, Recolor.edits(before.usages, green))
            val after = palette(root)
            assertThat(after.map { it.color.hex }).describedAs(hex).doesNotContain(hex)
            assertThat(after.single { it.color.hex == "#00ff00" }.count).describedAs(hex).isEqualTo(greenBefore + before.count)
        }
    }

    @Test
    fun `edits change single numbers so the file keeps its layout`() {
        val root = root()
        val red = entry(root, "#ff0000") // gradient stop 0 of [0, 1, 0, 0,  1, 0, 0, 1,  0, 1, 1, 1]
        val edits = Recolor.edits(red.usages, LottieColor.fromHex("#336699")!!)
        assertThat(edits.map { it.path.last() }).containsExactly(1, 2, 3, 9)
        assertThat(edits.map { it.text }).containsExactly("0.2", "0.4", "0.6", "1")
        Recolor.apply(root, edits)
        assertThat(Recolor.resolve(root, red.usages.single().path)).isEqualTo(listOf(0L, 0.2, 0.4, 0.6, 1L, 0L, 0L, 1L, 0L, 1L, 1L, 1L))
    }

    @Test
    fun `a translucent color adds alpha to rgb values`() {
        val root = MiniJson.parse("""{"layers":[{"ty":4,"shapes":[{"ty":"fl","c":{"a":0,"k":[1,0,0]}}]}]}""")
        val red = entry(root, "#ff0000")
        val edits = Recolor.edits(red.usages, LottieColor.fromHex("#00ff0080")!!)
        assertThat(edits.single().text).isEqualTo("[0, 1, 0, 0.502]")
    }

    @Test
    fun `a theme turns the recolored properties into slots and sets them`() {
        val animation = root().obj()!!
        val from = entry(animation, "#ff3333") // the first keyframe of an animated fill
        val result = Recolor.toTheme(animation, from.usages, from.color, green, null, "sample", limitToAnimation = false)
        assertThat(result.themed).isEqualTo(1)
        assertThat(result.skipped).isEmpty()

        // the animation looks the same without the theme
        assertThat(palette(result.animation).map { it.color.hex }).contains("#ff3333")
        val fill = Recolor.resolve(result.animation, listOf("layers", 0, "shapes", 0, "it", 1, "c")).obj()!!
        val sid = fill["sid"] as String
        assertThat(sid).isEqualTo("color_ff3333_2")
        assertThat(result.animation["slots"].obj()!![sid].obj()!!["p"].obj()!!["k"].arr()).hasSize(2)

        // the theme replaces that keyframe's color and keeps the other keyframe and the easing
        val rule = result.theme["rules"].arr()!!.single().obj()!!
        assertThat(rule["id"]).isEqualTo(sid)
        assertThat(rule["type"]).isEqualTo("Color")
        val keyframes = rule["keyframes"].arr()!!.map { it.obj()!! }
        assertThat(keyframes.map { it["frame"] }).containsExactly(0L, 59L)
        assertThat(keyframes[0]["value"]).isEqualTo(listOf(0L, 1L, 0L, 1L))
        assertThat(keyframes[1]["value"]).isEqualTo(listOf(0.2, 0.4, 1L, 1L))
        assertThat(keyframes[0]).containsKeys("inTangent", "outTangent")

        // with the theme, the palette shows the new color
        val themed = Recolor.themed(result.animation, result.theme, "sample")
        assertThat(palette(themed).map { it.color.hex }).contains("#00ff00").doesNotContain("#ff3333")
    }

    @Test
    fun `a theme reuses existing slots, shares slots between equal static colors and skips what it can't set`() {
        val animation = root().obj()!!
        val accent = entry(animation, "#009980")
        val result = Recolor.toTheme(animation, accent.usages, accent.color, green, null, "sample", limitToAnimation = true)
        val rule = result.theme["rules"].arr()!!.single().obj()!!
        assertThat(rule["id"]).isEqualTo("accent")
        assertThat(rule["value"]).isEqualTo(listOf(0L, 1L, 0L, 1L))
        assertThat(rule["animations"]).isEqualTo(listOf("sample"))

        // a second recolor of the same slot in the same theme updates the rule
        val again = Recolor.toTheme(result.animation, palette(Recolor.themed(result.animation, result.theme, "sample")).single { it.color.hex == "#00ff00" }.usages,
            green, LottieColor.fromHex("#0000ff")!!, result.theme, "sample", limitToAnimation = true)
        assertThat(again.theme["rules"].arr()!!.single().obj()!!["value"]).isEqualTo(listOf(0L, 0L, 1L, 1L))

        val solid = entry(animation, "#ffcc00")
        val skipped = Recolor.toTheme(animation, solid.usages, solid.color, green, null, "sample", limitToAnimation = false)
        assertThat(skipped.themed).isZero()
        assertThat(skipped.skipped).hasSize(1)

        val two = MiniJson.parse("""{"layers":[{"ty":4,"shapes":[{"ty":"fl","c":{"a":0,"k":[1,0,0,1]}},{"ty":"st","c":{"a":0,"k":[1,0,0,1]}}]}]}""").obj()!!
        val red = entry(two, "#ff0000")
        val shared = Recolor.toTheme(two, red.usages, red.color, green, null, null, limitToAnimation = false)
        assertThat(shared.animation["slots"].obj()!!.keys).containsExactly("color_ff0000")
        assertThat(shared.theme["rules"].arr()).hasSize(1)
    }

    @Test
    fun `the highlight mask makes one color white and every other color black`() {
        val root = root()
        val colors = palette(root)
        val white = colors.single { it.color.hex == "#ffffff" }
        val mask = Recolor.mask(root, colors, white)
        val masked = palette(mask)
        assertThat(masked.map { it.color.hex }).containsOnly("#ffffff", "#000000")
        assertThat(masked.single { it.color.hex == "#ffffff" }.count).isEqualTo(white.count)
        // the original is untouched
        assertThat(palette(root).map { it.color.hex }).isEqualTo(colors.map { it.color.hex })
    }

    @Test
    fun `the manifest lists a new theme once`() {
        val manifest = MiniJson.parse("""{"version":"2","animations":[{"id":"a","themes":["old"]}],"themes":[{"id":"old"}]}""").obj()!!
        val m = Recolor.manifestWithTheme(manifest, "night", "Night mode", "a")
        assertThat(m["themes"].arr()!!.map { it.obj()!!["id"] }).containsExactly("old", "night")
        assertThat(m["themes"].arr()!![1].obj()!!["name"]).isEqualTo("Night mode")
        assertThat(m["animations"].arr()!![0].obj()!!["themes"]).isEqualTo(listOf("old", "night"))
        assertThat(Recolor.manifestWithTheme(m, "night", null, "a")["themes"].arr()).hasSize(2)
    }
}
