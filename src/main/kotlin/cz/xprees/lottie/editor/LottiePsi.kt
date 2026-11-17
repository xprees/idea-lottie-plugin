package cz.xprees.lottie.editor

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonNumberLiteral
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import cz.xprees.lottie.model.JsonPath

/** Navigation between JSON PSI and [JsonPath]s / Lottie structure. */
object LottiePsi {

    fun root(file: JsonFile): JsonObject? = file.topLevelValue as? JsonObject

    fun findByPath(file: JsonFile, path: JsonPath): JsonValue? {
        var current: JsonValue = file.topLevelValue ?: return null
        for (step in path) {
            current = when (step) {
                is String -> (current as? JsonObject)?.findProperty(step)?.value
                is Int -> (current as? JsonArray)?.valueList?.getOrNull(step)
                else -> null
            } ?: return null
        }
        return current
    }

    /** The path of [value] from the document root, or null when it isn't inside the top-level value. */
    fun pathOf(value: JsonValue): JsonPath? {
        val steps = ArrayList<Any>()
        var current: PsiElement = value
        while (true) {
            val parent = current.parent ?: return null
            when (parent) {
                is JsonProperty -> {
                    if (parent.value != current) return null
                    steps += parent.name
                    current = parent.parent ?: return null // the JsonObject
                }
                is JsonArray -> {
                    steps += parent.valueList.indexOf(current as? JsonValue ?: return null)
                    current = parent
                }
                is JsonFile -> return if (parent.topLevelValue == current) steps.reversed() else null
                else -> return null
            }
        }
    }

    /** Root layers array (`layers` of the top-level object). */
    fun rootLayers(file: JsonFile): JsonArray? = root(file)?.findProperty("layers")?.value as? JsonArray

    /** Index of the root layer whose object contains [offset], or null. */
    fun rootLayerIndexAt(file: JsonFile, offset: Int): Int? {
        val layers = rootLayers(file) ?: return null
        if (!layers.textRange.containsOffset(offset)) return null
        return layers.valueList.indexOfFirst { it.textRange.containsOffset(offset) && offset > it.textRange.startOffset }.takeIf { it >= 0 }
    }

    /** The keyframe time `t` of the keyframe object around [offset] (an animated property's `k` array item), or null. */
    fun keyframeTimeAt(file: JsonFile, offset: Int): Double? {
        val element = file.findElementAt(offset) ?: return null
        var obj = PsiTreeUtil.getParentOfType(element, JsonObject::class.java)
        while (obj != null) {
            val array = obj.parent as? JsonArray
            val property = array?.parent as? JsonProperty
            if (property?.name == "k") {
                val t = (obj.findProperty("t")?.value as? JsonNumberLiteral)?.value
                if (t != null && (obj.findProperty("s") != null || obj.findProperty("e") != null)) return t
            }
            obj = PsiTreeUtil.getParentOfType(obj, JsonObject::class.java)
        }
        return null
    }

    fun stringValue(obj: JsonObject, key: String): String? = (obj.findProperty(key)?.value as? JsonStringLiteral)?.value

    fun numberValue(obj: JsonObject, key: String): Double? = (obj.findProperty(key)?.value as? JsonNumberLiteral)?.value
}
