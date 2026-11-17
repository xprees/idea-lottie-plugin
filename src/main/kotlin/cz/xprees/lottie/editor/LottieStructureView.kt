package cz.xprees.lottie.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonValue
import com.intellij.json.structureView.JsonCustomStructureViewFactory
import com.intellij.openapi.editor.Editor
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.LottieIcons
import cz.xprees.lottie.lang.LottieFiles
import cz.xprees.lottie.model.LottieNames
import cz.xprees.lottie.model.MarkerNames
import java.util.Locale
import javax.swing.Icon

/**
 * The Structure view of a Lottie JSON file shows the animation instead of raw JSON keys: the composition, its layers (by name and
 * type) with their shapes, the assets (precompositions with their layers, images), markers and slots.
 */
class LottieStructureViewFactory : JsonCustomStructureViewFactory {
    override fun getStructureViewBuilder(file: JsonFile): StructureViewBuilder? {
        val virtualFile = file.virtualFile ?: file.originalFile.virtualFile ?: return null
        if (!LottieFiles.isLottieJson(virtualFile)) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel {
                val root = LottiePsi.root(file)
                val element: StructureViewTreeElement = if (root != null) CompositionElement(root) else EmptyElement(file)
                return StructureViewModelBase(file, editor, element).withSuitableClasses(JsonObject::class.java)
            }

            override fun isRootNodeShown() = true
        }
    }
}

private abstract class LottieElement<T : JsonValue>(value: T) : PsiTreeElementBase<T>(value) {
    abstract val text: String?
    open val location: String? = null
    open val elementIcon: Icon? = null

    override fun getPresentableText(): String? = text

    override fun getLocationString(): String? = location

    override fun getIcon(open: Boolean): Icon? = elementIcon
}

private class EmptyElement(file: JsonFile) : PsiTreeElementBase<JsonFile>(file) {
    override fun getChildrenBase(): Collection<StructureViewTreeElement> = emptyList()
    override fun getPresentableText() = element?.name
}

private class CompositionElement(root: JsonObject) : LottieElement<JsonObject>(root) {
    override val text get() = element?.let { LottiePsi.stringValue(it, "nm") } ?: LottieBundle.message("structure.composition")
    override val location: String?
        get() {
            val o = element ?: return null
            val w = LottiePsi.numberValue(o, "w")?.toInt()
            val h = LottiePsi.numberValue(o, "h")?.toInt()
            val fr = LottiePsi.numberValue(o, "fr")
            val ip = LottiePsi.numberValue(o, "ip") ?: 0.0
            val op = LottiePsi.numberValue(o, "op")
            val parts = mutableListOf<String>()
            if (w != null && h != null) parts += "$w×$h"
            if (fr != null) parts += "${fr.trimNumber()} fps"
            if (fr != null && op != null && fr > 0) parts += String.format(Locale.ROOT, "%.2f s", (op - ip) / fr)
            return parts.joinToString(" · ").ifEmpty { null }
        }
    override val elementIcon: Icon get() = LottieIcons.LOTTIE

    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
        val o = element ?: return emptyList()
        val children = mutableListOf<StructureViewTreeElement>()
        (o.findProperty("layers")?.value as? JsonArray)?.let { children += LayersElement(it, LottieBundle.message("structure.layers")) }
        (o.findProperty("assets")?.value as? JsonArray)?.takeIf { it.valueList.isNotEmpty() }?.let { children += AssetsElement(it) }
        (o.findProperty("markers")?.value as? JsonArray)?.takeIf { it.valueList.isNotEmpty() }?.let { children += MarkersElement(it) }
        (o.findProperty("slots")?.value as? JsonObject)?.takeIf { it.propertyList.isNotEmpty() }?.let { children += SlotsElement(it) }
        return children
    }
}

private class LayersElement(array: JsonArray, private val title: String) : LottieElement<JsonArray>(array) {
    override val text get() = title
    override val location get() = element?.valueList?.size?.toString()
    override val elementIcon: Icon get() = AllIcons.Nodes.Folder
    override fun getChildrenBase(): Collection<StructureViewTreeElement> =
        element?.valueList.orEmpty().filterIsInstance<JsonObject>().mapIndexed { i, layer -> LayerElement(layer, i) }
}

private class LayerElement(layer: JsonObject, private val position: Int) : LottieElement<JsonObject>(layer) {
    private val type get() = element?.let { LottiePsi.numberValue(it, "ty")?.toInt() }

    override val text get() = element?.let { LottiePsi.stringValue(it, "nm") }?.takeIf { it.isNotBlank() } ?: "${LottieNames.layerType(type)} ${position + 1}"
    override val location: String?
        get() {
            val o = element ?: return null
            val parts = mutableListOf(LottieNames.layerType(type))
            LottiePsi.numberValue(o, "ind")?.let { parts += "#${it.trimNumber()}" }
            val ip = LottiePsi.numberValue(o, "ip")
            val op = LottiePsi.numberValue(o, "op")
            if (ip != null && op != null) parts += "${ip.trimNumber()}–${op.trimNumber()}"
            LottiePsi.numberValue(o, "parent")?.let { parts += LottieBundle.message("structure.parent", it.trimNumber()) }
            if ((o.findProperty("hd")?.value?.text) == "true") parts += LottieBundle.message("structure.hidden")
            LottieNames.matteMode(LottiePsi.numberValue(o, "tt")?.toInt())?.takeIf { it != "None" }?.let { parts += LottieBundle.message("structure.matte", it) }
            return parts.joinToString(" · ")
        }
    override val elementIcon: Icon
        get() = when (type) {
            0 -> AllIcons.Nodes.ModuleGroup
            1 -> AllIcons.Actions.Colors
            2 -> AllIcons.FileTypes.Image
            3 -> AllIcons.Nodes.Static
            4 -> AllIcons.Nodes.Property
            5 -> AllIcons.FileTypes.Text
            else -> AllIcons.Json.Object
        }

    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
        val shapes = element?.findProperty("shapes")?.value as? JsonArray ?: return emptyList()
        return shapes.valueList.filterIsInstance<JsonObject>().map(::ShapeElement)
    }
}

