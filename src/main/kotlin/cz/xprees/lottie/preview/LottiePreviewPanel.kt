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
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
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
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.actions.ExtractAnimationAction
import cz.xprees.lottie.actions.PackDotLottieAction
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieFiles
import cz.xprees.lottie.statemachine.InputType
import cz.xprees.lottie.statemachine.StateMachine
import cz.xprees.lottie.statemachine.StateMachineDocuments
import cz.xprees.lottie.statemachine.StateMachineInput
import cz.xprees.lottie.statemachine.StateMachineInputDialog
import cz.xprees.lottie.statemachine.StateMachinePanel
import cz.xprees.lottie.statemachine.StateMachineVirtualFile
import com.intellij.ui.components.JBTabbedPane
import cz.xprees.lottie.editor.LottieMarkerEditor
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.model.LottieInfo
import cz.xprees.lottie.model.LottieModel
import cz.xprees.lottie.palette.ColorUsage
import cz.xprees.lottie.palette.PaletteEntry
import cz.xprees.lottie.palette.PaletteScanner
import cz.xprees.lottie.palette.Recolor
import cz.xprees.lottie.palette.RecolorDialog
import cz.xprees.lottie.palette.RecolorWriter
import cz.xprees.lottie.settings.LottieSettings
import cz.xprees.lottie.settings.LottieSettings.Background
import cz.xprees.lottie.snippets.CodePlatform
import cz.xprees.lottie.snippets.CodePlatforms
import cz.xprees.lottie.snippets.CodeSnippetsPanel
import cz.xprees.lottie.snippets.SnippetContext
import cz.xprees.lottie.snippets.SnippetGenerator
import cz.xprees.lottie.snippets.SnippetMarker
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
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
    /** The size the info line shows, from the last successful update. */
    private var fileSize: String? = null
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
    private val browser: PreviewBrowser? = if (JcefSupport.isAvailable()) {
        PreviewBrowser(this, ::initConfig, { _ -> data }, ::onMessage)
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
    /** The Code tab of JSON files (their only side tab); `.lottie` files show it with the details. */
    private var codeVisible = PropertiesComponent.getInstance().getBoolean(CODE_VISIBLE_KEY, false)

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

        override fun playMarker(marker: Timeline.Marker) {
            this@LottiePreviewPanel.marker = marker.name
            browser?.call("setMarker", marker.name)
            updateToolbars()
        }

        override fun addMarker(frame: Double) {
            val editor = markerEditor ?: return
            val name = askMarkerName(LottieBundle.message("marker.add.title"), defaultMarkerName(), null) ?: return
            editor.add(name, frame + inPoint)
        }

        override fun renameMarker(marker: Timeline.Marker) {
            val index = marker.index ?: return
            val name = askMarkerName(LottieBundle.message("marker.rename.title"), marker.label, marker) ?: return
            if (name != marker.label) markerEditor?.rename(index, name)
        }

        override fun deleteMarker(marker: Timeline.Marker) {
            marker.index?.let { markerEditor?.delete(it) }
        }

        override fun changeMarker(marker: Timeline.Marker, start: Double, end: Double) {
            val index = marker.index ?: return
            markerEditor?.setRange(index, start + inPoint, end - start)
        }

        override fun markerCode(marker: Timeline.Marker): Pair<String, String> {
            val platform = CodePlatforms.getInstance(project).current
            val code = SnippetGenerator.playMarker(platform, SnippetMarker(marker.name, marker.start + inPoint, marker.end - marker.start, marker.label))
            return code to LottieBundle.message("code.platform.${platform.name.lowercase()}")
        }
    }).apply { showLayers = timelineLayersVisible }
    /** Writes marker edits made on the timeline into the JSON document (null for .lottie files, which are read-only). */
    private val markerEditor: LottieMarkerEditor? = document?.let { LottieMarkerEditor(project, it) }

    private fun defaultMarkerName(): String {
        val taken = timeline.markers.map { it.label }.toSet()
        return generateSequence(timeline.markers.size + 1) { it + 1 }.map { "marker_$it" }.first { it !in taken }
    }

    /** Asks for a marker name: not blank, and not the name of another marker (players look markers up by name). */
    private fun askMarkerName(title: String, initial: String, editing: Timeline.Marker?): String? {
        val others = timeline.markers.filter { it != editing }.map { it.label }.toSet()
        return Messages.showInputDialog(project, LottieBundle.message("marker.name.prompt"), title, null, initial, object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? = when {
                inputString.isBlank() -> LottieBundle.message("marker.name.blank")
                inputString.trim() in others -> LottieBundle.message("marker.name.duplicate", inputString.trim())
                else -> null
            }
        })?.trim()
    }

    private lateinit var timelineToolbar: ActionToolbar

    private fun updateToolbars() {
        toolbar.updateActionsAsync()
        if (::timelineToolbar.isInitialized) timelineToolbar.updateActionsAsync()
    }

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
        // read-only text: Undo with the focus here undoes the file's last change, not text typing
        putClientProperty(com.intellij.ide.actions.UndoRedoAction.IGNORE_SWING_UNDO_MANAGER, true)
    }
    private val detailsScroll = JBScrollPane(details).apply { border = JBUI.Borders.customLineLeft(JBColor.border()) }
    private val splitter = JBSplitter(false, "cz.xprees.lottie.details.proportion", 0.72f)

    /** The side panel: for `.lottie` files the details, the state machine when the file has one, and the code; for JSON the code. */
    private val sideTabs = JBTabbedPane()
    private val stateMachinePanel by lazy { StateMachinePanel(stateMachineCallbacks) }
    private val codePanel = CodeSnippetsPanel(project, this)
    /** The shown state machine as its file defines it, and the values of the running one (for the Code tab). */
    private var shownMachine: StateMachine? = null
    private var liveValues: Map<String, Any?> = emptyMap()
    private val toolbar: ActionToolbar
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        Disposer.register(parent, this)
        // takes the focus back after dialogs, so that the IDE's shortcuts (Undo) act on this file
        isFocusable = true

        toolbar = ActionManager.getInstance().createActionToolbar("LottiePreview", createActions(), true).apply {
            targetComponent = this@LottiePreviewPanel
        }
        north.add(toolbar.component)
        north.add(errorBanner)
        north.add(paletteStrip)
        paletteStrip.isVisible = paletteVisible
        add(north, BorderLayout.NORTH)

        val center: JComponent = browser?.component ?: unsupportedNotice()
        if (!isJson) sideTabs.addTab(LottieBundle.message("details.tab"), detailsScroll)
        sideTabs.addTab(LottieBundle.message("code.tab"), codePanel)
        sideTabs.addChangeListener { updateCodeActive() }
        splitter.firstComponent = center
        splitter.secondComponent = sideTabs.takeIf { sidePanelVisible }
        add(splitter, BorderLayout.CENTER)
        updateCodeActive()
        add(createScrubber(), BorderLayout.SOUTH)
        PlaybackKeys.install(this, enabled = { totalFrames > 0 }) { onPlaybackKey(it, fromPage = false) }

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

    private fun createPaletteStrip() = PaletteStrip(onNavigateToColor, ::recolor)

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
        /** The file size as the info line shows it: the document, or the archive and the shown animation's JSON. */
        val size: String? = null,
    )

    private fun update() {
        val text = document?.immutableCharSequence
        val currentAnimation = animationId
        val currentTheme = themeId
        val themeKnown = archive != null
        AppExecutorUtil.getAppExecutorService().execute {
            val prepared = try {
                if (text != null) prepareJson(text) else prepareDotLottie(currentAnimation, currentTheme, themeKnown)
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
        val bytes = text.toString().toByteArray(Charsets.UTF_8)
        return Prepared(bytes, null, info, palette, null, StringUtil.formatFileSize(bytes.size.toLong()))
    }

    /** [currentTheme]: the theme shown ([themeKnown] false before the first load: the animation's initial theme then). */
    private fun prepareDotLottie(currentAnimation: String?, currentTheme: String?, themeKnown: Boolean): Prepared {
        val bytes = file.contentsToByteArray()
        val archive = DotLottieArchive.read(bytes)
        val id = currentAnimation?.takeIf { a -> archive.manifest.animations.any { it.id == a } } ?: archive.defaultAnimationId
        val text = id?.let(archive::animationText)
        val parsed = text?.let(MiniJson::parseOrNull)
        val theme = if (themeKnown) currentTheme else archive.manifest.animations.firstOrNull { it.id == id }?.initialTheme
        // the palette shows the colors on screen: with a theme, the theme's colors for the slots it sets
        val root = theme?.let(archive::themeText)?.let(MiniJson::parseOrNull)?.let { Recolor.themed(parsed, it, id) } ?: parsed
        val size = StringUtil.formatFileSize(bytes.size.toLong()) +
            (text?.let { " " + LottieBundle.message("preview.size.json", StringUtil.formatFileSize(it.toByteArray(Charsets.UTF_8).size.toLong())) } ?: "")
        return Prepared(bytes, null, LottieModel.read(root), PaletteScanner.palette(PaletteScanner.scan(root)), archive, size)
    }

    private fun apply(prepared: Prepared) {
        if (prepared.error != null) {
            showError(prepared.error)
            return
        }
        showError(null)
        info = prepared.info
        fileSize = prepared.size
        timeline.layers = timelineLayers(prepared.info)
        if (isJson) {
            val ip = prepared.info?.inPoint ?: 0.0
            timeline.markers = prepared.info?.markers.orEmpty().map {
                Timeline.Marker(it.comment, it.name, it.start - ip, it.end - ip, it.index)
            }
            timeline.editableMarkers = document?.isWritable == true
        }
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
            // a theme that is gone (deleted, or its creation undone)
            if (themeId != null && archive.manifest.themes.none { it.id == themeId } && archive.themeText(themeId!!) == null) themeId = null
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
                // the page starts it once the file is loaded, also after a reload with a changed definition
                "stateMachineId" to stateMachineId,
            ),
        )
        updateStateMachineTab()
        updateCode()
        updateToolbars()
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
                // JSON files take the timeline's markers from the document (editable, with their array index)
                if (!isJson) timeline.markers = playerMarkers
                if (marker != null && markers.none { it.first == marker }) marker = null
                playing = message["playing"] == true
                (message["activeAnimationId"] as? String)?.let { animationId = it }
                if (!isJson) themeId = message["activeThemeId"] as? String
                timeline.lastFrame = (totalFrames - 1).coerceAtLeast(0.0)
                timeline.frameRate = frameRate()
                showError(null)
                updateToolbars()
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
                updateToolbars()
            }
            "zoom" -> {
                zoom = (message["zoom"] as? Number)?.toDouble() ?: 1.0
                zoomFit = message["mode"] == "fit"
                updateLabels()
            }
            "pick" -> onPick?.invoke((message["index"] as? Number)?.toInt())
            "key" -> PlaybackKeys.fromPage(
                message["key"] as? String,
                shift = message["shift"] == true,
                ctrl = message["ctrl"] == true,
                meta = message["meta"] == true,
                alt = message["alt"] == true,
            )?.takeIf { totalFrames > 0 }?.let { onPlaybackKey(it, fromPage = true) }
            "error" -> showError(LottieBundle.message("preview.error.render", message["message"] as? String ?: ""))
            "smState" -> if (!isJson) {
                val snapshot = StateMachinePanel.Snapshot.of(message)
                stateMachinePanel.showSnapshot(snapshot)
                liveValues = if (snapshot.running && snapshot.id == shownStateMachineId) snapshot.values else emptyMap()
                updateCode()
            }
            "smEvent" -> if (!isJson) stateMachinePanel.logEvent(message)
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
            updateToolbars()
        }
        frame = target
        timeline.frame = target
        updateLabels()
        browser?.call("seek", target)
    }

    private class KeyPress(val key: PlaybackKey, val fromPage: Boolean, val time: Long)

    private var lastKeyPress: KeyPress? = null

    /**
     * A playback key from the Swing bindings or from the page. With the focus in the page, the embedded browser can deliver one
     * press both ways: the copy from the other source right after it is dropped. Repeats of a held key come from one source.
     */
    private fun onPlaybackKey(key: PlaybackKey, fromPage: Boolean) {
        val now = System.currentTimeMillis()
        val last = lastKeyPress
        if (last != null && last.key == key && last.fromPage != fromPage && now - last.time < DUPLICATE_KEY_MS) {
            lastKeyPress = null
            return
        }
        lastKeyPress = KeyPress(key, fromPage, now)
        playbackKey(key)
    }

    fun playbackKey(key: PlaybackKey) {
        val last = (totalFrames - 1).coerceAtLeast(0.0)
        fun step(delta: Double) = seekLocally((Math.round(frame) + delta).toDouble().coerceIn(0.0, last))
        when (key) {
            PlaybackKey.TOGGLE -> togglePlayback()
            PlaybackKey.PREVIOUS -> step(-1.0)
            PlaybackKey.NEXT -> step(1.0)
            PlaybackKey.BACK_10 -> step(-10.0)
            PlaybackKey.FORWARD_10 -> step(10.0)
            PlaybackKey.FIRST -> seekLocally(0.0)
            PlaybackKey.LAST -> seekLocally(last)
        }
    }

    private fun togglePlayback() {
        if (!playing) lastLocalSeek = 0
        browser?.call(if (playing) "pause" else "play")
        playing = !playing
        updateToolbars()
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
            ) + (fileSize?.let { " · $it" } ?: "")
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

    /** Playback controls in the timeline's header row. */
    private fun createTimelineActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(FrameStepAction(LottieBundle.message("action.frame.first"), AllIcons.Actions.Play_first) { 0.0 })
        add(FrameStepAction(LottieBundle.message("action.frame.previous"), AllIcons.Actions.Play_back) { Math.round(frame) - 1.0 })
        add(PlayPauseAction())
        add(FrameStepAction(LottieBundle.message("action.frame.next"), AllIcons.Actions.Play_forward) { Math.round(frame) + 1.0 })
        add(FrameStepAction(LottieBundle.message("action.frame.last"), AllIcons.Actions.Play_last) { totalFrames - 1 })
        add(Separator.getInstance())
        add(LoopAction())
        add(SpeedAction())
        if (isJson) {
            add(Separator.getInstance())
            add(AddMarkerAction())
        }
    }

    private fun createScrubber(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.customLineTop(JBColor.border())
        timelineToolbar = ActionManager.getInstance().createActionToolbar("LottieTimeline", createTimelineActions(), true).apply {
            targetComponent = this@LottiePreviewPanel
            setReservePlaceAutoPopupIcon(false)
            component.border = JBUI.Borders.empty()
            component.isOpaque = false
        }
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.emptyRight(8)
            add(timelineToolbar.component, BorderLayout.WEST)
            // the frame readout stays; size, fps and zoom are cut short first when the pane is narrow
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(frameLabel, BorderLayout.WEST)
                add(infoLabel.apply { minimumSize = Dimension(0, 0) }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(timeline.component, BorderLayout.CENTER)
    }

    private fun createActions(): DefaultActionGroup = DefaultActionGroup().apply {
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
        add(CodeToggleAction())
        add(cz.xprees.lottie.actions.OptimizeLottieAction())
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

    private inner class AddMarkerAction : PreviewAction(LottieBundle.message("action.marker.add"), AllIcons.General.Add) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = timeline.editableMarkers && totalFrames > 0
        }

        override fun actionPerformed(e: AnActionEvent) {
            val name = askMarkerName(LottieBundle.message("marker.add.title"), defaultMarkerName(), null) ?: return
            markerEditor?.add(name, Math.round(frame) + inPoint)
        }
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
                            updateToolbars()
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
            // the palette follows the theme
            scheduleUpdate(0)
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
            updateStateMachineTab()
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

    // ---- state machine ------------------------------------------------------------------------------------------------

    /** The state machine the panel shows: the running one, or else the file's first. */
    private val shownStateMachineId: String? get() = stateMachineId ?: archive?.manifest?.stateMachines?.firstOrNull()?.id

    /** Adds or removes the State machine tab and shows the current definition in it. */
    private fun updateStateMachineTab() {
        val archive = archive ?: return
        val index = sideTabs.indexOfComponent(stateMachinePanel)
        if (archive.manifest.stateMachines.isEmpty()) {
            if (index >= 0) sideTabs.removeTabAt(index)
            return
        }
        if (index < 0) sideTabs.insertTab(LottieBundle.message("sm.tab"), null, stateMachinePanel, null, 1)
        val id = shownStateMachineId
        shownMachine = id?.let { archive.stateMachineText(it) }?.let(StateMachine::parse)
        stateMachinePanel.showDefinition(id, shownMachine)
        updateCode()
    }

    // ---- recolor ------------------------------------------------------------------------------------------------------

    /** Changes every use of a palette color: in the JSON document, or in the `.lottie` file's animation or one of its themes. */
    private fun recolor(entry: PaletteEntry) {
        try {
            recolorWithDialog(entry)
        } finally {
            // the focus back on the preview (not a text field it had before), so that Ctrl+Z undoes the change right away
            ApplicationManager.getApplication().invokeLater({
                if (!disposed) com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(this, true)
            }, com.intellij.openapi.application.ModalityState.nonModal())
        }
    }

    private fun recolorWithDialog(entry: PaletteEntry) {
        val document = document
        if (document != null) {
            val dialog = RecolorDialog(project, entry, null, null, false)
            if (!dialog.showAndGet() || dialog.color == entry.color) return
            RecolorWriter.toDocument(project, document, entry.usages, dialog.color)
            return
        }
        val archive = archive ?: return
        val id = animationId ?: archive.defaultAnimationId ?: return
        val v2 = archive.manifest.version?.startsWith("2") == true
        val dialog = RecolorDialog(project, entry, archive.manifest.themes, themeId?.takeIf { v2 }, v2)
        if (!dialog.showAndGet()) return
        val color = dialog.color
        if (color.rgba == entry.color.rgba) return
        when (dialog.target) {
            RecolorDialog.Target.ANIMATION -> RecolorWriter.toDotLottieAnimation(project, file, id, entry.usages, color)
            RecolorDialog.Target.THEME -> {
                if (entry.usages.none(Recolor::themable)) {
                    Messages.showInfoMessage(project, LottieBundle.message("recolor.theme.nothing"), LottieBundle.message("recolor.title", entry.color.hex))
                    return
                }
                val theme = dialog.themeId
                // show the theme once the file reloads
                themeId = theme
                val (themed, skipped) = RecolorWriter.toDotLottieTheme(project, file, id, entry.usages, entry.color, color, theme, dialog.newThemeName)
                cz.xprees.lottie.actions.LottieNotifications.info(project, LottieBundle.message("recolor.done.theme", theme, themed, skipped))
            }
        }
    }

    // ---- code snippets ------------------------------------------------------------------------------------------------

    private val sidePanelVisible get() = if (isJson) codeVisible else detailsVisible

    private fun updateCodeActive() {
        codePanel.active = sidePanelVisible && sideTabs.selectedComponent === codePanel
    }

    /** Hands the Code tab what its snippets use: the file, its markers and the shown state machine. */
    private fun updateCode() {
        val info = info
        val root = project.guessProjectDir()
        val path = root?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, it) } ?: file.name
        val machine = shownMachine.takeIf { !isJson }
        codePanel.setContext(
            SnippetContext(
                path = path,
                dotLottie = !isJson,
                markers = info?.markers.orEmpty().map { SnippetMarker(it.comment, it.start, it.duration, it.name) },
                inPoint = info?.inPoint ?: 0.0,
                stateMachineId = shownStateMachineId.takeIf { machine != null },
                inputs = machine?.inputs.orEmpty(),
                values = liveValues,
                customEvents = machine?.customEvents.orEmpty(),
            ),
        )
    }

    /** The Code tab's platform for state machine code: the picked one, or dotLottie web when that one has no state machines. */
    private fun stateMachinePlatform(): CodePlatform = CodePlatforms.getInstance(project).current.takeIf { it.stateMachines } ?: CodePlatform.DOTLOTTIE_WEB

    private inner class CodeToggleAction :
        DumbAwareToggleAction(LottieBundle.message("action.code"), LottieBundle.message("action.code.description"), AllIcons.Actions.ShowCode) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = sidePanelVisible && sideTabs.selectedComponent === codePanel
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (isJson) {
                codeVisible = state
                PropertiesComponent.getInstance().setValue(CODE_VISIBLE_KEY, state, false)
                splitter.secondComponent = sideTabs.takeIf { state }
            } else if (state) {
                detailsVisible = true
                PropertiesComponent.getInstance().setValue(DETAILS_VISIBLE_KEY, true, true)
                splitter.secondComponent = sideTabs
                sideTabs.selectedComponent = codePanel
            } else {
                sideTabs.selectedIndex = 0
            }
            updateCodeActive()
        }
    }

    private val stateMachineCallbacks = object : StateMachinePanel.Callbacks {
        override fun setInput(name: String, type: InputType, value: Any?) {
            browser?.call("smSetInput", name, type.json, value)
        }

        override fun fire(name: String) {
            browser?.call("smFire", name)
        }

        override fun reset(name: String) {
            browser?.call("smReset", name)
        }

        override fun restart() {
            val id = shownStateMachineId ?: return
            stateMachineId = id
            browser?.call("setStateMachine", id)
            updateToolbars()
        }

        override fun editJson() {
            val id = shownStateMachineId ?: return
            StateMachineDocuments.getInstance(project).open(file, id)
        }

        override fun addInput() {
            val machine = currentStateMachine() ?: return
            val dialog = StateMachineInputDialog(project, null, machine.inputs.map { it.name }.toSet())
            if (!dialog.showAndGet()) return
            val input = dialog.result
            editStateMachine(LottieBundle.message("sm.command.add.input")) { it.addInput(input) }
        }

        override fun editInput(input: StateMachineInput) {
            val machine = currentStateMachine() ?: return
            val dialog = StateMachineInputDialog(project, input, machine.inputs.map { it.name }.toSet())
            if (!dialog.showAndGet()) return
            val edited = dialog.result
            editStateMachine(LottieBundle.message("sm.command.edit.input")) {
                var m = it
                if (edited.name != input.name) m = m.renameInput(input.name, edited.name)
                if (edited.value != input.value) m = m.setInitialValue(edited.name, edited.value)
                m
            }
        }

        override fun deleteInput(input: StateMachineInput) {
            editStateMachine(LottieBundle.message("sm.command.delete.input")) { it.deleteInput(input.name) }
        }

        override fun inputCode(input: StateMachineInput, value: Any?): String = SnippetGenerator.input(stateMachinePlatform(), input, value).orEmpty()

        override fun customEventsCode(): String = SnippetGenerator.customEvents(stateMachinePlatform(), shownMachine?.customEvents.orEmpty()).orEmpty()

        override fun codePlatformName(): String = LottieBundle.message("code.platform.${stateMachinePlatform().name.lowercase()}")
    }

    /** The shown state machine as the file on disk has it now (edits waiting in an open JSON document written first). */
    private fun currentStateMachine(): StateMachine? {
        val id = shownStateMachineId ?: return null
        StateMachineDocuments.getInstance(project).flush(file)
        return runCatching { DotLottieArchive.read(file.contentsToByteArray()).stateMachineText(id) }.getOrNull()?.let(StateMachine::parse)
    }

    /** Changes the shown state machine in the `.lottie` file as one undoable command; the preview reloads and restarts it. */
    private fun editStateMachine(command: String, change: (StateMachine) -> StateMachine) {
        val id = shownStateMachineId ?: return
        val machine = currentStateMachine() ?: return
        val changed = change(machine)
        DotLottieFiles.replaceEntries(project, file, mapOf(StateMachineVirtualFile.entryPath(id) to changed.toJson().toByteArray(Charsets.UTF_8)), command)
    }

    private inner class DetailsToggleAction : DumbAwareToggleAction(LottieBundle.message("action.details"), null, AllIcons.Actions.PreviewDetails) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = detailsVisible
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            detailsVisible = state
            PropertiesComponent.getInstance().setValue(DETAILS_VISIBLE_KEY, state, true)
            splitter.secondComponent = sideTabs.takeIf { state }
            updateCodeActive()
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
        private const val CODE_VISIBLE_KEY = "cz.xprees.lottie.code.visible"
        private const val TIMELINE_LAYERS_KEY = "cz.xprees.lottie.timeline.layers"
        private const val LOCAL_SEEK_GRACE_MS = 400L
        private const val DUPLICATE_KEY_MS = 250L
    }
}

private val Background.cssName get() = name.lowercase()
