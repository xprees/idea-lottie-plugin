package cz.xprees.lottie.dotlottie

import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.num
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.json.str
import cz.xprees.lottie.model.LottieModel
import cz.xprees.lottie.statemachine.StateMachineContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One animation listed in a dotLottie manifest. */
data class DotLottieAnimation(
    val id: String,
    val initialTheme: String? = null,
    val themes: List<String> = emptyList(),
    /** u32 RGBA (dotLottie 2.0), or null. */
    val background: Long? = null,
)

/** A theme or a state machine listed in a dotLottie manifest. */
data class DotLottieAsset(val id: String, val name: String? = null) {
    val label get() = name?.takeIf { it.isNotBlank() } ?: id
}

data class DotLottieManifest(
    /** The manifest's `version`: "2" for dotLottie 2.0, "1"/"1.0" (or missing) for 1.0. */
    val version: String?,
    val generator: String?,
    val animations: List<DotLottieAnimation>,
    val themes: List<DotLottieAsset> = emptyList(),
    val stateMachines: List<DotLottieAsset> = emptyList(),
    val initialAnimation: String? = null,
    val initialStateMachine: String? = null,
    /** dotLottie 1.0 `author`. */
    val author: String? = null,
)

/** A file inside the archive. */
data class DotLottieEntry(val path: String, val size: Long) {
    val kind: String
        get() = when (path.substringBefore('/', "")) {
            "a", "animations" -> "Animation"
            "i", "images" -> "Image"
            "f" -> "Font"
            "u", "audio" -> "Audio"
            "t", "themes" -> "Theme"
            "s", "states" -> "State machine"
            else -> if (path == "manifest.json") "Manifest" else "Other"
        }
}

/**
 * A `.lottie` file read into memory: a Deflate ZIP with `manifest.json` and animations under `a/` (dotLottie 2.0) or
 * `animations/` (1.0), plus optional images (`i/`, `images/`), fonts (`f/`), audio (`u/`), themes (`t/`) and state machines (`s/`).
 *
 * See https://dotlottie.io/spec/2.0/.
 */
