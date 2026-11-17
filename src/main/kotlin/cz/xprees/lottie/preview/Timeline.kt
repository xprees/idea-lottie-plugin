package cz.xprees.lottie.preview

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.xprees.lottie.LottieBundle
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * The animation's timeline under the preview: a frame ruler with the playhead, the markers as named segments and, optionally, one
 * lane per root layer showing its in/out range.
 *
 * Press and drag anywhere on the timeline to scrub (playback pauses while scrubbing and resumes afterwards); hold Alt for
 * fractional frames. With the timeline focused, ←/→ step one frame (Shift: ten), Home/End jump to the ends and Space plays or
 * pauses. The mouse wheel over the ruler steps frames. Double-clicking a marker plays only its segment; clicking a layer (its name
 * or its bar) selects it.
 *
 * With [editableMarkers], the markers lane is always shown: its context menu adds, renames and deletes markers and moves their
 * start or end to the playhead, and dragging a marker's edge changes its range. The callbacks write the change; the timeline only
 * shows the result once [markers] is updated.
 */
internal class Timeline(private val callbacks: Callbacks) {

    interface Callbacks {
        fun seek(frame: Double)
        fun scrubStarted()
        fun scrubEnded()
        fun layerClicked(index: Int)
        fun playMarker(marker: Marker)
        fun addMarker(frame: Double) {}
        fun renameMarker(marker: Marker) {}
        fun deleteMarker(marker: Marker) {}
        fun changeMarker(marker: Marker, start: Double, end: Double) {}
        /** Code that plays [marker], and the name of the platform it is for (null: no copy item). */
        fun markerCode(marker: Marker): Pair<String, String>? = null
    }

    /**
     * A marker in player frames. [name] is what the player knows it by, [label] what is shown, [index] its position in the
     * document's `markers` array when it can be edited.
     */
    data class Marker(val name: String, val label: String, val start: Double, val end: Double, val index: Int? = null)

    /** A root layer and its visible range in player frames. */
    data class Layer(val index: Int, val label: String, val type: String, val start: Double, val end: Double, val hidden: Boolean)

    var lastFrame = 0.0
        set(value) {
            field = value
            refresh()
        }
    var frameRate = 0.0
    var frame = 0.0
        set(value) {
            if (field == value) return
            field = value
            ruler.repaint()
            tracks.repaint()
        }
    var markers: List<Marker> = emptyList()
        set(value) {
            field = value
            refresh()
        }
    var layers: List<Layer> = emptyList()
        set(value) {
            field = value
            refresh()
        }
    var selectedLayer: Int? = null
        set(value) {
            if (field == value) return
            field = value
            tracks.repaint()
            value?.let { index -> layers.indexOfFirst { it.index == index }.takeIf { it >= 0 }?.let { tracks.scrollRectToVisible(Rectangle(0, it * rowHeight, 1, rowHeight)) } }
        }
    var showLayers = false
        set(value) {
            field = value
            refresh()
        }
    var editableMarkers = false
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    private val showMarkersLane get() = markers.isNotEmpty() || editableMarkers

    private val rulerHeight get() = JBUI.scale(22)
    private val rowHeight get() = JBUI.scale(18)
    private val maxVisibleRows = 6
    private val labelWidth get() = if (showMarkersLane || (showLayers && layers.isNotEmpty())) JBUI.scale(120) else JBUI.scale(8)
    private val rightPadding get() = JBUI.scale(10)
    private var hoverX: Int? = null

    private val ruler = RulerView()
    private val tracks = TracksView()
    private val tracksScroll = JBScrollPane(tracks).apply {
        border = JBUI.Borders.customLineTop(JBColor.border())
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(ruler, BorderLayout.NORTH)
        add(tracksScroll, BorderLayout.CENTER)
    }

    private fun refresh() {
        tracksScroll.isVisible = showLayers && layers.isNotEmpty()
        val rows = layers.size.coerceAtMost(maxVisibleRows)
        tracksScroll.preferredSize = Dimension(0, rows * rowHeight + JBUI.scale(2))
        tracks.revalidate()
        component.revalidate()
        component.repaint()
    }

