package cz.xprees.lottie.snippets

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.EditorTextField
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.xprees.lottie.LottieBundle
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * The Code tab of the preview: snippets that use this animation's markers and state machine from an app, for the platform picked at
 * the top (by default the one the project's build files suggest), each with a copy button.
 */
class CodeSnippetsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {

    private val platforms = CodePlatforms.getInstance(project)
    private val combo = ComboBox(CodePlatform.entries.toTypedArray()).apply {
        renderer = textListCellRenderer<CodePlatform?> { platform -> platform?.let(::label) }
        isSwingPopup = false
    }
    /** TypeScript or JavaScript, for the web players. */
    private val languageCombo = ComboBox(arrayOf(true, false)).apply {
        renderer = textListCellRenderer<Boolean?> { ts -> ts?.let { LottieBundle.message(if (it) "code.language.ts" else "code.language.js") } }
        isSwingPopup = false
        toolTipText = LottieBundle.message("code.language.tooltip")
    }
    private val detectedLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }
    private val promptLink = com.intellij.ui.components.ActionLink(LottieBundle.message("code.prompt.copy")) { copyPrompt() }.apply {
        setIcon(AllIcons.Actions.Copy, false)
        toolTipText = LottieBundle.message("code.prompt.tooltip")
        font = JBUI.Fonts.smallFont()
    }
    private val content = WidthTrackingPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 8, 8, 8)
    }
    private val scroll = JBScrollPane(content, JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply { border = JBUI.Borders.empty() }
    private var context: SnippetContext? = null
    private var shown: Triple<CodePlatform, Boolean, SnippetContext>? = null
    private var updating = false

    /** Rebuilding the snippets waits until the tab is visible. */
    var active = false
        set(value) {
            field = value
            if (value) refresh()
        }

    init {
        com.intellij.openapi.util.Disposer.register(parent, this)
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 8))
            add(JBLabel(LottieBundle.message("code.platform")), BorderLayout.WEST)
            add(combo, BorderLayout.CENTER)
            add(languageCombo, BorderLayout.EAST)
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(detectedLabel, BorderLayout.CENTER)
                add(promptLink, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        combo.addActionListener {
            if (updating) return@addActionListener
            val platform = combo.selectedItem as? CodePlatform ?: return@addActionListener
            // picking the detected platform goes back to following the project
            platforms.picked = platform.takeIf { it != platforms.detected.firstOrNull()?.platform }
        }
        languageCombo.addActionListener {
            if (updating) return@addActionListener
            val ts = languageCombo.selectedItem as? Boolean ?: return@addActionListener
            // picking what the project uses goes back to following it
            platforms.pickedTypeScript = ts.takeIf { it != platforms.typeScriptDetected }
        }
        project.messageBus.connect(this).subscribe(CodePlatforms.TOPIC, CodePlatforms.Listener { refresh() })
        platforms.detect()
        refresh()
    }

    fun setContext(context: SnippetContext) {
        this.context = context
        refresh()
    }

    private fun name(platform: CodePlatform) = LottieBundle.message("code.platform.${platform.name.lowercase()}")

    private fun label(platform: CodePlatform): String {
        val name = name(platform)
        return if (platform == platforms.detected.firstOrNull()?.platform) LottieBundle.message("code.platform.detected", name) else name
    }

    private fun refresh() {
        val platform = platforms.current
        val ts = platforms.typeScript
        updating = true
        combo.selectedItem = platform
        languageCombo.selectedItem = ts
        updating = false
        languageCombo.isVisible = platform.typeScript
        val detected = platforms.detected.firstOrNull()
        detectedLabel.text = when {
            detected != null && platforms.picked == null -> LottieBundle.message("code.detected.from", detected.evidence)
            detected != null -> LottieBundle.message("code.detected.other", name(detected.platform), detected.evidence)
            platforms.detectionDone -> LottieBundle.message("code.detected.none")
            else -> " "
        }
        val context = context ?: return
        if (!active || shown == Triple(platform, ts, context)) return
        val otherPlatform = shown?.first != platform || shown?.second != ts
        shown = Triple(platform, ts, context)
        rebuild(platform, ts, context)
        // another platform is new code: start at its top (edits and live values keep the place)
        if (otherPlatform) javax.swing.SwingUtilities.invokeLater { scroll.verticalScrollBar.value = 0 }
    }

    private fun rebuild(platform: CodePlatform, ts: Boolean, context: SnippetContext) {
        content.removeAll()
        val snippets = SnippetGenerator.generate(platform, context, ts)
        val name = name(platform)
        content.add(hint(LottieBundle.message("code.package", platform.packageName)))
        for (note in snippets.notes) {
            content.add(hint(LottieBundle.message("code.note.${note.name.lowercase()}", name)))
        }
        if (context.markers.isEmpty()) content.add(hint(LottieBundle.message(if (context.dotLottie) "code.no.markers.lottie" else "code.no.markers")))
        for (snippet in snippets.snippets) {
            content.add(section(title(snippet), snippet.code, fileType(snippet.extension)))
        }
        content.add(Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
    }

    /**
     * The file type that highlights code with [extension]; without the language's plugin (Vue, Svelte, TypeScript in a Community
     * IDE) the closest one there is, so the code still gets some highlighting.
     */
    private fun fileType(extension: String): com.intellij.openapi.fileTypes.FileType {
        val manager = FileTypeManager.getInstance()
        for (candidate in listOf(extension) + FALLBACKS[extension].orEmpty()) {
            val type = manager.getFileTypeByExtension(candidate)
            if (type != com.intellij.openapi.fileTypes.UnknownFileType.INSTANCE && type != PlainTextFileType.INSTANCE) return type
        }
        return PlainTextFileType.INSTANCE
    }

    private fun copyPrompt() {
        val context = context ?: return
        val platform = platforms.current
        val prompt = AgentPrompt.build(platform, name(platform), platforms.typeScript, context)
        CopyPasteManager.getInstance().setContents(StringSelection(prompt))
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(LottieBundle.message("code.prompt.copied"), com.intellij.openapi.ui.MessageType.INFO, null)
            .setFadeoutTime(2500)
            .createBalloon()
            .show(com.intellij.ui.awt.RelativePoint.getSouthOf(promptLink), com.intellij.openapi.ui.popup.Balloon.Position.below)
    }

    private fun title(snippet: Snippet): String = when (snippet.kind) {
        SnippetKind.SETUP -> LottieBundle.message("code.section.setup")
        SnippetKind.PLAY_MARKER -> LottieBundle.message("code.section.marker", snippet.subject.orEmpty())
        SnippetKind.MARKER_EVENTS -> LottieBundle.message("code.section.marker.events")
        SnippetKind.STATE_MACHINE -> LottieBundle.message("code.section.state.machine", snippet.subject.orEmpty())
        SnippetKind.INPUT -> {
            val event = context?.inputs?.firstOrNull { it.name == snippet.subject }?.type == cz.xprees.lottie.statemachine.InputType.EVENT
            LottieBundle.message(if (event) "code.section.event" else "code.section.input", snippet.subject.orEmpty())
        }
        SnippetKind.CUSTOM_EVENTS -> LottieBundle.message("code.section.custom.events")
    }

    private fun section(title: String, code: String, fileType: com.intellij.openapi.fileTypes.FileType): JPanel = object : JPanel(BorderLayout(0, JBUI.scale(2))) {
        override fun getMaximumSize() = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(6, 0, 2, 0)
        add(JPanel(BorderLayout()).apply {
            add(SimpleColoredComponent().apply { append(title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES) }, BorderLayout.CENTER)
            add(InplaceButton(LottieBundle.message("code.copy"), AllIcons.Actions.Copy) {
                CopyPasteManager.getInstance().setContents(StringSelection(code))
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        val document = EditorFactory.getInstance().createDocument(code)
        add(object : EditorTextField(document, project, fileType, true, false) {
            override fun createEditor(): EditorEx = super.createEditor().apply {
                setHorizontalScrollbarVisible(true)
                setVerticalScrollbarVisible(false)
                settings.isUseSoftWraps = false
                settings.isLineNumbersShown = false
                settings.isCaretRowShown = false
                settings.additionalLinesCount = 0
                isEmbeddedIntoDialogWrapper = true
            }
        }.apply { setFontInheritedFromLAF(false) }, BorderLayout.CENTER)
    }

    private fun hint(text: String) = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 0)
        alignmentX = LEFT_ALIGNMENT
        setAllowAutoWrapping(true)
        setCopyable(true)
    }

    /** Lays its rows out at the viewport's width; the code scrolls sideways inside each snippet instead. */
    private class WidthTrackingPanel : JPanel(), javax.swing.Scrollable {
        override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    override fun dispose() {}

    private companion object {
        val FALLBACKS = mapOf(
            "tsx" to listOf("ts", "jsx", "js"),
            "ts" to listOf("js"),
            "jsx" to listOf("js"),
            "vue" to listOf("html"),
            "svelte" to listOf("html"),
        )
    }
}
