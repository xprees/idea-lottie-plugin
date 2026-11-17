package cz.xprees.lottie.preview

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class TimelineScaleTest {
    private val scale = TimelineScale(left = 100, width = 590, lastFrame = 59.0)

    @Test
    fun `maps frames to pixels and back`() {
        assertThat(scale.x(0.0)).isEqualTo(100)
        assertThat(scale.x(59.0)).isEqualTo(690)
        assertThat(scale.x(29.5)).isEqualTo(395)
        assertThat(scale.frame(395)).isEqualTo(30.0) // 29.5 rounds to a whole frame
        assertThat(scale.frame(395, exact = true)).isEqualTo(29.5)
    }

    @Test
    fun `clamps to the timeline`() {
        assertThat(scale.frame(0)).isEqualTo(0.0)
        assertThat(scale.frame(10_000)).isEqualTo(59.0)
        assertThat(scale.x(-5.0)).isEqualTo(100)
        assertThat(scale.x(500.0)).isEqualTo(690)
    }

    @Test
    fun `picks readable tick steps`() {
        // 10 px per frame: labels 44 px apart need every 5th frame
        assertThat(scale.tickStep(44)).isEqualTo(5)
        assertThat(scale.tickStep(8)).isEqualTo(1)
        assertThat(TimelineScale(0, 300, 3000.0).tickStep(44)).isEqualTo(500)
        assertThat(TimelineScale(0, 0, 10.0).tickStep(44)).isEqualTo(1)
    }

    @Test
    fun `single-frame animations don't divide by zero`() {
        val single = TimelineScale(0, 100, 0.0)
        assertThat(single.frame(50)).isEqualTo(0.0)
        assertThat(single.x(0.0)).isEqualTo(0)
    }
}
