package cz.xprees.lottie.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieFiles
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.num
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.json.str
import cz.xprees.lottie.model.MarkerNames

/** Adds, renames, moves and deletes the markers of an animation; each call is one undoable change. */
interface MarkerEditor {
    fun add(name: String, start: Double, duration: Double = 0.0)
    fun delete(index: Int)
    fun rename(index: Int, name: String)
    fun setRange(index: Int, start: Double, duration: Double)
}

/** The marker edits on a [MiniJson] tree's root `markers` array (mutable maps and lists), shared by the JSON and archive editors. */
object MarkerEdits {

    /** The root `markers` list of [root], created when missing. */
    @Suppress("UNCHECKED_CAST")
    fun markers(root: MutableMap<String, Any?>): MutableList<Any?> =
        root["markers"] as? MutableList<Any?> ?: ArrayList<Any?>().also { root["markers"] = it }

    /** Adds a marker, keeping the list sorted by start frame. */
    fun add(markers: MutableList<Any?>, name: String, start: Double, duration: Double) {
        val entry = linkedMapOf<String, Any?>("cm" to name, "tm" to whole(start), "dr" to whole(duration))
        val at = markers.indexOfFirst { (it.obj()?.get("tm").num() ?: 0.0) > start }.let { if (it < 0) markers.size else it }
        markers.add(at, entry)
    }

    fun delete(markers: MutableList<Any?>, index: Int) {
        if (index in markers.indices) markers.removeAt(index)
    }

    /** Renames marker [index], keeping an After Effects JSON comment (`{"name": ...}`) in that form. */
    fun rename(markers: MutableList<Any?>, index: Int, name: String) = update(markers, index) { it["cm"] = MarkerNames.rename(it["cm"].str(), name) }

    fun setRange(markers: MutableList<Any?>, index: Int, start: Double, duration: Double) = update(markers, index) {
        it["tm"] = whole(start)
        it["dr"] = whole(duration.coerceAtLeast(0.0))
    }

    private fun update(markers: MutableList<Any?>, index: Int, change: (MutableMap<String, Any?>) -> Unit) {
        val marker = markers.getOrNull(index).obj() ?: return
        markers[index] = LinkedHashMap(marker).also(change)
    }

    /** Integral frame numbers are written without a fraction (`30`, not `30.0`). */
    fun whole(frame: Double): Any = if (frame == Math.rint(frame)) frame.toLong() else frame
}

/**
 * Edits the markers of the animation [animationId] gives (the one the preview shows) inside the `.lottie` [file]: its JSON entry is
 * read, changed and written back, as one undoable change of the archive.
 */
class DotLottieMarkerEditor(private val project: Project, private val file: VirtualFile, private val animationId: () -> String?) : MarkerEditor {

    override fun add(name: String, start: Double, duration: Double) = edit(LottieBundle.message("marker.command.add")) { MarkerEdits.add(it, name, start, duration) }

    override fun delete(index: Int) = edit(LottieBundle.message("marker.command.delete")) { MarkerEdits.delete(it, index) }

    override fun rename(index: Int, name: String) = edit(LottieBundle.message("marker.command.rename")) { MarkerEdits.rename(it, index, name) }

    override fun setRange(index: Int, start: Double, duration: Double) =
        edit(LottieBundle.message("marker.command.move")) { MarkerEdits.setRange(it, index, start, duration) }

    private fun edit(command: String, change: (MutableList<Any?>) -> Unit) {
        DotLottieFiles.rewrite(project, file, command) { bytes ->
            val archive = DotLottieArchive.read(bytes)
            val id = animationId() ?: archive.defaultAnimationId ?: return@rewrite bytes
            val path = listOf("a/$id.json", "animations/$id.json").firstOrNull { archive.bytes(it) != null } ?: return@rewrite bytes
            @Suppress("UNCHECKED_CAST")
            val root = MiniJson.parse(archive.bytes(path)!!.toString(Charsets.UTF_8)) as? MutableMap<String, Any?> ?: return@rewrite bytes
            val markers = MarkerEdits.markers(root)
            val before = markers.toList()
            change(markers)
            if (markers == before) return@rewrite bytes
            DotLottieWriter.replace(bytes, mapOf(path to MiniJson.write(root).toByteArray(Charsets.UTF_8)))
        }
    }
}
