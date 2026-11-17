package cz.xprees.lottie.editor

import com.intellij.json.JsonElementTypes
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonNumberLiteral
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.lang.LottieFiles
import cz.xprees.lottie.model.JsonPath
import cz.xprees.lottie.palette.ColorKind
import cz.xprees.lottie.palette.LottieColor
import cz.xprees.lottie.palette.PaletteScanner
import cz.xprees.lottie.settings.LottieSettings
import java.awt.Color

/**
 * Gutter swatches for the colors of a Lottie JSON file: `[r, g, b(, a)]` arrays of fills, strokes, text, color effects and color
 * slots (components 0..1, or 0..255 in old exports), and the `"#rrggbb"` color of solid layers. Clicking a swatch opens the color
 * picker; the chosen color is written back in the file's own form (same scale, alpha kept when present).
 *
 * Only values the palette scanner classifies as colors get a swatch, so positions, scales and other number triples don't.
 */
class LottieColorProvider : ElementColorProvider {

    override fun getColorFrom(element: PsiElement): Color? {
        val target = target(element) ?: return null
        val color = when (target) {
            is JsonArray -> numbers(target)?.let(LottieColor::fromComponents)
            is JsonStringLiteral -> LottieColor.fromHex(target.value)
            else -> null
        } ?: return null
        return Color(color.red, color.green, color.blue, color.alpha)
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        val target = target(element) ?: return
        val file = target.containingFile ?: return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        val text = when (target) {
            is JsonArray -> {
                val previous = numbers(target)?.let(LottieColor::fromComponents) ?: return
                LottieColor.fromRgba8(color.red, color.green, color.blue, color.alpha, previous).toJsonArray()
            }
            is JsonStringLiteral -> "\"#%02x%02x%02x\"".format(color.red, color.green, color.blue)
            else -> return
        }
        val range = target.textRange
        WriteCommandAction.runWriteCommandAction(file.project, LottieBundle.message("color.change.command"), null, {
            document.replaceString(range.startOffset, range.endOffset, text)
            PsiDocumentManager.getInstance(file.project).commitDocument(document)
        }, file)
    }

    /** The color value [element] starts: the `[` of a color array, or the string token of a solid layer color. */
    private fun target(element: PsiElement): PsiElement? {
        val value: PsiElement = when (element.elementType) {
            JsonElementTypes.L_BRACKET -> element.parent as? JsonArray
            JsonElementTypes.DOUBLE_QUOTED_STRING -> element.parent as? JsonStringLiteral
            else -> null
        } ?: return null
        val file = value.containingFile as? JsonFile ?: return null
        if (!LottieSettings.getInstance().state.colorSwatches) return null
        val virtualFile = file.virtualFile ?: file.originalFile.virtualFile ?: return null
        if (!LottieFiles.isLottieJson(virtualFile)) return null
        val path = LottiePsi.pathOf(value as com.intellij.json.psi.JsonValue) ?: return null
        val colors = colorPaths(file)
        return when (value) {
            is JsonArray -> value.takeIf { path in colors.arrays }
            is JsonStringLiteral -> value.takeIf { path in colors.solids }
            else -> null
        }
    }

    private class ColorPaths(val arrays: Set<JsonPath>, val solids: Set<JsonPath>)

    private fun colorPaths(file: JsonFile): ColorPaths = CachedValuesManager.getCachedValue(file) {
        val usages = PaletteScanner.scan(MiniJson.parseOrNull(file.text))
        CachedValueProvider.Result.create(
            ColorPaths(PaletteScanner.editableArrayPaths(usages), usages.filter { it.kind == ColorKind.SOLID }.mapTo(HashSet()) { it.path }),
            file,
        )
    }

    private fun numbers(array: JsonArray): List<Double>? =
        array.valueList.map { (it as? JsonNumberLiteral)?.value ?: return null }
}
