package cz.xprees.lottie.preview

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class PlaybackKeysTest {

    private fun key(key: String?, shift: Boolean = false, ctrl: Boolean = false, meta: Boolean = false, alt: Boolean = false, mac: Boolean = false) =
        PlaybackKeys.fromPage(key, shift, ctrl, meta, alt, mac)

    @Test
    fun `maps the page keys like the rest of the preview`() {
        assertThat(key(" ")).isEqualTo(PlaybackKey.TOGGLE)
        assertThat(key("ArrowLeft")).isEqualTo(PlaybackKey.PREVIOUS)
        assertThat(key("ArrowRight")).isEqualTo(PlaybackKey.NEXT)
        assertThat(key("ArrowLeft", shift = true)).isEqualTo(PlaybackKey.BACK_10)
        assertThat(key("ArrowRight", shift = true)).isEqualTo(PlaybackKey.FORWARD_10)
        assertThat(key("Home")).isEqualTo(PlaybackKey.FIRST)
        assertThat(key("End")).isEqualTo(PlaybackKey.LAST)
    }

    @Test
    fun `jumps to the first and last frame with Ctrl, or Cmd on macOS`() {
        assertThat(key("ArrowLeft", ctrl = true)).isEqualTo(PlaybackKey.FIRST)
        assertThat(key("ArrowRight", ctrl = true)).isEqualTo(PlaybackKey.LAST)
        assertThat(key("ArrowRight", meta = true)).isNull()
        assertThat(key("ArrowLeft", meta = true, mac = true)).isEqualTo(PlaybackKey.FIRST)
        assertThat(key("ArrowRight", meta = true, mac = true)).isEqualTo(PlaybackKey.LAST)
        // Ctrl+arrows switch desktops on macOS: not ours
        assertThat(key("ArrowRight", ctrl = true, mac = true)).isNull()
    }

    @Test
    fun `ignores other keys and combinations`() {
        assertThat(key("a")).isNull()
        assertThat(key(" ", ctrl = true)).isNull()
        assertThat(key("ArrowLeft", alt = true)).isNull()
        assertThat(key("ArrowLeft", shift = true, ctrl = true)).isNull()
        assertThat(key(null)).isNull()
    }
}
