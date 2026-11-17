package cz.xprees.lottie.dotlottie

import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.json.str
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts dotLottie 1.0 archives to the 2.0 layout (https://dotlottie.io/spec/2.0/):
 *
 * - `animations/<id>.json` → `a/<id>.json`, `images/` → `i/` (image assets are pointed at `/i/`), `audio/` → `u/`
 * - the manifest becomes a 2.0 manifest: `version` "2", the generator, the animations and `initial.animation` (from
 *   `activeAnimationId`)
 *
 * What 2.0 has no place for is left out and reported in [Result.dropped] (unless it holds the default value): per-animation playback
 * settings of the 1.0 manifest (`loop`, `autoplay`, `speed`, `direction`, `mode`/`playMode`, `hover`, `intermission`,
 * `themeColor`), its descriptive fields
 * (`author`, `description`, `keywords`, `revision`, `custom`), and 1.0-era `themes/` (Lottie style sheets) and `states/`, whose
 * formats differ from 2.0 themes and state machines. Any other file is copied unchanged.
 */
object DotLottieMigration {

    class Result(val bytes: ByteArray, val dropped: List<String>)

    /** 1.0 per-animation settings with their default values: a setting at its default loses nothing and is not reported. */
    private val PLAYBACK_DEFAULTS = linkedMapOf<String, Any?>(
        "loop" to false, "autoplay" to false, "speed" to 1.0, "direction" to 1.0, "mode" to "normal", "playMode" to "normal",
        "hover" to false, "intermission" to 0.0, "themeColor" to null,
    )
    private val META_KEYS = listOf("author", "description", "keywords", "revision", "custom")

    /** Null if [original] already is a dotLottie 2.0 archive. */
    fun toV2(original: ByteArray, generator: String): Result? {
        val archive = DotLottieArchive.read(original)
        if (archive.specVersion == 2) return null
        val files = archive.allFiles
        val manifest = files["manifest.json"]?.toString(Charsets.UTF_8)?.let(MiniJson::parseOrNull).obj()
        val dropped = LinkedHashSet<String>()

        // animations keep their ids when those are valid 2.0 ids
        val ids = LinkedHashMap<String, String>()
        for (animation in archive.manifest.animations) {
            var id = DotLottieWriter.sanitizeId(animation.id)
            var n = 1
            while (id in ids.values) id = "${DotLottieWriter.sanitizeId(animation.id)}_${n++}"
            ids[animation.id] = id
        }
        val images = files.keys.filter { it.startsWith("images/") }.associateWith { "i/" + it.removePrefix("images/") }

        val out = LinkedHashMap<String, ByteArray>()
        for ((oldId, newId) in ids) {
            val text = archive.animationText(oldId) ?: continue
            out["a/$newId.json"] = MiniJson.write(repointImages(MiniJson.parse(text), images.keys)).toByteArray()
        }
        if (out.isEmpty()) throw DotLottieException("The dotLottie file contains no animation")
        for ((path, bytes) in files) {
            when {
                path == "manifest.json" -> {}
                path.startsWith("animations/") -> if (path.removePrefix("animations/").removeSuffix(".json") !in ids) out[path] = bytes
                path.startsWith("images/") -> out[images.getValue(path)] = bytes
                path.startsWith("audio/") -> out["u/" + path.removePrefix("audio/")] = bytes
                path.startsWith("themes/") -> dropped += "themes/ (dotLottie 1.0 themes)"
                path.startsWith("states/") -> dropped += "states/ (dotLottie 1.0 state machines)"
                else -> out[path] = bytes
            }
        }

        manifest?.get("animations").arr()?.forEach { item ->
            val a = item.obj() ?: return@forEach
            PLAYBACK_DEFAULTS.filter { (key, default) -> a.containsKey(key) && !isDefault(a[key], default) }.forEach { dropped += "animations[].${it.key}" }
        }
        META_KEYS.filter { manifest?.containsKey(it) == true && !isEmpty(manifest[it]) }.forEach { dropped += it }

        val newManifest = linkedMapOf<String, Any?>(
            "version" to "2",
            "generator" to generator,
            "animations" to ids.values.map { linkedMapOf<String, Any?>("id" to it) },
        )
        archive.manifest.initialAnimation?.let(ids::get)?.let { newManifest["initial"] = linkedMapOf<String, Any?>("animation" to it) }

        val bytes = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.setMethod(ZipOutputStream.DEFLATED)
                fun put(path: String, data: ByteArray) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(data)
                    zip.closeEntry()
                }
                put("manifest.json", MiniJson.write(newManifest, pretty = true).toByteArray())
                out.forEach { (path, data) -> put(path, data) }
            }
        }.toByteArray()
        return Result(bytes, dropped.toList())
    }

    /** Image assets that point into `images/` point into `/i/` instead; embedded and external images stay as they are. */
    private fun repointImages(animation: Any?, imagePaths: Set<String>): Any? {
        val root = animation.obj() ?: return animation
        val assets = root["assets"].arr() ?: return animation
        val updated = assets.map { asset ->
            val a = asset.obj() ?: return@map asset
            val p = a["p"].str() ?: return@map asset
            if (a.containsKey("layers") || p.startsWith("data:")) return@map asset
            val joined = (a["u"].str().orEmpty() + p).trimStart('/')
            val inArchive = joined.takeIf { it in imagePaths } ?: "images/$p".takeIf { it in imagePaths } ?: return@map asset
            LinkedHashMap(a).apply {
                put("u", "/i/")
                put("p", inArchive.removePrefix("images/"))
            }
        }
        return LinkedHashMap(root).apply { put("assets", updated) }
    }

    private fun isDefault(value: Any?, default: Any?): Boolean = when {
        value is Number && default is Number -> value.toDouble() == default.toDouble()
        value is String && default == null -> value.isBlank()
        else -> value == default
    }

    private fun isEmpty(value: Any?) = value == null || (value is String && value.isBlank()) || (value is Collection<*> && value.isEmpty()) ||
        (value is Map<*, *> && value.isEmpty())
}
