package cz.xprees.lottie.preview

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.ColorChooserService
import com.intellij.ui.ColorUtil
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.actions.ExtractAnimationAction
import cz.xprees.lottie.actions.PackDotLottieAction
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.model.LottieInfo
import cz.xprees.lottie.model.LottieModel
import cz.xprees.lottie.palette.ColorUsage
import cz.xprees.lottie.palette.PaletteEntry
import cz.xprees.lottie.palette.PaletteScanner
import cz.xprees.lottie.settings.LottieSettings
import cz.xprees.lottie.settings.LottieSettings.Background
import java.awt.BorderLayout
import java.awt.Color
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The Lottie preview: a JCEF page running the dotLottie player, with a toolbar (playback, speed, markers, zoom, background,
 * dotLottie animation/theme/state machine pickers), a frame scrubber, the color palette and, for `.lottie` files, the manifest.
 *
 * With a [document] (Lottie JSON) it follows edits live: valid JSON is rendered after a short pause, invalid JSON keeps the last
 * good render and shows the parse error. Without one it previews the `.lottie` file and reloads when it changes on disk.
 */
class LottiePreviewPanel(
    private val project: Project,
    val file: VirtualFile,
    private val document: Document?,
    parent: Disposable,
) : JPanel(BorderLayout()), Disposable, UiDataProvider {

    val isJson get() = document != null
    private val settings get() = LottieSettings.getInstance().state

    // ---- state shared with the page -----------------------------------------------------------------------------------
    @Volatile
    private var data: ByteArray? = null
    private var dataVersion = 0
    private var loadedOnce = false

    private var totalFrames = 0.0
    private var durationSeconds = 0.0
    private var animationWidth = 0.0
    private var animationHeight = 0.0
    /** Marker names as the player knows them, with their display labels. */
    private var markers: List<Pair<String, String>> = emptyList()
    private var marker: String? = null
    private var playing = false
    private var frame = 0.0
    private var zoom = 1.0
    private var zoomFit = true
    private var loop = settings.loop
    private var speed = 1.0
    private var background = settings.background
    private var customBackground = settings.customBackground

    // dotLottie
    private var archive: DotLottieArchive? = null
    private var animationId: String? = null
    private var themeId: String? = null
    private var stateMachineId: String? = null

    private var info: LottieInfo? = null
    private var palette: List<PaletteEntry> = emptyList()

    /** Set by the split editor: the root layer index clicked in the preview (null: nothing hit). */
    var onPick: ((Int?) -> Unit)? = null
        set(value) {
            field = value
            browser?.call("setPickEnabled", value != null && settings.previewSync)
        }

    /** Set by the split editor: move the caret to a color's definition. */
    var onNavigateToColor: ((ColorUsage) -> Unit)? = null
        set(value) {
            field = value
            paletteStrip = createPaletteStrip()
        }

    // ---- UI -----------------------------------------------------------------------------------------------------------
    private val browser: PreviewBrowser? = if (JBCefApp.isSupported()) {
        PreviewBrowser(this, ::initConfig, { data }, ::onMessage)
    } else {
        null
    }

    private val errorBanner = EditorNotificationPanel(EditorNotificationPanel.Status.Error).apply { isVisible = false }
    private val north = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private var paletteStrip: PaletteStrip = createPaletteStrip()
        set(value) {
            north.remove(field)
            field = value
            north.add(value)
            value.isVisible = paletteVisible
            value.setPalette(palette)
        }
    private var paletteVisible = PropertiesComponent.getInstance().getBoolean(PALETTE_VISIBLE_KEY, true)
    private var detailsVisible = PropertiesComponent.getInstance().getBoolean(DETAILS_VISIBLE_KEY, true)

    private var playingBeforeScrub = false

    /** When the user last moved the playhead; the page's frame echoes of earlier seeks are ignored for a moment after it. */
    private var lastLocalSeek = 0L
    private var timelineLayersVisible = PropertiesComponent.getInstance().getBoolean(TIMELINE_LAYERS_KEY, false)
    private val timeline: Timeline = Timeline(object : Timeline.Callbacks {
        override fun seek(frame: Double) = seekLocally(frame)

        override fun scrubStarted() {
            playingBeforeScrub = playing
            if (playing) browser?.call("pause")
        }

        override fun scrubEnded() {
            if (playingBeforeScrub) {
                // resuming on purpose: follow the playback right away
                lastLocalSeek = 0
                playing = true
                browser?.call("play")
            }
            playingBeforeScrub = false
        }

        override fun togglePlay() = togglePlayback()

        override fun layerClicked(index: Int) {
            timeline.selectedLayer = index
            val pick = onPick
            if (pick != null) {
                pick(index)
            } else {
                // no code to jump to (.lottie): show the layer's first frame
                timeline.layers.firstOrNull { it.index == index }?.let { seek(it.start) }
            }
            highlightLayer(index)
        }

        override fun markerDoubleClicked(name: String) {
            marker = name
            browser?.call("setMarker", name)
            toolbar.updateActionsAsync()
        }
    }).apply { showLayers = timelineLayersVisible }
    private val frameLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val infoLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = SwingConstants.RIGHT
    }
    private val details = JEditorPane().apply {
        contentType = "text/html"
        editorKit = com.intellij.util.ui.HTMLEditorKitBuilder().build()
        isEditable = false
        border = JBUI.Borders.empty(4, 8)
    }
    private val detailsScroll = JBScrollPane(details).apply { border = JBUI.Borders.customLineLeft(JBColor.border()) }
    private val splitter = JBSplitter(false, "cz.xprees.lottie.details.proportion", 0.72f)
    private val toolbar: ActionToolbar
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        Disposer.register(parent, this)

        toolbar = ActionManager.getInstance().createActionToolbar("LottiePreview", createActions(), true).apply {
            targetComponent = this@LottiePreviewPanel
        }
        north.add(toolbar.component)
        north.add(errorBanner)
        north.add(paletteStrip)
        paletteStrip.isVisible = paletteVisible
        add(north, BorderLayout.NORTH)

        val center: JComponent = browser?.component ?: unsupportedNotice()
        if (isJson) {
            add(center, BorderLayout.CENTER)
        } else {
            splitter.firstComponent = center
            splitter.secondComponent = detailsScroll.takeIf { detailsVisible }
            add(splitter, BorderLayout.CENTER)
        }
        add(createScrubber(), BorderLayout.SOUTH)

        if (document != null) {
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = scheduleUpdate(UPDATE_DELAY_MS)
            }, this)
        } else {
            project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it is VFileContentChangeEvent && it.file == file }) scheduleUpdate(UPDATE_DELAY_MS)
                }
            })
        }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LottieSettings.TOPIC, LottieSettings.Listener {
            browser?.call("setPickEnabled", onPick != null && it.previewSync)
        })
        scheduleUpdate(0)
        updateLabels()
    }

    private fun createPaletteStrip() = PaletteStrip(onNavigateToColor)

    override fun uiDataSnapshot(sink: DataSink) {
        sink[CommonDataKeys.VIRTUAL_FILE] = file
        sink[CommonDataKeys.PROJECT] = project
        sink[KEY] = this
    }

    // ---- content ------------------------------------------------------------------------------------------------------

    private fun scheduleUpdate(delay: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest(::update, delay)
    }

    private class Prepared(
        val bytes: ByteArray?,
        val error: String?,
        val info: LottieInfo?,
        val palette: List<PaletteEntry>,
        val archive: DotLottieArchive?,
    )

    private fun update() {
        val text = document?.immutableCharSequence
        val currentAnimation = animationId
        AppExecutorUtil.getAppExecutorService().execute {
            val prepared = try {
                if (text != null) prepareJson(text) else prepareDotLottie(currentAnimation)
            } catch (e: Exception) {
                Prepared(null, e.message ?: e.toString(), null, emptyList(), null)
            }
            ApplicationManager.getApplication().invokeLater({ if (!disposed) apply(prepared) }, { disposed })
        }
    }

    private fun prepareJson(text: CharSequence): Prepared {
        val root = try {
            MiniJson.parse(text)
        } catch (e: MiniJson.ParseException) {
            return Prepared(null, LottieBundle.message("preview.error.json", e.message ?: ""), null, emptyList(), null)
        }
        val info = LottieModel.read(root) ?: return Prepared(null, LottieBundle.message("preview.error.not.lottie"), null, emptyList(), null)
        val palette = PaletteScanner.palette(PaletteScanner.scan(root))
        return Prepared(text.toString().toByteArray(Charsets.UTF_8), null, info, palette, null)
    }

    private fun prepareDotLottie(currentAnimation: String?): Prepared {
        val bytes = file.contentsToByteArray()
        val archive = DotLottieArchive.read(bytes)
        val id = currentAnimation?.takeIf { a -> archive.manifest.animations.any { it.id == a } } ?: archive.defaultAnimationId
        val root = id?.let(archive::animationText)?.let(MiniJson::parseOrNull)
        return Prepared(bytes, null, LottieModel.read(root), PaletteScanner.palette(PaletteScanner.scan(root)), archive)
    }

    private fun apply(prepared: Prepared) {
        if (prepared.error != null) {
            showError(prepared.error)
            return
        }
        showError(null)
        info = prepared.info
        timeline.layers = timelineLayers(prepared.info)
        palette = prepared.palette
        paletteStrip.setPalette(palette)
        val firstLoad = !loadedOnce
        prepared.archive?.let { archive ->
            val firstArchive = this.archive == null
            this.archive = archive
            details.text = DotLottieDetails.html(archive)
            details.caretPosition = 0
            if (animationId == null || archive.manifest.animations.none { it.id == animationId }) animationId = archive.defaultAnimationId
            if (firstArchive) {
                themeId = archive.manifest.animations.firstOrNull { it.id == animationId }?.initialTheme
                stateMachineId = archive.manifest.initialStateMachine
            }
        }
        data = prepared.bytes
        dataVersion++
        loadedOnce = true
        browser?.call(
            "load",
            mapOf(
                "url" to "${PreviewBrowser.ORIGIN}data/$dataVersion",
                "kind" to if (isJson) "json" else "lottie",
                "keepState" to !firstLoad,
                "animationId" to animationId,
                "themeId" to themeId,
                "marker" to marker,
            ),
        )
        if (firstLoad && stateMachineId != null) browser?.call("setStateMachine", stateMachineId)
        toolbar.updateActionsAsync()
        updateLabels()
    }

    private fun showError(message: String?) {
        errorBanner.isVisible = message != null
        if (message != null) errorBanner.text = message
        north.revalidate()
    }

    // ---- messages from the page ---------------------------------------------------------------------------------------

    private fun initConfig(): Map<String, Any?> {
        val dark = !JBColor.isBright()
        return mapOf(
            "wasmUrl" to "${PreviewBrowser.ORIGIN}web/player/dotlottie-player.wasm",
            "autoplay" to settings.autoplay,
            "loop" to loop,
            "background" to background.cssName,
            "customBackground" to customBackground,
            "colors" to mapOf(
                "checker-a" to if (dark) "#2b2d30" else "#ffffff",
                "checker-b" to if (dark) "#393b40" else "#e8e8e8",
                "text" to "#" + ColorUtil.toHex(UIUtil.getContextHelpForeground()),
                "highlight" to "#" + ColorUtil.toHex(JBUI.CurrentTheme.Focus.focusColor()),
            ),
        )
    }

    private fun onMessage(message: Map<String, Any?>) {
        when (message["type"]) {
            "ready" -> browser?.call("setPickEnabled", onPick != null && settings.previewSync)
            "loaded" -> {
                totalFrames = (message["totalFrames"] as? Number)?.toDouble() ?: 0.0
                durationSeconds = (message["duration"] as? Number)?.toDouble() ?: 0.0
                animationWidth = (message["width"] as? Number)?.toDouble() ?: 0.0
                animationHeight = (message["height"] as? Number)?.toDouble() ?: 0.0
                @Suppress("UNCHECKED_CAST")
                val playerMarkers = (message["markers"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { m ->
                    val name = m["name"] as? String ?: return@mapNotNull null
                    Timeline.Marker(name, m["label"] as? String ?: name, (m["start"] as? Number)?.toDouble() ?: 0.0, (m["end"] as? Number)?.toDouble() ?: 0.0)
                }
                markers = playerMarkers.map { it.name to it.label }
                timeline.markers = playerMarkers
                if (marker != null && markers.none { it.first == marker }) marker = null
                playing = message["playing"] == true
                (message["activeAnimationId"] as? String)?.let { animationId = it }
                if (!isJson) themeId = message["activeThemeId"] as? String
                timeline.lastFrame = (totalFrames - 1).coerceAtLeast(0.0)
                timeline.frameRate = frameRate()
                showError(null)
                toolbar.updateActionsAsync()
                updateLabels()
            }
            "frame" -> {
                // A local seek pauses the player: messages arriving right after it are echoes of earlier seeks or of the playback
                // before the pause, and would undo a quick series of steps (arrow keys, dragging)
                if (System.currentTimeMillis() - lastLocalSeek < LOCAL_SEEK_GRACE_MS) return
                playing = message["playing"] == true
                frame = (message["frame"] as? Number)?.toDouble() ?: 0.0
                timeline.frame = frame
                updateLabels()
            }
            "playing" -> {
                playing = message["playing"] == true
                toolbar.updateActionsAsync()
            }
            "zoom" -> {
                zoom = (message["zoom"] as? Number)?.toDouble() ?: 1.0
                zoomFit = message["mode"] == "fit"
                updateLabels()
            }
            "pick" -> onPick?.invoke((message["index"] as? Number)?.toInt())
            "error" -> showError(LottieBundle.message("preview.error.render", message["message"] as? String ?: ""))
        }
    }

    private fun frameRate(): Double = info?.frameRate?.takeIf { it > 0 } ?: if (durationSeconds > 0) totalFrames / durationSeconds else 0.0

    /** Root layers as timeline lanes, in player frames (relative to the animation's in point). */
    private fun timelineLayers(info: LottieInfo?): List<Timeline.Layer> {
        if (info == null) return emptyList()
        val last = info.frames
        return info.layers.map { layer ->
            Timeline.Layer(
                index = layer.position,
                label = layer.label,
                type = layer.typeName,
                start = ((layer.inPoint ?: info.inPoint) - info.inPoint).coerceIn(0.0, last),
                end = ((layer.outPoint ?: info.outPoint) - info.inPoint).coerceIn(0.0, last),
                hidden = layer.hidden,
            )
        }
    }

    /** Moves the playhead to [target] right away (timeline, labels) and seeks the player, which pauses it. */
    private fun seekLocally(target: Double) {
        lastLocalSeek = System.currentTimeMillis()
        if (playing) {
            playing = false
            toolbar.updateActionsAsync()
        }
        frame = target
        timeline.frame = target
        updateLabels()
        browser?.call("seek", target)
    }

    private fun togglePlayback() {
        if (!playing) lastLocalSeek = 0
        browser?.call(if (playing) "pause" else "play")
        playing = !playing
        toolbar.updateActionsAsync()
    }

    private fun updateLabels() {
        val fps = frameRate()
        val time = if (fps > 0) frame / fps else 0.0
        frameLabel.text = LottieBundle.message(
            "preview.frame",
            frame.toInt(),
            (totalFrames - 1).toInt().coerceAtLeast(0),
            String.format(Locale.ROOT, "%.2f", time),
            String.format(Locale.ROOT, "%.2f", durationSeconds),
        )
        infoLabel.text = if (animationWidth > 0) {
            LottieBundle.message(
                "preview.info",
                animationWidth.toInt(),
                animationHeight.toInt(),
                String.format(Locale.ROOT, "%.4g", fps).trimEnd('0').trimEnd('.'),
                (zoom * 100).toInt(),
            )
        } else {
            ""
        }
    }

    // ---- API for the split editor ------------------------------------------------------------------------------------

    /** Outlines root layer [index] in the preview (null clears it). */
    fun highlightLayer(index: Int?) {
        timeline.selectedLayer = index
        if (settings.previewSync) browser?.call("highlightLayer", index)
    }

    /** Pauses and shows [frame] (relative to the animation's in point). */
    fun seek(frame: Double) {
        seekLocally(frame.coerceIn(0.0, (totalFrames - 1).coerceAtLeast(0.0)))
    }

    /** [seek], but only while paused, so moving the caret doesn't stop a playing animation. */
    fun seekIfPaused(frame: Double) {
        if (!playing) seek(frame)
    }

    val inPoint: Double get() = info?.inPoint ?: 0.0

    // ---- UI pieces ----------------------------------------------------------------------------------------------------

    private fun unsupportedNotice(): JComponent = JPanel(GridBagLayout()).apply {
        add(JBLabel(LottieBundle.message("preview.jcef.unsupported")).apply {
            foreground = UIUtil.getContextHelpForeground()
            horizontalAlignment = SwingConstants.CENTER
        }, GridBagConstraints())
    }

    private fun createScrubber(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.customLineTop(JBColor.border())
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(2, 8)
            add(frameLabel, BorderLayout.WEST)
            add(infoLabel, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(timeline.component, BorderLayout.CENTER)
    }

    private fun createActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(FrameStepAction(LottieBundle.message("action.frame.first"), AllIcons.Actions.Play_first) { 0.0 })
        add(FrameStepAction(LottieBundle.message("action.frame.previous"), AllIcons.Actions.Play_back) { Math.round(frame) - 1.0 })
        add(PlayPauseAction())
        add(FrameStepAction(LottieBundle.message("action.frame.next"), AllIcons.Actions.Play_forward) { Math.round(frame) + 1.0 })
        add(FrameStepAction(LottieBundle.message("action.frame.last"), AllIcons.Actions.Play_last) { totalFrames - 1 })
        add(LoopAction())
        add(SpeedAction())
        add(MarkerAction())
        add(Separator.getInstance())
        add(ZoomAction(LottieBundle.message("action.zoom.fit"), AllIcons.General.FitContent) { browser?.call("setZoom", "fit", 1.0) })
        add(ZoomAction(LottieBundle.message("action.zoom.actual"), AllIcons.General.ActualZoom) { browser?.call("setZoom", "fixed", 1.0) })
        add(ZoomAction(LottieBundle.message("action.zoom.in"), AllIcons.General.ZoomIn) { browser?.call("setZoom", "fixed", zoom * 1.25) })
        add(ZoomAction(LottieBundle.message("action.zoom.out"), AllIcons.General.ZoomOut) { browser?.call("setZoom", "fixed", zoom / 1.25) })
        add(BackgroundAction())
        if (!isJson) {
            add(Separator.getInstance())
            add(AnimationAction())
            add(ThemeAction())
            add(StateMachineAction())
        }
        add(Separator.getInstance())
        add(PaletteToggleAction())
        add(TimelineLayersToggleAction())
        if (isJson) {
            add(PackDotLottieAction())
        } else {
            add(DetailsToggleAction())
            add(ExtractAnimationAction())
        }
    }

    private abstract inner class PreviewAction(text: String, icon: javax.swing.Icon?) : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class PlayPauseAction : PreviewAction(LottieBundle.message("action.play"), AllIcons.Actions.Execute) {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = if (playing) AllIcons.Actions.Pause else AllIcons.Actions.Execute
            e.presentation.text = LottieBundle.message(if (playing) "action.pause" else "action.play")
            e.presentation.isEnabled = browser != null
        }

        override fun actionPerformed(e: AnActionEvent) = togglePlayback()
    }

    /** Pauses and jumps to the frame [target] computes (clamped to the animation). */
    private inner class FrameStepAction(text: String, icon: javax.swing.Icon, private val target: () -> Double) : PreviewAction(text, icon) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = browser != null && totalFrames > 0
        }

        override fun actionPerformed(e: AnActionEvent) = seekLocally(target().coerceIn(0.0, (totalFrames - 1).coerceAtLeast(0.0)))
    }

    private inner class TimelineLayersToggleAction : DumbAwareToggleAction(LottieBundle.message("action.timeline.layers"), null, AllIcons.Actions.ShowAsTree) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = timelineLayersVisible
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            timelineLayersVisible = state
            PropertiesComponent.getInstance().setValue(TIMELINE_LAYERS_KEY, state, false)
            timeline.showLayers = state
        }
    }

    private inner class LoopAction : DumbAwareToggleAction(LottieBundle.message("action.loop"), null, AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = loop
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            loop = state
            browser?.call("setLoop", state)
        }
    }

    private inner class ZoomAction(text: String, icon: javax.swing.Icon, private val run: () -> Unit) : PreviewAction(text, icon) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = browser != null
        }

        override fun actionPerformed(e: AnActionEvent) = run()
    }

    /** A combo box in the toolbar listing [options]; the selected one is shown as its text. */
    private abstract inner class ChoiceAction<T>(private val tooltip: String) : ComboBoxAction() {
        abstract fun options(): List<T>
        abstract fun selected(): T
        abstract fun label(option: T): String
        abstract fun select(option: T)
        open fun visible(): Boolean = true

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = visible()
            e.presentation.isEnabled = browser != null
            e.presentation.text = label(selected())
            e.presentation.description = tooltip
        }

        override fun createPopupActionGroup(button: JComponent, dataContext: com.intellij.openapi.actionSystem.DataContext): DefaultActionGroup =
            DefaultActionGroup().apply {
                for (option in options()) {
                    add(object : DumbAwareToggleAction(label(option)) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT
                        override fun isSelected(e: AnActionEvent) = option == selected()
                        override fun setSelected(e: AnActionEvent, state: Boolean) {
                            select(option)
                            toolbar.updateActionsAsync()
                        }
                    })
                }
            }
    }

    private inner class SpeedAction : ChoiceAction<Double>(LottieBundle.message("action.speed")) {
        override fun options() = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 4.0)
        override fun selected() = speed
        override fun label(option: Double) = String.format(Locale.ROOT, "%sx", option.toString().removeSuffix(".0"))
        override fun select(option: Double) {
            speed = option
            browser?.call("setSpeed", option)
        }
    }

    private inner class MarkerAction : ChoiceAction<String?>(LottieBundle.message("action.marker")) {
        override fun options() = listOf<String?>(null) + markers.map { it.first }
        override fun selected() = marker
        override fun label(option: String?) = option?.let { name -> markers.firstOrNull { it.first == name }?.second ?: name }
            ?: LottieBundle.message("action.marker.all")
        override fun visible() = markers.isNotEmpty()
        override fun select(option: String?) {
            marker = option
            browser?.call("setMarker", option)
        }
    }

    private inner class BackgroundAction : ChoiceAction<Background>(LottieBundle.message("action.background")) {
        override fun options() = Background.entries
        override fun selected() = background
        override fun label(option: Background) = LottieBundle.message("background.${option.name.lowercase()}")
        override fun select(option: Background) {
            if (option == Background.CUSTOM) {
                val current = runCatching { ColorUtil.fromHex(customBackground) }.getOrDefault(Color.GRAY)
                val chosen = ColorChooserService.Companion.getInstance().showDialog(project, this@LottiePreviewPanel, LottieBundle.message("background.custom.title"), current, false, emptyList(), true)
                    ?: return
                customBackground = "#" + ColorUtil.toHex(chosen)
            }
            background = option
            browser?.call("setBackground", option.cssName, customBackground)
        }
    }

    private inner class AnimationAction : ChoiceAction<String?>(LottieBundle.message("action.animation")) {
        override fun options() = archive?.manifest?.animations?.map { it.id }.orEmpty()
        override fun selected() = animationId
        override fun label(option: String?) = option ?: ""
        override fun visible() = (archive?.manifest?.animations?.size ?: 0) > 1
        override fun select(option: String?) {
            if (option == null || option == animationId) return
            animationId = option
            browser?.call("selectAnimation", option)
            // palette and info follow the selected animation
            scheduleUpdate(0)
        }
    }

    private inner class ThemeAction : ChoiceAction<String?>(LottieBundle.message("action.theme")) {
        override fun options() = listOf<String?>(null) + archive?.manifest?.themes?.map { it.id }.orEmpty()
        override fun selected() = themeId
        override fun label(option: String?) = option?.let { id -> archive?.manifest?.themes?.firstOrNull { it.id == id }?.label ?: id }
            ?: LottieBundle.message("action.theme.none")
        override fun visible() = archive?.manifest?.themes?.isNotEmpty() == true
        override fun select(option: String?) {
            themeId = option
            browser?.call("setTheme", option)
        }
    }

    private inner class StateMachineAction : ChoiceAction<String?>(LottieBundle.message("action.state.machine")) {
        override fun options() = listOf<String?>(null) + archive?.manifest?.stateMachines?.map { it.id }.orEmpty()
        override fun selected() = stateMachineId
        override fun label(option: String?) = option?.let { id -> archive?.manifest?.stateMachines?.firstOrNull { it.id == id }?.label ?: id }
            ?: LottieBundle.message("action.state.machine.none")
        override fun visible() = archive?.manifest?.stateMachines?.isNotEmpty() == true
        override fun select(option: String?) {
            stateMachineId = option
            browser?.call("setStateMachine", option)
        }
    }

    private inner class PaletteToggleAction : DumbAwareToggleAction(LottieBundle.message("action.palette"), null, AllIcons.Actions.Colors) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = paletteVisible
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            paletteVisible = state
            PropertiesComponent.getInstance().setValue(PALETTE_VISIBLE_KEY, state, true)
            paletteStrip.isVisible = state
            north.revalidate()
        }
    }

    private inner class DetailsToggleAction : DumbAwareToggleAction(LottieBundle.message("action.details"), null, AllIcons.Actions.PreviewDetails) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = detailsVisible
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            detailsVisible = state
            PropertiesComponent.getInstance().setValue(DETAILS_VISIBLE_KEY, state, true)
            splitter.secondComponent = detailsScroll.takeIf { state }
        }
    }

    @Volatile
    private var disposed = false

    override fun dispose() {
        disposed = true
    }

    companion object {
        val KEY = com.intellij.openapi.actionSystem.DataKey.create<LottiePreviewPanel>("cz.xprees.lottie.previewPanel")

        private const val UPDATE_DELAY_MS = 300
        private const val PALETTE_VISIBLE_KEY = "cz.xprees.lottie.palette.visible"
        private const val DETAILS_VISIBLE_KEY = "cz.xprees.lottie.details.visible"
        private const val TIMELINE_LAYERS_KEY = "cz.xprees.lottie.timeline.layers"
        private const val LOCAL_SEEK_GRACE_MS = 400L
    }
}

private val Background.cssName get() = name.lowercase()
