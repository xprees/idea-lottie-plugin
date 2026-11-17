package cz.xprees.lottie.detect

/**
 * Decides from the first bytes of a `.json` file whether it is a Lottie animation, without parsing the whole document.
 *
 * Only the top-level keys of the root object are collected; nested objects, arrays and strings (often huge base64 image assets)
 * are skipped. The Lottie spec requires `fr`, `ip`, `op`, `w`, `h` and `layers` on the root, and exporters add `v`. A file counts
 * as Lottie once [MIN_KEYS] of these are seen and `fr`, `ip` and `op` are among them; exporters write the header keys first, so a
 * small window is normally enough.
 */
object LottieSniffer {

    /** Bytes read from the start of a file. */
    const val WINDOW = 64 * 1024

    private val LOTTIE_KEYS = setOf("v", "fr", "ip", "op", "w", "h", "layers")
    private val REQUIRED = setOf("fr", "ip", "op")
    private const val MIN_KEYS = 5

    fun looksLikeLottie(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val keys = topLevelKeys(bytes, minOf(length, bytes.size)) ?: return false
        return matches(keys)
    }

    internal fun matches(keys: Set<String>): Boolean {
        val found = keys.intersect(LOTTIE_KEYS)
        return found.size >= MIN_KEYS && found.containsAll(REQUIRED)
    }

    /**
     * Top-level keys seen within [length] bytes, or null when the content doesn't start with a JSON object. Scanning stops early
     * once the keys already identify a Lottie file.
     */
    internal fun topLevelKeys(bytes: ByteArray, length: Int): Set<String>? {
        var i = 0
        // UTF-8 byte order mark
        if (length >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < length && bytes[i].isWs()) i++
        if (i >= length || bytes[i] != '{'.code.toByte()) return null
        i++

        val keys = HashSet<String>()
        var depth = 1
        var expectKey = true
        while (i < length) {
            val b = bytes[i]
            when (b.toInt().toChar()) {
                '"' -> {
                    val start = i + 1
                    i = skipString(bytes, start, length)
                    if (i < 0) return keys
                    if (depth == 1 && expectKey) {
                        keys += String(bytes, start, i - start - 1, Charsets.UTF_8)
                        if (matches(keys)) return keys
                        expectKey = false
                    }
                    continue
                }
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return keys
                }
                ',' -> if (depth == 1) expectKey = true
                ':' -> if (depth == 1) expectKey = false
            }
            i++
        }
        return keys
    }

    /** Index just past the closing quote of the string whose content starts at [start], or -1 when it runs past [length]. */
    private fun skipString(bytes: ByteArray, start: Int, length: Int): Int {
        var i = start
        while (i < length) {
            when (bytes[i]) {
                '\\'.code.toByte() -> i += 2
                '"'.code.toByte() -> return i + 1
                else -> i++
            }
        }
        return -1
    }

    private fun Byte.isWs() = this == ' '.code.toByte() || this == '\n'.code.toByte() || this == '\r'.code.toByte() || this == '\t'.code.toByte()
}
