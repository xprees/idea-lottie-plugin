package cz.xprees.lottie

import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.model.LottieModel
import cz.xprees.lottie.palette.ColorKind
import cz.xprees.lottie.palette.LottieColor
import cz.xprees.lottie.palette.PaletteScanner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class PaletteTest {
    private val usages = PaletteScanner.scan(MiniJson.parse(TestData.text("sample.json")))

    @Test
    fun `finds every kind of color`() {
        assertThat(usages.map { it.kind }.toSet()).containsExactlyInAnyOrder(
            ColorKind.FILL, ColorKind.STROKE, ColorKind.GRADIENT, ColorKind.SOLID, ColorKind.SLOT,
        )
        val fills = usages.filter { it.kind == ColorKind.FILL }
        // two animated keyframes on the circle, plus the slotted bar
        assertThat(fills.map { it.color.hex }).containsExactly("#ff3333", "#3366ff", "#009980")
        assertThat(fills.first().animated).isTrue()
        assertThat(fills.first().owner).isEqualTo("Circle › Circle group › Fill")
        assertThat(fills.first().path).isEqualTo(listOf("layers", 0, "shapes", 0, "it", 1, "c", "k", 0, "s"))
    }

    @Test
    fun `gradients list one color per stop and are not editable`() {
        val gradient = usages.filter { it.kind == ColorKind.GRADIENT }
        assertThat(gradient.map { it.color.hex }).containsExactly("#ff0000", "#0000ff")
        assertThat(gradient.map { it.gradientStop }).containsExactly(0, 1)
        assertThat(gradient.none { it.editable }).isTrue()
        assertThat(gradient.first().path.take(3)).isEqualTo(listOf("assets", 0, "layers"))
    }

    @Test
    fun `solid layer colors and referenced slots`() {
        assertThat(usages.single { it.kind == ColorKind.SOLID }.let { it.color.hex to it.path }).isEqualTo("#ffcc00" to listOf("layers", 4, "sc"))
        assertThat(usages.single { it.kind == ColorKind.SLOT }.slotId).isEqualTo("accent")
    }

    @Test
    fun `palette groups equal colors`() {
        val palette = PaletteScanner.palette(usages)
        val teal = palette.first { it.color.hex == "#009980" }
        assertThat(teal.count).isEqualTo(2)
        assertThat(teal.kinds).containsExactlyInAnyOrder(ColorKind.FILL, ColorKind.SLOT)
        assertThat(PaletteScanner.editableArrayPaths(usages)).contains(listOf("layers", 0, "shapes", 0, "it", 2, "c", "k"))
    }

    @Test
    fun `color conversions`() {
        val c = LottieColor.fromComponents(listOf(1.0, 0.5, 0.0))!!
        assertThat(c.hex).isEqualTo("#ff8000")
        assertThat(c.toJsonArray()).isEqualTo("[1, 0.5, 0]")
        val legacy = LottieColor.fromComponents(listOf(255.0, 128.0, 0.0, 255.0))!!
        assertThat(legacy.scale).isEqualTo(255.0)
        assertThat(LottieColor.fromRgba8(0, 51, 255, 255, legacy).toJsonArray()).isEqualTo("[0, 51, 255, 255]")
        assertThat(LottieColor.fromRgba8(255, 0, 0, 128, c).toJsonArray()).isEqualTo("[1, 0, 0, 0.502]")
        assertThat(LottieColor.fromComponents(listOf(0.2, 2.0, 0.3))).isNotNull // 0..255 scale
        assertThat(LottieColor.fromComponents(listOf(0.2, 300.0, 0.3))).isNull()
        assertThat(LottieColor.fromComponents(listOf(1.0, 1.0))).isNull()
        assertThat(LottieColor.fromHex("#0f8")!!.hex).isEqualTo("#00ff88")
        assertThat(LottieColor.fromHex("#11223380")!!.hex).isEqualTo("#11223380")
        assertThat(LottieColor.formatComponent(0.23529411)).isEqualTo("0.2353")
    }

    @Test
    fun `model reads header, layers and markers`() {
        val info = LottieModel.read(TestData.text("sample.json"))!!
        assertThat(info.width).isEqualTo(200.0)
        assertThat(info.frameRate).isEqualTo(30.0)
        assertThat(info.durationSeconds).isEqualTo(2.0)
        assertThat(info.layers.map { it.label }).containsExactly("Circle", "Badge", "Controller", "Slotted", "Background")
        assertThat(info.layers.map { it.typeName }).containsExactly("Shape", "Precomposition", "Null", "Shape", "Solid")
        assertThat(info.markers.map { it.name }).containsExactly("intro", "outro")
        assertThat(info.slotIds).containsExactly("accent")
    }
}