class DotLottieArchive private constructor(
    val manifest: DotLottieManifest,
    private val files: Map<String, ByteArray>,
) {
    val entries: List<DotLottieEntry> get() = files.map { (path, bytes) -> DotLottieEntry(path, bytes.size.toLong()) }.sortedBy { it.path }

    /** 2 for dotLottie 2.0 archives, 1 otherwise. */
    val specVersion: Int
        get() = if (manifest.version?.substringBefore('.') == "2" || files.keys.any { it.startsWith("a/") }) 2 else 1

    val defaultAnimationId: String? get() = manifest.initialAnimation?.takeIf { id -> manifest.animations.any { it.id == id } } ?: manifest.animations.firstOrNull()?.id

    fun bytes(path: String): ByteArray? = files[path]

    fun animationText(id: String): String? = (files["a/$id.json"] ?: files["animations/$id.json"])?.toString(Charsets.UTF_8)

    fun themeText(id: String): String? = files["t/$id.json"]?.toString(Charsets.UTF_8)

    fun stateMachineText(id: String): String? = files["s/$id.json"]?.toString(Charsets.UTF_8)

    /**
     * The Lottie JSON of animation [id] as a standalone document: image assets that point into the archive are embedded as data
     * URIs, so the result renders and edits without the archive.
     */
    fun extractAnimation(id: String, pretty: Boolean = true): String? {
        val text = animationText(id) ?: return null
        val root = MiniJson.parse(text).obj() ?: return text
        val assets = root["assets"].arr() ?: return if (pretty) MiniJson.write(root, true) else text
        val newAssets = assets.map { asset ->
            val a = asset.obj() ?: return@map asset
            val p = a["p"].str()
            if (p == null || a.containsKey("layers") || p.startsWith("data:") || a["e"].num() == 1.0) return@map asset
            val bytes = resolveImage(a["u"].str().orEmpty(), p) ?: return@map asset
            LinkedHashMap(a).apply {
                put("u", "")
                put("p", "data:${imageMime(p)};base64," + Base64.getEncoder().encodeToString(bytes))
                put("e", 1L)
            }
        }
        return MiniJson.write(LinkedHashMap(root).apply { put("assets", newAssets) }, pretty)
    }

    /** What state machines in this archive can refer to: animations, themes, markers and layer names. */
    fun stateMachineContext(): StateMachineContext {
        val markers = LinkedHashMap<String, Set<String>>()
        val layers = HashSet<String>()
        for (animation in manifest.animations) {
            val root = animationText(animation.id)?.let(MiniJson::parseOrNull).obj() ?: continue
            // a segment names a marker by the player's name for it (the raw comment) or its readable name
            markers[animation.id] = LottieModel.read(root)?.markers.orEmpty().flatMap { listOf(it.name, it.comment) }.toSet()
            collectLayerNames(root["layers"], layers)
            root["assets"].arr()?.forEach { collectLayerNames(it.obj()?.get("layers"), layers) }
        }
        val themes = manifest.themes.map { it.id }.toSet() + files.keys.filter { it.startsWith("t/") && it.endsWith(".json") }.map { it.removePrefix("t/").removeSuffix(".json") }
        return StateMachineContext(manifest.animations.map { it.id }.toSet(), themes, markers, layers)
    }

    private fun collectLayerNames(layers: Any?, into: MutableSet<String>) {
        layers.arr()?.forEach { layer -> layer.obj()?.get("nm").str()?.let(into::add) }
    }

    private fun resolveImage(u: String, p: String): ByteArray? {
        val joined = (u + p).trimStart('/')
        return files[joined] ?: files["i/$p"] ?: files["images/$p"]
    }

    companion object {
        /** Limits that keep a crafted archive (a ZIP bomb) from exhausting memory. */
        const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
        private const val MAX_ENTRIES = 10_000

        fun read(data: ByteArray): DotLottieArchive {
            val files = LinkedHashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (files.size >= MAX_ENTRIES) throw DotLottieException("Too many entries in the archive")
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    if (name.split('/').any { it == ".." }) continue
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var size = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        size += n
                        total += n
                        if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) throw DotLottieException("The archive is too large to preview")
                        out.write(buffer, 0, n)
                    }
                    files[name] = out.toByteArray()
                }
            }
            if (files.isEmpty()) throw DotLottieException("Not a dotLottie file: the ZIP archive is empty or unreadable")
            return DotLottieArchive(parseManifest(files), files)
        }

        internal fun parseManifest(files: Map<String, ByteArray>): DotLottieManifest {
            val json = files["manifest.json"]?.toString(Charsets.UTF_8)?.let(MiniJson::parseOrNull).obj()
            val listed = json?.get("animations").arr().orEmpty().mapNotNull { item ->
                val a = item.obj() ?: return@mapNotNull null
                val id = a["id"].str() ?: return@mapNotNull null
                DotLottieAnimation(
                    id = id,
                    initialTheme = a["initialTheme"].str(),
                    themes = a["themes"].arr().orEmpty().mapNotNull { it.str() },
                    background = (a["background"] as? Number)?.toLong(),
                )
            }
            // A manifest without animations (or no manifest at all): list the animation files themselves
            val animations = listed.ifEmpty {
                files.keys.filter { (it.startsWith("a/") || it.startsWith("animations/")) && it.endsWith(".json") && it.count { c -> c == '/' } == 1 }
                    .map { DotLottieAnimation(it.substringAfter('/').removeSuffix(".json")) }
            }
            if (animations.isEmpty()) throw DotLottieException("The dotLottie file contains no animation")
            fun assets(key: String) = json?.get(key).arr().orEmpty().mapNotNull { item ->
                val a = item.obj() ?: return@mapNotNull null
                DotLottieAsset(a["id"].str() ?: return@mapNotNull null, a["name"].str())
            }
            val themes = assets("themes").ifEmpty {
                files.keys.filter { it.startsWith("t/") && it.endsWith(".json") }.map { DotLottieAsset(it.removePrefix("t/").removeSuffix(".json")) }
            }
            val stateMachines = assets("stateMachines").ifEmpty {
                files.keys.filter { it.startsWith("s/") && it.endsWith(".json") }.map { DotLottieAsset(it.removePrefix("s/").removeSuffix(".json")) }
            }
            val initial = json?.get("initial").obj()
            return DotLottieManifest(
                version = json?.get("version")?.let { (it as? String) ?: it.toString() },
                generator = json?.get("generator").str(),
                animations = animations,
                themes = themes,
                stateMachines = stateMachines,
                initialAnimation = initial?.get("animation").str() ?: json?.get("activeAnimationId").str(),
                initialStateMachine = initial?.get("stateMachine").str(),
                author = json?.get("author").str(),
            )
        }

        internal fun imageMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "avif" -> "image/avif"
            else -> "image/png"
        }

        internal fun mimeExtension(mime: String): String = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            "image/avif" -> "avif"
            else -> "png"
        }
    }
}

