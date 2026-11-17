package cz.xprees.lottie

import cz.xprees.lottie.json.MiniJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class MiniJsonTest {
    @Test
    fun `parses and writes back`() {
        val text = """{"a":1,"b":[1.5,-2,3e2,true,null],"c":{"d":"x\"yé\n"}}"""
        val value = MiniJson.parse(text)
        assertThat(MiniJson.write(value)).isEqualTo("""{"a":1,"b":[1.5,-2,300,true,null],"c":{"d":"x\"yé\n"}}""")
    }

    @Test
    fun `integers stay integers`() {
        val value = MiniJson.parse("""{"ind":12345678901,"t":0}""") as Map<*, *>
        assertThat(value["ind"]).isEqualTo(12345678901L)
        assertThat(MiniJson.write(value)).isEqualTo("""{"ind":12345678901,"t":0}""")
    }

    @Test
    fun `pretty printing keeps numeric arrays on one line`() {
        assertThat(MiniJson.write(MiniJson.parse("""{"k":[1,0.5,0],"o":{"a":0}}"""), pretty = true))
            .isEqualTo("{\n  \"k\": [1, 0.5, 0],\n  \"o\": {\n    \"a\": 0\n  }\n}")
    }

    @Test
    fun `reports the position of an error`() {
        val error = MiniJson.errorOf("{\n  \"a\": 1,\n  \"b\": ]\n}")
        assertThat(error).isNotNull
        assertThat(error!!.line).isEqualTo(3)
        assertThat(error.column).isEqualTo(8)
        assertThat(MiniJson.errorOf("""{"a":[1,2]}""")).isNull()
    }

    @Test
    fun `rejects trailing content and bad literals`() {
        assertThatThrownBy { MiniJson.parse("{} x") }.isInstanceOf(MiniJson.ParseException::class.java)
        assertThatThrownBy { MiniJson.parse("[tru]") }.isInstanceOf(MiniJson.ParseException::class.java)
        assertThatThrownBy { MiniJson.parse("[1,]") }.isInstanceOf(MiniJson.ParseException::class.java)
    }
}
