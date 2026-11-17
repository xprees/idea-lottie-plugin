package cz.xprees.lottie.preview

import com.intellij.json.psi.JsonFile
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm
import cz.xprees.lottie.editor.LottiePsi
import cz.xprees.lottie.palette.ColorUsage
import cz.xprees.lottie.settings.LottieSettings

/**
 * The Lottie JSON editor: text on the left, preview on the right, with the usual Editor / Editor and Preview / Preview switch.
 *
 * Code and preview follow each other: the root layer at the caret (also when it is selected in the Structure view) is outlined in
 * the preview, a caret inside a keyframe shows that keyframe's frame while paused, clicking the preview moves the caret to the
 * layer under the mouse, and clicking a palette swatch walks through the places that color is defined.
 */
class LottieSplitEditor(
    private val project: Project,
    textEditor: TextEditor,
    private val preview: LottiePreviewFileEditor,
) : TextEditorWithPreview(textEditor, preview, "Lottie", LottieSettings.getInstance().state.defaultLayout.layout) {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var highlighted: Int? = null

    init {
        preview.panel.onPick = ::navigateToLayer
        preview.panel.onNavigateToColor = ::navigateToColor
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest(::syncCaret, 150)
            }
        }, this)
    }

    private fun jsonFile(): JsonFile? {
        val document = editor.document
        val manager = PsiDocumentManager.getInstance(project)
        if (manager.isUncommited(document)) return null
        return manager.getPsiFile(document) as? JsonFile
    }

    private fun syncCaret() {
        if (!LottieSettings.getInstance().state.previewSync) return
        val file = jsonFile() ?: return
        val offset = editor.caretModel.offset
        val layer = LottiePsi.rootLayerIndexAt(file, offset)
        if (layer != highlighted) {
            highlighted = layer
            preview.panel.highlightLayer(layer)
        }
        LottiePsi.keyframeTimeAt(file, offset)?.let { t -> preview.panel.seekIfPaused(t - preview.panel.inPoint) }
    }

    private fun navigateToLayer(index: Int?) {
        if (index == null) return
        val file = jsonFile() ?: return
        val layer = LottiePsi.rootLayers(file)?.valueList?.getOrNull(index) ?: return
        highlighted = index
        moveCaret(layer.textRange.startOffset)
    }

    private fun navigateToColor(usage: ColorUsage) {
        val file = jsonFile() ?: return
        val value = LottiePsi.findByPath(file, usage.path) ?: return
        moveCaret(value.textRange.startOffset)
    }

    private fun moveCaret(offset: Int) {
        if (getLayout() == Layout.SHOW_PREVIEW) setLayout(Layout.SHOW_EDITOR_AND_PREVIEW)
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }
}
