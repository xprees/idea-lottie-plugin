package cz.xprees.lottie.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.util.messages.Topic

/** Settings › Languages & Frameworks › Lottie. */
@Service(Service.Level.APP)
@State(name = "LottieSettings", storages = [Storage("lottie.xml")])
class LottieSettings : PersistentStateComponent<LottieSettings.State> {

    enum class DefaultLayout(val layout: TextEditorWithPreview.Layout) {
        EDITOR_AND_PREVIEW(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW),
        EDITOR(TextEditorWithPreview.Layout.SHOW_EDITOR),
        PREVIEW(TextEditorWithPreview.Layout.SHOW_PREVIEW),
    }

    enum class Background { CHECKERBOARD, LIGHT, DARK, CUSTOM }

    /** What hovering a palette color shows in the preview. */
    enum class PaletteHighlight { OUTLINE, DIM, NONE }

    class State {
        /** Recognize plain `.json` files by their content (the `.lottie.json` name always counts). */
        var sniffJson: Boolean = true
        var defaultLayout: DefaultLayout = DefaultLayout.EDITOR_AND_PREVIEW
        var autoplay: Boolean = true
        var loop: Boolean = true
        var background: Background = Background.CHECKERBOARD
        /** `#rrggbb`, used with [Background.CUSTOM]. */
        var customBackground: String = "#808080"
        /** Color swatches in the gutter of Lottie JSON files, with a color picker that writes the color back. */
        var colorSwatches: Boolean = true
        /** Readable hints next to Lottie's short keys and codes. */
        var inlayHints: Boolean = true
        /** Highlight the layer at the caret in the preview, and jump to the layer clicked in the preview. */
        var previewSync: Boolean = true
        /** Hovering a palette color outlines the shapes using it, or dims the other colors. */
        var paletteHighlight: PaletteHighlight = PaletteHighlight.OUTLINE
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun update(change: State.() -> Unit) {
        state.change()
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).settingsChanged(state)
    }

    fun interface Listener {
        fun settingsChanged(state: State)
    }

    companion object {
        @JvmField
        val TOPIC = Topic.create("Lottie settings", Listener::class.java)

        fun getInstance(): LottieSettings = ApplicationManager.getApplication().getService(LottieSettings::class.java)
    }
}
