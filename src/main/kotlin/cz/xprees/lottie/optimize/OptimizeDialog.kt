package cz.xprees.lottie.optimize

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.lang.LottieFiles
import java.awt.event.ActionEvent
import javax.swing.SwingConstants
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.ImageIcon
import java.awt.FlowLayout
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Options of an optimization with a live estimate: the current size, what each step would save and the result, computed by
 * running the optimizer on a copy in the background whenever an option changes. *Save as Copy* (OK) and *Optimize in Place* close
 * the dialog with [mode] set; [result] is the optimization of the chosen options.
 */
class OptimizeDialog(private val project: Project, private val file: VirtualFile) : DialogWrapper(project) {

    enum class Mode { COPY, IN_PLACE }

    private val isJson = !LottieFiles.isDotLottie(file)

    /** The input as it is now: the editor's text for JSON (unsaved edits count), the file bytes for `.lottie`. */
    private val text: String? = if (isJson) FileDocumentManager.getInstance().getDocument(file)?.text ?: VfsUtilCore.loadText(file) else null
    private val bytes: ByteArray? = if (isJson) null else file.contentsToByteArray()
    private val sizeBefore = (text?.toByteArray(Charsets.UTF_8)?.size ?: bytes!!.size).toLong()

    private val webp: WebpEncoder? = ChromiumWebpEncoder.create(disposable)
    private val properties = PropertiesComponent.getInstance(project)

    private val cleanup = JBCheckBox(LottieBundle.message("optimize.cleanup"), properties.getBoolean(KEY_CLEANUP, true))
    private val round = JBCheckBox(LottieBundle.message("optimize.precision"), properties.getBoolean(KEY_ROUND, true))
    private val decimals = JSpinner(SpinnerNumberModel(properties.getInt(KEY_DECIMALS, 3), 0, 5, 1))
    private val hidden = JBCheckBox(LottieBundle.message("optimize.hidden"), properties.getBoolean(KEY_HIDDEN, false))
    private val images = JBCheckBox(LottieBundle.message("optimize.images"), properties.getBoolean(KEY_IMAGES, false))
    private val target = ComboBox(ImageTarget.entries.filter { it != ImageTarget.WEBP || webp != null }.toTypedArray()).apply {
        renderer = textListCellRenderer<ImageTarget?> { it?.let { t -> LottieBundle.message("optimize.target.${t.name.lowercase(Locale.ROOT)}") } }
        selectedItem = runCatching { ImageTarget.valueOf(properties.getValue(KEY_TARGET, ImageTarget.KEEP.name)) }.getOrDefault(ImageTarget.KEEP)
            .takeIf { it != ImageTarget.WEBP || webp != null } ?: ImageTarget.KEEP
    }
    private val quality = JSpinner(SpinnerNumberModel(properties.getInt(KEY_QUALITY, 80), 1, 100, 5))
    private val scale = ComboBox(arrayOf(1, 2, 3, 0)).apply {
        renderer = textListCellRenderer<Int?> { value ->
            when (value) {
                null -> null
                0 -> LottieBundle.message("optimize.scale.none")
                else -> LottieBundle.message("optimize.scale", value)
            }
        }
        selectedItem = properties.getInt(KEY_SCALE, 2)
    }