class DotLottieException(message: String) : RuntimeException(message)

/** Builds dotLottie 2.0 archives from Lottie JSON. */
object DotLottieWriter {

    /**
     * [original] with the entries in [changes] replaced (or added; a null value removes the entry). Every other entry is copied
     * unchanged and in its original order.
     */
    fun replace(original: ByteArray, changes: Map<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        val written = HashSet<String>()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.DEFLATED)
            ZipInputStream(ByteArrayInputStream(original)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val bytes = if (name in changes) changes[name] else input.readBytes()
                    written += name
                    if (bytes == null) continue
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            for ((name, bytes) in changes) {
                if (name in written || bytes == null) continue
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val ID_CHARS = Regex("[^a-zA-Z0-9._ -]")

    /** A manifest/file id that follows the dotLottie pattern `^[a-zA-Z0-9._ -]+$`. */
    fun sanitizeId(name: String): String = name.replace(ID_CHARS, "_").trim().ifEmpty { "animation" }

    /**
     * Packs one animation into a dotLottie 2.0 archive. Images embedded as data URIs and images found by [resolveImage] (called
     * with the asset's `u` and `p`) are stored under `i/` and the assets rewritten to point there; anything unresolved stays as is.
     */
    fun pack(
        animationId: String,
        animation: Map<String, Any?>,
        generator: String,
        resolveImage: (u: String, p: String) -> ByteArray? = { _, _ -> null },
    ): ByteArray {
        val id = sanitizeId(animationId)
        val images = LinkedHashMap<String, ByteArray>()
        val assets = animation["assets"].arr()?.map { asset ->
            val a = asset.obj() ?: return@map asset
            val p = a["p"].str()
            if (p == null || a.containsKey("layers")) return@map asset
            val assetId = sanitizeId(a["id"].str() ?: "image_${images.size}")
            val (bytes, ext) = if (p.startsWith("data:")) {
                val mime = p.removePrefix("data:").substringBefore(';').substringBefore(',')
                val payload = p.substringAfter(',', "")
                val decoded = if (p.substringBefore(',').endsWith(";base64")) runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull()
                else java.net.URLDecoder.decode(payload, Charsets.UTF_8).toByteArray()
                decoded to DotLottieArchive.mimeExtension(mime)
            } else {
                resolveImage(a["u"].str().orEmpty(), p) to p.substringAfterLast('.', "png").lowercase()
            }
            if (bytes == null) return@map asset
            var name = "$assetId.$ext"
            var n = 1
            while (name in images) name = "${assetId}_${n++}.$ext"
            images[name] = bytes
            LinkedHashMap(a).apply {
                put("u", "/i/")
                put("p", name)
                put("e", 0L)
            }
        }
        val json = if (assets != null) LinkedHashMap(animation).apply { put("assets", assets) } else animation

        val manifest = linkedMapOf<String, Any?>(
            "version" to "2",
            "generator" to generator,
            "animations" to listOf(linkedMapOf<String, Any?>("id" to id)),
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.DEFLATED)
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("manifest.json", MiniJson.write(manifest, pretty = true).toByteArray())
            put("a/$id.json", MiniJson.write(json).toByteArray())
            images.forEach { (name, bytes) -> put("i/$name", bytes) }
        }
        return out.toByteArray()
    }
}
