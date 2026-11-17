package cz.xprees.lottie.preview

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.palette.ColorUsage
import cz.xprees.lottie.palette.PaletteEntry
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * The colors of the animation as a row of swatches above the preview. A swatch's tooltip lists its uses; clicking it calls
 * [onNavigate] with the next use (cycling), so in a JSON file the caret walks through every place the color is defined.
 */
internal class PaletteStrip(private val onNavigate: ((ColorUsage) -> Unit)?) : JPanel(BorderLayout()) {

    private val swatches = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3))).apply { isOpaque = false }
    private val title = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(0, 8, 0, 4)
    }

    init {
        border = JBUI.Borders.customLineBottom(JBColor.border())
        add(title, BorderLayout.WEST)
        add(JBScrollPane(swatches, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }, BorderLayout.CENTER)
    }

    fun setPalette(entries: List<PaletteEntry>) {
        swatches.removeAll()
        title.text = LottieBundle.message("palette.title", entries.size)
        for (entry in entries) swatches.add(Swatch(entry))
        if (entries.isEmpty()) swatches.add(JBLabel(LottieBundle.message("palette.empty")).apply { foreground = UIUtil.getContextHelpForeground() })
        revalidate()
        repaint()
    }

    private inner class Swatch(private val entry: PaletteEntry) : JComponent() {
        private var next = 0
        private val color = entry.color.let { Color(it.red, it.green, it.blue, it.alpha) }

        init {
            preferredSize = Dimension(JBUI.scale(20), JBUI.scale(20))
            toolTipText = tooltip()
            if (onNavigate != null) cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.isPopupTrigger || e.button != MouseEvent.BUTTON1) return
                    val navigate = onNavigate ?: return
                    val usage = entry.usages[next % entry.usages.size]
                    next++
                    navigate(usage)
                }

                override fun mousePressed(e: MouseEvent) = popup(e)

                override fun mouseReleased(e: MouseEvent) = popup(e)
            })
        }

        private fun popup(e: MouseEvent) {
            if (!e.isPopupTrigger) return
            val menu = JBPopupMenu()
            menu.add(JBMenuItem(LottieBundle.message("palette.copy.hex", entry.color.hex), AllIcons.Actions.Copy).apply {
                addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(entry.color.hex)) }
            })
            menu.add(JBMenuItem(LottieBundle.message("palette.copy.array", entry.color.toJsonArray())).apply {
                addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(entry.color.toJsonArray())) }
            })
            menu.show(this, e.x, e.y)
        }

        private fun tooltip(): String {
            val uses = entry.usages.groupBy { it.kind }.entries.joinToString("<br>") { (kind, list) -> "${kind.label}: ${list.size}" }
            val owners = entry.usages.mapNotNull { it.owner }.distinct()
            val ownerText = if (owners.isEmpty()) "" else "<br><br>" + owners.take(8).joinToString("<br>") { it.escapeHtml() } +
                (if (owners.size > 8) "<br>…" else "")
            val hint = if (onNavigate != null) "<br><br><i>${LottieBundle.message("palette.click.hint")}</i>" else ""
            return "<html><b>${entry.color.hex}</b> · ${LottieBundle.message("palette.uses", entry.count)}<br>$uses$ownerText$hint</html>"
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(6)
                val w = width - 1
                val h = height - 1
                if (color.alpha < 255) {
                    // checkerboard under translucent colors
                    val clip = java.awt.geom.RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), arc.toFloat(), arc.toFloat())
                    g2.clip(clip)
                    val cell = JBUI.scale(5)
                    for (y in 0..h step cell) for (x in 0..w step cell) {
                        g2.color = if ((x / cell + y / cell) % 2 == 0) Color.WHITE else Color(0xCCCCCC)
                        g2.fillRect(x, y, cell, cell)
                    }
                    g2.clip = null
                }
                g2.color = color
                g2.fillRoundRect(0, 0, w, h, arc, arc)
                g2.color = JBColor.border()
                g2.drawRoundRect(0, 0, w, h, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }
}

private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
