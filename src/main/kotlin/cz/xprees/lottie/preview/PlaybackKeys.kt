package cz.xprees.lottie.preview

import com.intellij.openapi.util.SystemInfo
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.text.JTextComponent

/**
 * Playback keys of the preview, wherever the focus is inside it (the canvas, the timeline, the toolbars):
 * Space plays or pauses, ←/→ step one frame (Shift: ten), Ctrl+←/→ (⌘ on macOS) or Home/End jump to the first or last frame.
 *
 * The canvas is a browser with its own keyboard focus, so the page forwards the same keys ([fromPage]) instead of Swing.
 */
enum class PlaybackKey { TOGGLE, PREVIOUS, NEXT, BACK_10, FORWARD_10, FIRST, LAST }

object PlaybackKeys {

    /** Ctrl on Windows and Linux, ⌘ on macOS (where Ctrl+←/→ switch desktops). */
    private val jump: Int get() = if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK

    fun strokes(): Map<KeyStroke, PlaybackKey> = linkedMapOf(
        KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0) to PlaybackKey.TOGGLE,
        KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0) to PlaybackKey.PREVIOUS,
        KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0) to PlaybackKey.NEXT,
        KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK) to PlaybackKey.BACK_10,
        KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK) to PlaybackKey.FORWARD_10,
        KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, jump) to PlaybackKey.FIRST,
        KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, jump) to PlaybackKey.LAST,
        KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0) to PlaybackKey.FIRST,
        KeyStroke.getKeyStroke(KeyEvent.VK_END, 0) to PlaybackKey.LAST,
    )

    /** The key of a page `keydown` ([key] is the DOM `KeyboardEvent.key`), or null for any other key. */
    fun fromPage(key: String?, shift: Boolean, ctrl: Boolean, meta: Boolean, alt: Boolean, mac: Boolean = SystemInfo.isMac): PlaybackKey? {
        if (alt) return null
        val jump = if (mac) meta && !ctrl else ctrl && !meta
        val plain = !shift && !ctrl && !meta
        return when (key) {
            " " -> PlaybackKey.TOGGLE.takeIf { plain }
            "Home" -> PlaybackKey.FIRST.takeIf { plain }
            "End" -> PlaybackKey.LAST.takeIf { plain }
            "ArrowLeft" -> when {
                plain -> PlaybackKey.PREVIOUS
                shift && !ctrl && !meta -> PlaybackKey.BACK_10
                jump && !shift -> PlaybackKey.FIRST
                else -> null
            }
            "ArrowRight" -> when {
                plain -> PlaybackKey.NEXT
                shift && !ctrl && !meta -> PlaybackKey.FORWARD_10
                jump && !shift -> PlaybackKey.LAST
                else -> null
            }
            else -> null
        }
    }

    /**
     * Binds the keys on [component] for its whole subtree. They step aside (the event goes on to the focused control or the IDE)
     * while a text field has the focus, so typing a space or moving the caret in the state machine panel still works, and while
     * [enabled] is false (nothing loaded).
     */
    fun install(component: JComponent, enabled: () -> Boolean, run: (PlaybackKey) -> Unit) {
        val inputMap = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        for ((stroke, key) in strokes()) {
            val id = "lottie.playback.$key"
            inputMap.put(stroke, id)
            component.actionMap.put(id, object : AbstractAction() {
                override fun isEnabled(): Boolean =
                    enabled() && KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner !is JTextComponent

                override fun actionPerformed(e: ActionEvent?) = run(key)
            })
        }
    }
}