    private fun scale(width: Int) = TimelineScale(labelWidth, (width - labelWidth - rightPadding).coerceAtLeast(1), lastFrame)

    private fun timeText(frame: Double): String {
        val seconds = if (frameRate > 0) String.format(Locale.ROOT, " · %.2f s", frame / frameRate) else ""
        return LottieBundle.message("timeline.frame", formatFrame(frame)) + seconds
    }

    private fun formatFrame(frame: Double) = if (frame == Math.rint(frame)) frame.toLong().toString() else String.format(Locale.ROOT, "%.2f", frame)

    private val playheadColor = JBColor(Color(0x3574F0), Color(0x548AF7))
    private val markerColors = listOf(Color(0x2FA36B), Color(0xE08A00), Color(0x9C5FD6), Color(0xD9435E), Color(0x2B8FD6))

    /** Mouse handling shared by the ruler and the layer lanes. */
    private abstract inner class TimelineView : JComponent() {
        private var scrubbing = false

        init {
            isFocusable = true
            isOpaque = true
            toolTipText = ""
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    if (e.isPopupTrigger) {
                        popup(e)
                        return
                    }
                    if (e.button != MouseEvent.BUTTON1) return
                    if (e.x < labelWidth) {
                        labelPressed(e)
                        return
                    }
                    if (e.clickCount == 2 && doubleClicked(e)) return
                    if (pressed(e)) return
                    scrubbing = true
                    callbacks.scrubStarted()
                    seekTo(e)
                }

                override fun mouseDragged(e: MouseEvent) {
                    hoverX = e.x
                    if (!dragged(e) && scrubbing) seekTo(e)
                    repaintAll()
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        popup(e)
                        return
                    }
                    if (released(e)) return
                    if (!scrubbing) return
                    scrubbing = false
                    callbacks.scrubEnded()
                }

                override fun mouseMoved(e: MouseEvent) {
                    hoverX = e.x.takeIf { it >= labelWidth }
                    cursor = cursorAt(e)
                    repaintAll()
                }

                override fun mouseExited(e: MouseEvent) {
                    hoverX = null
                    repaintAll()
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            // the playback keys (Space, arrows, Home/End) are bound on the whole preview, see PlaybackKeys
        }

        private fun seekTo(e: MouseEvent) = callbacks.seek(scale(width).frame(e.x, exact = e.isAltDown))

        open fun labelPressed(e: MouseEvent) {}

        open fun doubleClicked(e: MouseEvent): Boolean = false

        /** A left press the view handles itself instead of scrubbing. */
        open fun pressed(e: MouseEvent): Boolean = false

        open fun dragged(e: MouseEvent): Boolean = false

        open fun released(e: MouseEvent): Boolean = false

        open fun popup(e: MouseEvent) {}

        open fun cursorAt(e: MouseEvent): Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        private fun repaintAll() {
            ruler.repaint()
            tracks.repaint()
        }

        override fun getToolTipText(event: MouseEvent): String? =
            if (event.x >= labelWidth && lastFrame > 0) timeText(scale(width).frame(event.x, exact = event.isAltDown)) else null

        protected fun repaintTimeline() = repaintAll()

        protected fun Graphics2D.playhead(s: TimelineScale, top: Int, bottom: Int) {
            hoverX?.let { hx ->
                color = JBColor(Color(0, 0, 0, 40), Color(255, 255, 255, 40))
                drawLine(hx, top, hx, bottom)
            }
            val x = s.x(frame)
            color = playheadColor
            stroke = BasicStroke(JBUI.scale(2).toFloat())
            drawLine(x, top, x, bottom)
            stroke = BasicStroke(1f)
        }

