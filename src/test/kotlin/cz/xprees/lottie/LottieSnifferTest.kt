package cz.xprees.lottie

import cz.xprees.lottie.detect.LottieSniffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class LottieSnifferTest {
    private fun sniff(text: String) = LottieSniffer.looksLikeLottie(text.toByteArray())

    @Test
    fun `recognizes a Lottie export`() {
        assertThat(LottieSniffer.looksLikeLottie(TestData.bytes("sample.json"))).isTrue()
    }

    @Test
    fun `rejects other JSON`() {
        assertThat(LottieSniffer.looksLikeLottie(TestData.bytes("not-lottie.json"))).isFalse()
    }

    @Test
    fun `skips huge embedded assets before the header keys`() {
        val bytes = TestData.bytes("assets-first.json")
        assertThat(bytes.size).isGreaterThan(LottieSniffer.WINDOW)
        // the image string runs past the window, so only keys before it are seen: not enough
        assertThat(LottieSniffer.looksLikeLottie(bytes, LottieSniffer.WINDOW)).isFalse()
        assertThat(LottieSniffer.looksLikeLottie(bytes)).isTrue()
    }

    @Test
    fun `only top-level keys count`() {
        assertThat(sniff("""{"meta":{"v":"5","fr":30,"ip":0,"op":10,"w":1,"h":1,"layers":[]}}""")).isFalse()
        assertThat(sniff("""{"v":"5","fr":30,"ip":0,"op":10,"w":1,"h":1,"layers":[]}""")).isTrue()
    }

    @Test
    fun `needs frame rate and in and out points`() {
        assertThat(sniff("""{"v":"5","w":1,"h":1,"layers":[],"nm":"x","op":3}""")).isFalse()
        assertThat(sniff("""{"fr":30,"ip":0,"op":10,"w":1,"h":1}""")).isTrue()
    }

    @Test
    fun `keys inside string values are ignored`() {
        assertThat(sniff("""{"nm":"\"fr\":1,\"ip\":0","x":"\"op\" \"w\" \"h\"","fr":1}""")).isFalse()
    }

    @Test
    fun `handles a byte order mark and whitespace`() {
        assertThat(LottieSniffer.looksLikeLottie(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\n  {\"fr\":1,\"ip\":0,\"op\":1,\"w\":1,\"h\":1}".toByteArray())).isTrue()
    }

    @Test
    fun `rejects non-object roots and truncated input`() {
        assertThat(sniff("""[{"fr":1,"ip":0,"op":1,"w":1,"h":1}]""")).isFalse()
        assertThat(sniff("")).isFalse()
        assertThat(sniff("""{"fr":1,"ip""")).isFalse()
    }
}