    private val compareFirst = JBCheckBox(LottieBundle.message("optimize.compare.check"), properties.getBoolean(KEY_COMPARE, true)).apply {
        isVisible = FrameComparer.isAvailable()
    }
    private val compareButton = JButton(LottieBundle.message("optimize.compare")).apply {
        isVisible = FrameComparer.isAvailable()
        addActionListener { estimate?.second?.let { compareInBackground(it) } }
    }
    private val compareLabel = JBLabel()
    private val originalImage = JBLabel(LottieBundle.message("optimize.compare.original")).apply { verticalTextPosition = SwingConstants.BOTTOM; horizontalTextPosition = SwingConstants.CENTER }
    private val optimizedImage = JBLabel(LottieBundle.message("optimize.compare.optimized")).apply { verticalTextPosition = SwingConstants.BOTTOM; horizontalTextPosition = SwingConstants.CENTER }
    private val framePanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
        add(originalImage)
        add(optimizedImage)
        isVisible = false
    }

    /** The last frame comparison and the optimization it compared. */
    @Volatile private var comparison: Pair<OptimizeResult, FrameComparison>? = null

    private val totalLabel = JBLabel()
    private val stepsLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val removedLabel = JBLabel().apply { foreground = JBColor.namedColor("Label.warningForeground", JBColor.ORANGE) }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, disposable)
    @Volatile private var generation = 0
    @Volatile private var estimate: Pair<OptimizeOptions, OptimizeResult>? = null

    var mode: Mode = Mode.COPY
        private set
    var result: OptimizeResult? = null
        private set

    private val inPlaceAction = object : AbstractAction(LottieBundle.message("optimize.in.place")) {
        override fun actionPerformed(e: ActionEvent?) = finish(Mode.IN_PLACE)
    }

    init {
        title = LottieBundle.message("optimize.title", file.name)
        setOKButtonText(LottieBundle.message("optimize.copy"))
        init()
        listOf(cleanup, round, hidden, images).forEach { it.addActionListener { changed() } }
        listOf(decimals, quality).forEach { it.addChangeListener { changed() } }
        listOf(target, scale).forEach { it.addActionListener { changed() } }
        changed()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, inPlaceAction, cancelAction)

    override fun createCenterPanel(): JComponent = panel {
        row(LottieBundle.message("optimize.size.now")) {
            label(StringUtil.formatFileSize(sizeBefore)).bold()
        }
        group(LottieBundle.message("optimize.group.options")) {
            row { cell(cleanup).comment(LottieBundle.message("optimize.cleanup.comment")) }
            row {
                cell(round)
                cell(decimals)
                label(LottieBundle.message("optimize.precision.decimals"))
            }.rowComment(LottieBundle.message("optimize.precision.comment"))
            row { cell(hidden).comment(LottieBundle.message("optimize.hidden.comment")) }
            row { cell(images) }
            indent {
                row(LottieBundle.message("optimize.target")) { cell(target) }
                row(LottieBundle.message("optimize.quality")) { cell(quality) }
                row(LottieBundle.message("optimize.scale.label")) { cell(scale) }.rowComment(
                    if (webp == null) LottieBundle.message("optimize.webp.unavailable") else LottieBundle.message("optimize.images.comment"),
                )
            }
        }
        group(LottieBundle.message("optimize.group.result")) {
            // the sizes on the left, the compared frame next to them (so comparing doesn't make the dialog taller)
            val sizes = JPanel(VerticalLayout(JBUI.scale(6))).apply {
                add(totalLabel)
                add(stepsLabel)
                add(removedLabel)
            }
            row {
                cell(sizes).align(AlignY.TOP)
                cell(framePanel).align(AlignY.TOP)
            }
            if (FrameComparer.isAvailable()) {
                row {
                    cell(compareButton)
                    cell(compareLabel).align(AlignX.FILL)
                }
                row { cell(compareFirst) }
            }
        }
    }.apply { border = JBUI.Borders.empty(4) }

    private fun options() = OptimizeOptions(
        cleanup = cleanup.isSelected,
        precision = if (round.isSelected) decimals.value as Int else null,
        removeHiddenLayers = hidden.isSelected,
        images = ImageOptions(
            enabled = images.isSelected,
            target = target.selectedItem as ImageTarget,
            quality = quality.value as Int,
            maxScale = (scale.selectedItem as Int).takeIf { it > 0 },
        ),
    )

    private fun changed() {
        decimals.isEnabled = round.isSelected
        listOf(target, quality, scale).forEach { it.isEnabled = images.isSelected }
        val options = options()
        val current = ++generation
        totalLabel.text = LottieBundle.message("optimize.estimating")
        okAction.isEnabled = false
        inPlaceAction.isEnabled = false
        compareButton.isEnabled = false
        alarm.cancelAllRequests()
        alarm.addRequest({
            val r = runCatching { run(options) }
            ApplicationManager.getApplication().invokeLater({
                if (current != generation) return@invokeLater
                r.onSuccess {
                    estimate = options to it
                    show(it)
                }.onFailure { totalLabel.text = LottieBundle.message("optimize.failed", it.message ?: it.toString()) }
            }, ModalityState.stateForComponent(contentPane))
        }, ESTIMATE_DELAY_MS)
    }

    private fun run(options: OptimizeOptions): OptimizeResult =
        if (isJson) Optimizer.optimizeJson(text!!, options, webp) else Optimizer.optimizeDotLottie(bytes!!, options, webp)

    private fun show(r: OptimizeResult) {
        // HTML labels: escape what we insert ("<1%" would start a tag)
        fun html(text: String) = StringUtil.escapeXmlEntities(text)
        totalLabel.text = "<html>" + LottieBundle.message("optimize.total", html(StringUtil.formatFileSize(r.sizeBefore)), html(StringUtil.formatFileSize(r.sizeAfter)), html(percent(r.saved, r.sizeBefore))) +
            (r.packedSize?.let { "&nbsp;&nbsp;&nbsp;" + html(LottieBundle.message("optimize.packed", StringUtil.formatFileSize(it))) } ?: "") + "</html>"
        stepsLabel.text = "<html>" + r.savings.joinToString("<br>") { (step, saved) -> html("${stepName(step)}: ${signed(saved)} (${percent(saved, r.sizeBefore)})") } +
            (if (r.imagesChanged > 0) "<br>" + LottieBundle.message("optimize.images.changed", r.imagesChanged) else "") + "</html>"
        removedLabel.text = if (r.layersRemoved.isEmpty()) "" else "<html>" + StringUtil.escapeXmlEntities(LottieBundle.message("optimize.removed", r.layersRemoved.joinToString(", "))) + "</html>"
        okAction.isEnabled = true
        inPlaceAction.isEnabled = r.saved > 0
        compareButton.isEnabled = true
        if (comparison?.first !== r) {
            compareLabel.text = ""
            framePanel.isVisible = false
        }
    }

    private fun originalBytes(): ByteArray = text?.toByteArray(Charsets.UTF_8) ?: bytes!!

    private fun compareInBackground(r: OptimizeResult) {
        compareButton.isEnabled = false
        compareLabel.foreground = UIUtil.getContextHelpForeground()
        compareLabel.text = LottieBundle.message("optimize.compare.running")
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching { FrameComparer.compare(originalBytes(), r.bytes, isJson) }
            ApplicationManager.getApplication().invokeLater({
                compareButton.isEnabled = true
                outcome.onSuccess { showComparison(r, it) }
                    .onFailure { compareLabel.text = LottieBundle.message("optimize.compare.failed", it.message ?: it.toString()) }
            }, ModalityState.stateForComponent(contentPane))
        }
    }

    private fun showComparison(r: OptimizeResult, c: FrameComparison) {
        comparison = r to c
        val where = c.worstAnimation?.let { LottieBundle.message("optimize.compare.animation", it) }.orEmpty()
        compareLabel.text = if (c.significant) {
            LottieBundle.message("optimize.compare.differs", c.worstFrame, where, share(c.worstShare), c.frames)
        } else {
            LottieBundle.message("optimize.compare.same", c.frames, share(c.worstShare))
        }
        compareLabel.foreground = if (c.significant) JBColor.namedColor("Label.warningForeground", JBColor.ORANGE) else UIUtil.getLabelForeground()
        originalImage.icon = c.original?.let(::thumbnail)
        optimizedImage.icon = c.optimized?.let(::thumbnail)
        framePanel.isVisible = c.original != null && c.optimized != null
    }

    private fun thumbnail(image: java.awt.image.BufferedImage): ImageIcon {
        val size = JBUI.scale(THUMBNAIL)
        val ratio = minOf(size.toDouble() / image.width, size.toDouble() / image.height)
        return ImageIcon(image.getScaledInstance((image.width * ratio).toInt().coerceAtLeast(1), (image.height * ratio).toInt().coerceAtLeast(1), java.awt.Image.SCALE_SMOOTH))
    }

    override fun doOKAction() = finish(Mode.COPY)

    /**
     * Closes the dialog with [mode]. In place, with *Compare frames* on, the frames are compared first (unless they already were
     * for this result), and a visible difference has to be confirmed.
     */
    private fun finish(mode: Mode) {
        val options = options()
        val ready = estimate?.takeIf { it.first == options }?.second ?: run(options)
        if (mode == Mode.IN_PLACE && compareFirst.isVisible && compareFirst.isSelected) {
            val c = comparison?.takeIf { it.first === ready }?.second ?: try {
                ProgressManager.getInstance().runProcessWithProgressSynchronously<FrameComparison, Exception>(
                    { FrameComparer.compare(originalBytes(), ready.bytes, isJson) },
                    LottieBundle.message("optimize.compare.running"), true, project,
                ).also { showComparison(ready, it) }
            } catch (e: ProcessCanceledException) {
                return
            } catch (e: Exception) {
                compareLabel.text = LottieBundle.message("optimize.compare.failed", e.message ?: e.toString())
                null
            }
            if (c != null && c.significant) {
                val where = c.worstAnimation?.let { LottieBundle.message("optimize.compare.animation", it) }.orEmpty()
                val proceed = Messages.showYesNoDialog(
                    project,
                    LottieBundle.message("optimize.compare.confirm", c.worstFrame, where, share(c.worstShare)),
                    LottieBundle.message("optimize.compare.confirm.title"),
                    LottieBundle.message("optimize.in.place.anyway"),
                    Messages.getCancelButton(),
                    Messages.getWarningIcon(),
                ) == Messages.YES
                if (!proceed) return
            }
        }
        this.mode = mode
        result = ready
        properties.setValue(KEY_CLEANUP, cleanup.isSelected, true)
        properties.setValue(KEY_ROUND, round.isSelected, true)
        properties.setValue(KEY_DECIMALS, decimals.value as Int, 3)
        properties.setValue(KEY_HIDDEN, hidden.isSelected, false)
        properties.setValue(KEY_IMAGES, images.isSelected, false)
        properties.setValue(KEY_TARGET, (target.selectedItem as ImageTarget).name, ImageTarget.KEEP.name)
        properties.setValue(KEY_QUALITY, quality.value as Int, 80)
        properties.setValue(KEY_SCALE, scale.selectedItem as Int, 2)
        properties.setValue(KEY_COMPARE, compareFirst.isSelected, true)
        super.doOKAction()
    }

    companion object {
        private const val ESTIMATE_DELAY_MS = 250
        private const val KEY_CLEANUP = "cz.xprees.lottie.optimize.cleanup"
        private const val KEY_ROUND = "cz.xprees.lottie.optimize.round"
        private const val KEY_DECIMALS = "cz.xprees.lottie.optimize.decimals"
        private const val KEY_HIDDEN = "cz.xprees.lottie.optimize.hidden"
        private const val KEY_IMAGES = "cz.xprees.lottie.optimize.images"
        private const val KEY_TARGET = "cz.xprees.lottie.optimize.target"
        private const val KEY_QUALITY = "cz.xprees.lottie.optimize.quality"
        private const val KEY_SCALE = "cz.xprees.lottie.optimize.scale"
        private const val KEY_COMPARE = "cz.xprees.lottie.optimize.compare"
        private const val THUMBNAIL = 96

        /** 0.0123 → "1.23%". */
        fun share(value: Double): String = String.format(Locale.ROOT, "%.2f%%", value * 100)

        fun stepName(step: OptimizeStep): String = LottieBundle.message("optimize.step.${step.name.lowercase(Locale.ROOT)}")

        /** −21.4 kB (a saving) or +1.2 kB (a step that grew the file). */
        fun signed(saved: Long): String = when {
            saved == 0L -> StringUtil.formatFileSize(0)
            saved > 0 -> "−" + StringUtil.formatFileSize(saved)
            else -> "+" + StringUtil.formatFileSize(-saved)
        }

        /** −64%, −99.7% (not a rounded −100% for a file that still has bytes), <1% for a small change, 0% for none. */
        fun percent(saved: Long, of: Long): String {
            if (of == 0L || saved == 0L) return "0%"
            val sign = if (saved > 0) "−" else "+"
            val value = kotlin.math.abs(saved * 100.0 / of)
            return when {
                value < 1 -> "$sign<1%"
                value >= 99.5 && value < 100 -> String.format(Locale.ROOT, "%s%.1f%%", sign, minOf(value, 99.9))
                else -> String.format(Locale.ROOT, "%s%.0f%%", sign, value)
            }
        }
    }
}
