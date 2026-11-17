package cz.xprees.lottie.palette

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColorPicker
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.dotlottie.DotLottieAsset
import cz.xprees.lottie.dotlottie.DotLottieWriter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Picks the new color for every use of a palette [entry]. For `.lottie` files ([themes] not null) it also asks where to put the
 * change: into the animation, or into a dotLottie theme (an existing one or a new one); [themesSupported] is false for dotLottie
 * 1.0 files, which have no themes.
 */
class RecolorDialog(
    project: Project,
    private val entry: PaletteEntry,
    private val themes: List<DotLottieAsset>?,
    private val activeTheme: String?,
    private val themesSupported: Boolean,
) : DialogWrapper(project) {

    enum class Target { ANIMATION, THEME }

    private val picker = ColorPicker(disposable, entry.color.let { Color(it.red, it.green, it.blue, it.alpha) }, true)
    private val animationRadio = JBRadioButton(LottieBundle.message("recolor.target.animation"))
    private val themeRadio = JBRadioButton(LottieBundle.message("recolor.target.theme"))
    private val themeCombo = ComboBox(themes.orEmpty().map { it.id }.toTypedArray()).apply {
        isEditable = true
        selectedItem = activeTheme ?: themes.orEmpty().firstOrNull()?.id ?: NEW_THEME
    }
    private val skippedLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        setAllowAutoWrapping(true)
    }

    val color: LottieColor
        get() = picker.color.let { LottieColor.fromRgba8(it.red, it.green, it.blue, it.alpha, entry.color) }

    val target: Target get() = if (themes != null && themeRadio.isSelected) Target.THEME else Target.ANIMATION

    /** The theme to write into: an id of [themes], or a new id made from what was typed. */
    val themeId: String get() = (themeCombo.editor.item?.toString() ?: themeCombo.selectedItem?.toString()).orEmpty().trim().let { typed ->
        themes.orEmpty().firstOrNull { it.id == typed || it.label == typed }?.id ?: DotLottieWriter.sanitizeId(typed)
    }

    /** The name typed for a new theme (null when an existing one is picked). */
    val newThemeName: String? get() = (themeCombo.editor.item?.toString() ?: "").trim().takeIf { typed -> themes.orEmpty().none { it.id == typed || it.label == typed } }

    init {
        title = LottieBundle.message("recolor.title", entry.color.hex)
        ButtonGroup().apply {
            add(animationRadio)
            add(themeRadio)
        }
        val useTheme = themesSupported && activeTheme != null
        themeRadio.isSelected = useTheme
        animationRadio.isSelected = !useTheme
        themeRadio.isEnabled = themesSupported
        listOf(animationRadio, themeRadio).forEach { it.addActionListener { updateState() } }
        init()
        updateState()
    }

    private fun updateState() {
        themeCombo.isEnabled = themeRadio.isSelected
        val skipped = entry.usages.count { !Recolor.themable(it) }
        skippedLabel.text = wrap(when {
            themes != null && !themesSupported -> LottieBundle.message("recolor.theme.v1")
            themeRadio.isSelected && skipped > 0 -> LottieBundle.message("recolor.theme.skipped", skipped)
            themeRadio.isSelected -> LottieBundle.message("recolor.theme.comment")
            else -> LottieBundle.message("recolor.animation.comment")
        })
    }

    /** [text] wrapped at the picker's width, so that a long note doesn't widen the dialog. */
    private fun wrap(text: String) = "<html><body style='width: ${JBUI.scale(320)}px'>" +
        com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text) + "</body></html>"

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBLabel(summary()).apply { setCopyable(false) }, BorderLayout.NORTH)
        add(picker, BorderLayout.CENTER)
        if (themes != null) {
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(animationRadio) }.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    add(themeRadio)
                    add(themeCombo.apply { prototypeDisplayValue = "wwwwwwwwwwwwwwww" })
                }.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
                add(skippedLabel.apply { border = JBUI.Borders.emptyTop(4); alignmentX = java.awt.Component.LEFT_ALIGNMENT })
            }, BorderLayout.SOUTH)
        } else {
            add(skippedLabel, BorderLayout.SOUTH)
        }
    }

    private fun summary(): String {
        val kinds = entry.usages.groupBy { it.kind }.entries.joinToString(" · ") { (kind, list) -> "${kind.label} ${list.size}" }
        val animated = entry.usages.count { it.animated }
        val extra = if (animated > 0) LottieBundle.message("recolor.summary.animated", animated) else ""
        return "<html>" + LottieBundle.message("recolor.summary", entry.count, kinds) + extra + "</html>"
    }

    override fun getPreferredFocusedComponent(): JComponent = picker.preferredFocusedComponent

    override fun doValidate(): ValidationInfo? {
        if (target == Target.THEME && (themeCombo.editor.item?.toString() ?: "").isBlank()) {
            return ValidationInfo(LottieBundle.message("recolor.theme.blank"), themeCombo)
        }
        return null
    }

    override fun doOKAction() {
        picker.appendRecentColor()
        picker.saveRecentColors()
        super.doOKAction()
    }

    companion object {
        private const val NEW_THEME = "recolored"
    }
}
