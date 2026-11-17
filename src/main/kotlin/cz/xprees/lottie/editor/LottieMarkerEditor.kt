package cz.xprees.lottie.editor

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.num
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.json.str
import cz.xprees.lottie.model.MarkerNames

/**
 * Edits the markers of a Lottie JSON document: the root `markers` array of `{"cm": name, "tm": start frame, "dr": duration}`
 * objects. Markers are how Lottie names moments and segments for code: players look them up by name (`cm`) to play a segment or to
 * react when playback reaches it.
 *
 * Each edit is one undoable command. Markers that aren't edited keep their text (and any extra properties); the array is reformatted
 * with the project's JSON code style. Frames are composition frames (`tm`), not relative to the in point.
 */
class LottieMarkerEditor(private val project: Project, private val document: Document) {

    /** Adds a marker and keeps the array sorted by start frame. */
    fun add(name: String, start: Double, duration: Double = 0.0) = edit(LottieBundle.message("marker.command.add")) { markers ->
        val entry = MiniJson.write(linkedMapOf<String, Any?>("cm" to name, "tm" to whole(start), "dr" to whole(duration)))
        val at = markers.indexOfFirst { (MiniJson.parseOrNull(it).obj()?.get("tm").num() ?: 0.0) > start }.let { if (it < 0) markers.size else it }
        markers.add(at, entry)
    }

    fun delete(index: Int) = edit(LottieBundle.message("marker.command.delete")) { markers ->
        if (index in markers.indices) markers.removeAt(index)
    }

    /** Renames marker [index], keeping an After Effects JSON comment (`{"name": ...}`) in that form. */
    fun rename(index: Int, name: String) = update(index, LottieBundle.message("marker.command.rename")) { marker ->
        marker["cm"] = MarkerNames.rename(marker["cm"].str(), name)
    }

    /** Moves marker [index] to [start] with [duration] (both in composition frames). */
    fun setRange(index: Int, start: Double, duration: Double) = update(index, LottieBundle.message("marker.command.move")) { marker ->
        marker["tm"] = whole(start)
        marker["dr"] = whole(duration.coerceAtLeast(0.0))
    }

    private fun update(index: Int, command: String, change: (MutableMap<String, Any?>) -> Unit) = edit(command) { markers ->
        val marker = markers.getOrNull(index)?.let(MiniJson::parseOrNull).obj() ?: return@edit
        markers[index] = MiniJson.write(LinkedHashMap(marker).also(change))
    }

    /** Integral frame numbers are written without a fraction (`30`, not `30.0`). */
    private fun whole(frame: Double): Any = if (frame == Math.rint(frame)) frame.toLong() else frame

    /**
     * Runs [change] on the texts of the marker objects and writes the result back as the root `markers` array, creating it when
     * the document has none. Does nothing when the document isn't a JSON object.
     */
    private fun edit(command: String, change: (MutableList<String>) -> Unit) {
        val manager = PsiDocumentManager.getInstance(project)
        val psi = manager.getPsiFile(document) ?: return
        // Everything happens in the write command: committing the document needs the write lock, and edits come from plain Swing
        // listeners (timeline menu, drag) that don't hold it
        WriteCommandAction.writeCommandAction(project, psi).withName(command).run<RuntimeException> {
            manager.commitDocument(document)
            val file = manager.getPsiFile(document) as? JsonFile ?: return@run
            val root = file.topLevelValue as? JsonObject ?: return@run
            val property = root.findProperty("markers")
            val array = property?.value as? JsonArray
            val markers = array?.valueList?.map { it.text }?.toMutableList() ?: mutableListOf()
            val before = markers.toList()
            change(markers)
            if (markers == before) return@run
            val arrayText = if (markers.isEmpty()) "[]" else markers.joinToString(",\n", "[\n", "\n]")
            val value = property?.value
            val (start, end, text) = when {
                value != null -> Triple(value.textRange.startOffset, value.textRange.endOffset, arrayText)
                else -> {
                    val last = root.propertyList.lastOrNull()
                    if (last != null) Triple(last.textRange.endOffset, last.textRange.endOffset, ",\n\"markers\": $arrayText")
                    else (root.textRange.startOffset + 1).let { Triple(it, it, "\n\"markers\": $arrayText\n") }
                }
            }
            document.replaceString(start, end, text)
            manager.commitDocument(document)
            manager.getPsiFile(document)?.let { CodeStyleManager.getInstance(project).reformatText(it, start, start + text.length) }
        }
    }
}
