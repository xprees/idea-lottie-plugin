package cz.xprees.lottie.json

/**
 * A small, dependency-free JSON reader and writer for Lottie data outside the editor's PSI: animations inside `.lottie` archives,
 * validity checks for the live preview, and the rewrites done by Extract/Pack.
 *
 * Values are plain Kotlin: [Map] (insertion ordered), [List], [String], [Double]/[Long] (integers without fraction or exponent stay
 * [Long] so ids and frame numbers are written back unchanged), [Boolean] and `null`.
 */
object MiniJson {

    class ParseException(message: String, val offset: Int, val line: Int, val column: Int) :
        RuntimeException("$message (line $line, column $column)")

    fun parse(text: CharSequence): Any? = Parser(text).parseDocument()

    /** Parses [text] and returns null instead of throwing. */
    fun parseOrNull(text: CharSequence): Any? = try {
        parse(text)
    } catch (_: ParseException) {
        null
    }

    /** The parse error of [text], or null when it is valid JSON. */
    fun errorOf(text: CharSequence): ParseException? = try {
        parse(text)
        null
    } catch (e: ParseException) {
        e
    }

    fun write(value: Any?, pretty: Boolean = false): String = StringBuilder().also { Writer(it, pretty).value(value, 0) }.toString()

    private class Parser(private val s: CharSequence) {
        private var i = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = value(0)
            skipWs()
            if (i < s.length) fail("Unexpected trailing content")
            return v
        }

        private fun value(depth: Int): Any? {
            if (depth > 512) fail("Nesting too deep")
            if (i >= s.length) fail("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("Unexpected character '$c'")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (peek() == '}') {
                i++
                return map
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("Expected a property name")
                val key = str()
                skipWs()
                if (peek() != ':') fail("Expected ':'")
                i++
                skipWs()
                map[key] = value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return map
                    }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skipWs()
            if (peek() == ']') {
                i++
                return list
            }
            while (true) {
                skipWs()
                list += value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return list
                    }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("Unterminated string")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad unicode escape")
                                val hex = s.subSequence(i, i + 4).toString()
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("Bad unicode escape"))
                                i += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("Control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Number {
            val start = i
            if (peek() == '-') i++
            var integral = true
            while (i < s.length && s[i] in '0'..'9') i++
            if (i < s.length && s[i] == '.') {
                integral = false
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.subSequence(start, i).toString()
            if (text == "-" || text.isEmpty()) fail("Bad number")
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull() ?: fail("Bad number '$text'")
        }

        private fun literal(word: String, v: Any?): Any? {
            if (i + word.length > s.length || s.subSequence(i, i + word.length).toString() != word) fail("Unexpected token")
            i += word.length
            return v
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun fail(message: String): Nothing {
            var line = 1
            var col = 1
            for (k in 0 until minOf(i, s.length)) {
                if (s[k] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            throw ParseException(message, i, line, col)
        }
    }

    private class Writer(private val out: StringBuilder, private val pretty: Boolean) {
        fun value(v: Any?, indent: Int) {
            when (v) {
                null -> out.append("null")
                is String -> string(v)
                is Boolean -> out.append(v)
                is Long, is Int, is Short, is Byte -> out.append(v)
                is Number -> number(v.toDouble())
                is Map<*, *> -> obj(v, indent)
                is List<*> -> arr(v, indent)
                else -> string(v.toString())
            }
        }

        private fun number(d: Double) {
            when {
                d.isNaN() || d.isInfinite() -> out.append('0')
                d == Math.rint(d) && kotlin.math.abs(d) < 1e15 -> out.append(d.toLong())
                else -> out.append(d.toString().let { if ('E' in it) java.math.BigDecimal(d).stripTrailingZeros().toPlainString() else it })
            }
        }

        private fun obj(m: Map<*, *>, indent: Int) {
            if (m.isEmpty()) {
                out.append("{}")
                return
            }
            out.append('{')
            var first = true
            for ((k, v) in m) {
                if (!first) out.append(',')
                first = false
                newline(indent + 1)
                string(k.toString())
                out.append(if (pretty) ": " else ":")
                value(v, indent + 1)
            }
            newline(indent)
            out.append('}')
        }

        private fun arr(l: List<*>, indent: Int) {
            if (l.isEmpty()) {
                out.append("[]")
                return
            }
            // numeric arrays (colors, vectors, keyframe values) stay on one line even when pretty printing
            val flat = l.all { it == null || it is Number || it is Boolean }
            out.append('[')
            l.forEachIndexed { index, v ->
                if (index > 0) out.append(if (pretty && flat) ", " else ",")
                if (!flat) newline(indent + 1)
                value(v, indent + 1)
            }
            if (!flat) newline(indent)
            out.append(']')
        }

        private fun newline(indent: Int) {
            if (!pretty) return
            out.append('\n')
            repeat(indent) { out.append("  ") }
        }

        private fun string(s: String) {
            out.append('"')
            for (c in s) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
            out.append('"')
        }
    }
}

/** Navigation helpers over [MiniJson] values. */
@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>

internal fun Any?.arr(): List<Any?>? = this as? List<Any?>

internal fun Any?.num(): Double? = (this as? Number)?.toDouble()

internal fun Any?.str(): String? = this as? String
