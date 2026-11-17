package cz.xprees.lottie.editor

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonNumberLiteral
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.lang.LottieFiles
import cz.xprees.lottie.model.LottieNames
import cz.xprees.lottie.settings.LottieSettings
import java.util.Locale

/**
 * Readable hints next to Lottie's short keys and codes: layer and shape types (`"ty": 4` → Shape layer), blend and matte modes,
 * parent layer names, in/out points and keyframe times in seconds, and the layer name in front of each layer object (which stays
 * visible when the object is folded).
 */
class LottieInlayHintsProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file !is JsonFile || !LottieSettings.getInstance().state.inlayHints) return null
        val virtualFile = file.virtualFile ?: return null
        if (!LottieFiles.isLottieJson(virtualFile)) return null
        val frameRate = LottiePsi.root(file)?.let { LottiePsi.numberValue(it, "fr") }?.takeIf { it > 0 }
        return Collector(frameRate)
    }

    private class Collector(private val frameRate: Double?) : SharedBypassCollector {

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            when (element) {
                is JsonObject -> layerHeader(element, sink)
                is JsonProperty -> property(element, sink)
            }
        }

        /** The layer name in front of a multi-line layer object. */
        private fun layerHeader(obj: JsonObject, sink: InlayTreeSink) {
            if (!isLayer(obj) || !obj.text.contains('\n')) return
            val type = LottieNames.layerTypeOrNull(LottiePsi.numberValue(obj, "ty")?.toInt())
            val name = LottiePsi.stringValue(obj, "nm")?.takeIf { it.isNotBlank() }
            val label = listOfNotNull(type, name?.let { "“$it”" }).joinToString(" ").ifEmpty { return }
            hint(sink, obj.textRange.startOffset, label, relatedToPrevious = false)
        }

        private fun property(property: JsonProperty, sink: InlayTreeSink) {
            val value = property.value ?: return
            val obj = property.parent as? JsonObject ?: return
            val end = value.textRange.endOffset
            when (property.name) {
                "ty" -> when {
                    value is JsonNumberLiteral && isLayer(obj) -> LottieNames.layerTypeOrNull(value.value.toInt())?.let { hint(sink, end, it) }
                    value is JsonStringLiteral && isShape(obj) -> LottieNames.shapeType(value.value)?.let { hint(sink, end, it) }
                }
                "bm" -> if (value is JsonNumberLiteral && (isLayer(obj) || isShape(obj))) {
                    LottieNames.blendMode(value.value.toInt())?.takeIf { value.value.toInt() != 0 }?.let { hint(sink, end, it) }
                }
                "tt" -> if (value is JsonNumberLiteral && isLayer(obj)) LottieNames.matteMode(value.value.toInt())?.let { hint(sink, end, LottieBundle.message("inlay.matte", it)) }
                "parent" -> if (value is JsonNumberLiteral && isLayer(obj)) parentName(obj, value.value)?.let { hint(sink, end, "→ “$it”") }
                "ip", "op", "st" -> if (value is JsonNumberLiteral && (isLayer(obj) || obj.parent is JsonFile)) seconds(value.value)?.let { hint(sink, end, it) }
                "t" -> if (value is JsonNumberLiteral && isKeyframe(obj)) seconds(value.value)?.let { hint(sink, end, it) }
            }
        }

        private fun seconds(frames: Double): String? = frameRate?.let { String.format(Locale.ROOT, "%.2f s", frames / it) }

        private fun parentName(layer: JsonObject, ind: Double): String? {
            val layers = layer.parent as? JsonArray ?: return null
            val parent = layers.valueList.filterIsInstance<JsonObject>().firstOrNull { LottiePsi.numberValue(it, "ind") == ind } ?: return null
            return LottiePsi.stringValue(parent, "nm")
        }

        private fun hint(sink: InlayTreeSink, offset: Int, text: String, relatedToPrevious: Boolean = true) {
            sink.addPresentation(InlineInlayPosition(offset, relatedToPrevious, 0), null, null, HintFormat.default) { text(text) }
        }
    }

    companion object {
        /** An object in a `layers` array (root composition or precomposition asset). */
        fun isLayer(obj: JsonObject): Boolean = arrayKey(obj) == "layers"

        /** An object in a shape list (`shapes` of a shape layer, `it` of a group). */
        fun isShape(obj: JsonObject): Boolean = arrayKey(obj).let { it == "shapes" || it == "it" }

        /** A keyframe: an object in an animated property's `k` array. */
        fun isKeyframe(obj: JsonObject): Boolean = arrayKey(obj) == "k"

        private fun arrayKey(obj: JsonObject): String? = ((obj.parent as? JsonArray)?.parent as? JsonProperty)?.name
    }
}