private class ShapeElement(shape: JsonObject) : LottieElement<JsonObject>(shape) {
    private val ty get() = element?.let { LottiePsi.stringValue(it, "ty") }

    override val text get() = element?.let { LottiePsi.stringValue(it, "nm") }?.takeIf { it.isNotBlank() } ?: LottieNames.shapeType(ty) ?: ty ?: "?"
    override val location get() = LottieNames.shapeType(ty) ?: ty
    override val elementIcon: Icon
        get() = when (ty) {
            "gr" -> AllIcons.Nodes.Folder
            "fl", "st", "gf", "gs" -> AllIcons.Actions.Colors
            "tr" -> AllIcons.General.Layout
            else -> AllIcons.Nodes.Property
        }

    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
        if (ty != "gr") return emptyList()
        val items = element?.findProperty("it")?.value as? JsonArray ?: return emptyList()
        return items.valueList.filterIsInstance<JsonObject>().map(::ShapeElement)
    }
}

private class AssetsElement(array: JsonArray) : LottieElement<JsonArray>(array) {
    override val text get() = LottieBundle.message("structure.assets")
    override val location get() = element?.valueList?.size?.toString()
    override val elementIcon: Icon get() = AllIcons.Nodes.Folder
    override fun getChildrenBase(): Collection<StructureViewTreeElement> =
        element?.valueList.orEmpty().filterIsInstance<JsonObject>().map(::AssetElement)
}

private class AssetElement(asset: JsonObject) : LottieElement<JsonObject>(asset) {
    private val layers get() = element?.findProperty("layers")?.value as? JsonArray

    override val text get() = element?.let { LottiePsi.stringValue(it, "nm") ?: LottiePsi.stringValue(it, "id") } ?: "?"
    override val location: String?
        get() {
            val o = element ?: return null
            if (layers != null) return LottieBundle.message("structure.precomp", layers!!.valueList.size)
            val p = LottiePsi.stringValue(o, "p")
            return when {
                p == null -> null
                p.startsWith("data:") -> LottieBundle.message("structure.image.embedded")
                else -> LottieBundle.message("structure.image", (LottiePsi.stringValue(o, "u") ?: "") + p)
            }
        }
    override val elementIcon: Icon get() = if (layers != null) AllIcons.Nodes.ModuleGroup else AllIcons.FileTypes.Image
    override fun getChildrenBase(): Collection<StructureViewTreeElement> =
        layers?.valueList.orEmpty().filterIsInstance<JsonObject>().mapIndexed { i, layer -> LayerElement(layer, i) }
}

private class MarkersElement(array: JsonArray) : LottieElement<JsonArray>(array) {
    override val text get() = LottieBundle.message("structure.markers")
    override val location get() = element?.valueList?.size?.toString()
    override val elementIcon: Icon get() = AllIcons.Nodes.Bookmark
    override fun getChildrenBase(): Collection<StructureViewTreeElement> =
        element?.valueList.orEmpty().filterIsInstance<JsonObject>().map(::MarkerElement)
}

private class MarkerElement(marker: JsonObject) : LottieElement<JsonObject>(marker) {
    override val text get() = element?.let { MarkerNames.parse(LottiePsi.stringValue(it, "cm")) } ?: "?"
    override val location: String?
        get() {
            val o = element ?: return null
            val tm = LottiePsi.numberValue(o, "tm") ?: return null
            val dr = LottiePsi.numberValue(o, "dr") ?: 0.0
            return "${tm.trimNumber()}–${(tm + dr).trimNumber()}"
        }
    override val elementIcon: Icon get() = AllIcons.Nodes.Bookmark
    override fun getChildrenBase(): Collection<StructureViewTreeElement> = emptyList()
}

private class SlotsElement(slots: JsonObject) : LottieElement<JsonObject>(slots) {
    override val text get() = LottieBundle.message("structure.slots")
    override val location get() = element?.propertyList?.size?.toString()
    override val elementIcon: Icon get() = AllIcons.Nodes.Folder
    override fun getChildrenBase(): Collection<StructureViewTreeElement> =
        element?.propertyList.orEmpty().mapNotNull { property -> (property.value as? JsonObject)?.let { SlotElement(it, property.name) } }
}

private class SlotElement(slot: JsonObject, private val id: String) : LottieElement<JsonObject>(slot) {
    override val text get() = id
    override val elementIcon: Icon get() = AllIcons.Nodes.Variable
    override fun getChildrenBase(): Collection<StructureViewTreeElement> = emptyList()
}

internal fun Double.trimNumber(): String = if (this == Math.rint(this)) toLong().toString() else String.format(Locale.ROOT, "%.3f", this).trimEnd('0').trimEnd('.')