        protected fun Graphics2D.setup() {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            font = JBUI.Fonts.smallFont()
        }
    }

    /** The frame ruler with tick labels, the markers lane and the playhead handle. */
    private inner class RulerView : TimelineView() {
        override fun getPreferredSize() = Dimension(JBUI.scale(200), rulerHeight + if (showMarkersLane) rowHeight else 0)

        /** The marker being resized by dragging one of its edges, with its draft range. */
        private var resizing: Marker? = null
        private var resizingEnd = false
        private var draftStart = 0.0
        private var draftEnd = 0.0
        private val edgeSlop get() = JBUI.scale(4)

        private fun markerAt(e: MouseEvent): Marker? {
            if (e.y < rulerHeight) return null
            val s = scale(width)
            return markers.filter { e.x >= s.x(it.start) - edgeSlop && e.x <= maxOf(s.x(it.end), s.x(it.start) + JBUI.scale(8)) + edgeSlop }
                .minByOrNull { it.end - it.start }
        }

        /** An editable marker whose end (true) or start (false) edge is under the mouse. */
        private fun edgeAt(e: MouseEvent): Pair<Marker, Boolean>? {
            if (!editableMarkers || e.y < rulerHeight) return null
            val s = scale(width)
            for (m in markers.filter { it.index != null }) {
                if (kotlin.math.abs(e.x - s.x(m.end)) <= edgeSlop) return m to true
                if (kotlin.math.abs(e.x - s.x(m.start)) <= edgeSlop) return m to false
            }
            return null
        }

        override fun cursorAt(e: MouseEvent): Cursor =
            if (edgeAt(e) != null) Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR) else super.cursorAt(e)

        override fun doubleClicked(e: MouseEvent): Boolean {
            val marker = markerAt(e) ?: return false
            callbacks.playMarker(marker)
            return true
        }

        override fun pressed(e: MouseEvent): Boolean {
            val (marker, end) = edgeAt(e) ?: return false
            resizing = marker
            resizingEnd = end
            draftStart = marker.start
            draftEnd = marker.end
            return true
        }

        override fun dragged(e: MouseEvent): Boolean {
            if (resizing == null) return false
            val f = scale(width).frame(e.x, exact = e.isAltDown)
            if (resizingEnd) draftEnd = f.coerceAtLeast(draftStart) else draftStart = f.coerceAtMost(draftEnd)
            return true
        }

        override fun released(e: MouseEvent): Boolean {
            val marker = resizing ?: return false
            resizing = null
            if (draftStart != marker.start || draftEnd != marker.end) callbacks.changeMarker(marker, draftStart, draftEnd)
            repaintTimeline()
            return true
        }

        override fun popup(e: MouseEvent) {
            val menu = JBPopupMenu()
            fun item(text: String, run: () -> Unit) = menu.add(JBMenuItem(text).apply { addActionListener { run() } })
            val atMouse = if (e.x >= labelWidth) scale(width).frame(e.x) else Math.round(frame).toDouble()
            val playhead = Math.round(frame).toDouble()
            val marker = markerAt(e)
            if (marker != null) {
                item(LottieBundle.message("timeline.marker.play", marker.label)) { callbacks.playMarker(marker) }
                item(LottieBundle.message("timeline.marker.copy", marker.label)) {
                    CopyPasteManager.getInstance().setContents(StringSelection(marker.label))
                }
                callbacks.markerCode(marker)?.let { (code, platform) ->
                    item(LottieBundle.message("timeline.marker.copy.code", marker.label, platform)) {
                        CopyPasteManager.getInstance().setContents(StringSelection(code))
                    }
                }
                if (marker.index != null && editableMarkers) {
                    item(LottieBundle.message("timeline.marker.rename")) { callbacks.renameMarker(marker) }
                    if (playhead <= marker.end) {
                        item(LottieBundle.message("timeline.marker.start.here", formatFrame(playhead))) { callbacks.changeMarker(marker, playhead, marker.end) }
                    }
                    if (playhead >= marker.start) {
                        item(LottieBundle.message("timeline.marker.end.here", formatFrame(playhead))) { callbacks.changeMarker(marker, marker.start, playhead) }
                    }
                    menu.addSeparator()
                    item(LottieBundle.message("timeline.marker.delete", marker.label)) { callbacks.deleteMarker(marker) }
                }
            }
            if (editableMarkers) {
                if (menu.componentCount > 0) menu.addSeparator()
                item(LottieBundle.message("timeline.marker.add.at", formatFrame(atMouse))) { callbacks.addMarker(atMouse) }
                if (atMouse != playhead) item(LottieBundle.message("timeline.marker.add.playhead", formatFrame(playhead))) { callbacks.addMarker(playhead) }
            }
            if (menu.componentCount > 0) menu.show(this, e.x, e.y)
        }

        /** [m] with the draft range while its edge is being dragged. */
        private fun shown(m: Marker) = if (resizing != null && resizing == m) m.copy(start = draftStart, end = draftEnd) else m

        init {
            addMouseWheelListener { e: MouseWheelEvent ->
                val delta = if (e.isShiftDown) 10.0 else 1.0
                callbacks.seek((Math.round(frame) + e.wheelRotation.coerceIn(-1, 1) * delta).coerceIn(0.0, lastFrame.coerceAtLeast(0.0)))
            }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setup()
                g2.color = UIUtil.getPanelBackground()
                g2.fillRect(0, 0, width, height)
                val s = scale(width)
                val fm = g2.fontMetrics
                val muted = UIUtil.getContextHelpForeground()

                // ticks: labelled every `step` frames, small ticks in between when there is room
                if (lastFrame > 0) {
                    val step = s.tickStep(JBUI.scale(44))
                    val minor = when {
                        step >= 10 && s.tickStep(JBUI.scale(8)) <= step / 5 -> step / 5
                        step >= 2 && s.tickStep(JBUI.scale(8)) <= step / 2 -> step / 2
                        else -> 0
                    }
                    g2.color = JBColor.border()
                    if (minor > 0) {
                        var f = 0
                        while (f <= lastFrame) {
                            val x = s.x(f.toDouble())
                            g2.drawLine(x, rulerHeight - JBUI.scale(4), x, rulerHeight - 1)
                            f += minor
                        }
                    }
                    var f = 0
                    while (f <= lastFrame) {
                        val x = s.x(f.toDouble())
                        g2.color = JBColor.border()
                        g2.drawLine(x, rulerHeight - JBUI.scale(8), x, rulerHeight - 1)
                        g2.color = muted
                        val text = f.toString()
                        val tx = (x - fm.stringWidth(text) / 2).coerceIn(s.left - JBUI.scale(2), width - fm.stringWidth(text) - 1)
                        g2.drawString(text, tx, fm.ascent + JBUI.scale(2))
                        f += step
                    }
                }
                g2.color = JBColor.border()
                g2.drawLine(0, rulerHeight - 1, width, rulerHeight - 1)

                // markers lane
                if (showMarkersLane) {
                    val top = rulerHeight
                    g2.color = muted
                    g2.drawString(LottieBundle.message("timeline.markers"), JBUI.scale(8), top + (rowHeight + fm.ascent - fm.descent) / 2)
                    if (markers.isEmpty()) {
                        g2.drawString(LottieBundle.message("timeline.markers.empty"), s.left + JBUI.scale(4), top + (rowHeight + fm.ascent - fm.descent) / 2)
                    }
                    val shownMarkers = markers.map(::shown)
                    val starts = shownMarkers.map { s.x(it.start) }.sorted()
                    shownMarkers.forEachIndexed { i, m ->
                        val x1 = s.x(m.start)
                        val x2 = s.x(m.end)
                        val base = markerColors[i % markerColors.size]
                        val baseline = top + (rowHeight + fm.ascent - fm.descent) / 2
                        val clip = g2.clip
                        if (x2 - x1 < JBUI.scale(8)) {
                            // a point marker (an event at one frame): a flag, labelled up to the next marker
                            g2.color = base
                            g2.fillRect(x1, top + JBUI.scale(1), JBUI.scale(2), rowHeight - JBUI.scale(2))
                            val flag = java.awt.Polygon(
                                intArrayOf(x1 + JBUI.scale(2), x1 + JBUI.scale(8), x1 + JBUI.scale(2)),
                                intArrayOf(top + JBUI.scale(2), top + JBUI.scale(5), top + JBUI.scale(8)),
                                3,
                            )
                            g2.fillPolygon(flag)
                            val limit = starts.firstOrNull { it > x1 } ?: width
                            g2.clipRect(x1, top, (limit - x1 - JBUI.scale(2)).coerceAtLeast(0), rowHeight)
                            g2.color = UIUtil.getLabelForeground()
                            g2.drawString(m.label, x1 + JBUI.scale(10), baseline)
                        } else {
                            g2.color = Color(base.red, base.green, base.blue, 70)
                            g2.fillRoundRect(x1, top + JBUI.scale(2), x2 - x1, rowHeight - JBUI.scale(4), JBUI.scale(6), JBUI.scale(6))
                            g2.color = base
                            g2.fillRect(x1, top + JBUI.scale(2), JBUI.scale(2), rowHeight - JBUI.scale(4))
                            g2.clipRect(x1, top, x2 - x1, rowHeight)
                            g2.color = UIUtil.getLabelForeground()
                            g2.drawString(m.label, x1 + JBUI.scale(5), baseline)
                        }
                        g2.clip = clip
                    }
                }

                g2.playhead(s, 0, height)
                // playhead handle with the frame number
                if (lastFrame > 0) {
                    val x = s.x(frame)
                    val text = formatFrame(frame)
                    val w = fm.stringWidth(text) + JBUI.scale(8)
                    val hx = (x - w / 2).coerceIn(0, (width - w).coerceAtLeast(0))
                    g2.color = playheadColor
                    g2.fillRoundRect(hx, JBUI.scale(1), w, fm.height, JBUI.scale(6), JBUI.scale(6))
                    g2.color = Color.WHITE
                    g2.drawString(text, hx + JBUI.scale(4), JBUI.scale(1) + fm.ascent)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /** One lane per root layer: its name in the label column and its in/out range as a bar. */
    private inner class TracksView : TimelineView(), Scrollable {
        override fun getPreferredSize() = Dimension(JBUI.scale(200), layers.size * rowHeight)

        private fun layerAt(y: Int) = layers.getOrNull(y / rowHeight)

        override fun labelPressed(e: MouseEvent) {
            layerAt(e.y)?.let { callbacks.layerClicked(it.index) }
        }

        override fun doubleClicked(e: MouseEvent): Boolean {
            val layer = layerAt(e.y) ?: return false
            callbacks.layerClicked(layer.index)
            return true
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val layer = layerAt(event.y) ?: return null
            if (event.x < labelWidth) {
                return "<html><b>${layer.label.escapeHtml()}</b> · ${layer.type}<br>${formatFrame(layer.start)}–${formatFrame(layer.end)}</html>"
            }
            return super.getToolTipText(event)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setup()
                g2.color = UIUtil.getPanelBackground()
                g2.fillRect(0, 0, width, height)
                val s = scale(width)
                val fm = g2.fontMetrics
                layers.forEachIndexed { row, layer ->
                    val top = row * rowHeight
                    if (layer.index == selectedLayer) {
                        g2.color = UIUtil.getListSelectionBackground(false)
                        g2.fillRect(0, top, width, rowHeight)
                    }
                    g2.color = if (layer.hidden) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
                    val clip = g2.clip
                    g2.clipRect(JBUI.scale(8), top, labelWidth - JBUI.scale(12), rowHeight)
                    g2.drawString(layer.label, JBUI.scale(8), top + (rowHeight + fm.ascent - fm.descent) / 2)
                    g2.clip = clip
                    val x1 = s.x(layer.start)
                    val x2 = s.x(layer.end).coerceAtLeast(x1 + JBUI.scale(2))
                    val bar = JBColor(Color(0x9AA7B0), Color(0x5E6670))
                    g2.color = if (layer.hidden) Color(bar.red, bar.green, bar.blue, 70) else if (layer.index == selectedLayer) playheadColor else bar
                    g2.fillRoundRect(x1, top + JBUI.scale(4), x2 - x1, rowHeight - JBUI.scale(8), JBUI.scale(6), JBUI.scale(6))
                }
                g2.playhead(s, 0, height)
            } finally {
                g2.dispose()
            }
        }

        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = rowHeight
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
}

private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
