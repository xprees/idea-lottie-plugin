package cz.xprees.lottie.statemachine

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.xprees.lottie.LottieBundle
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * The state machine of a `.lottie` preview: its current state and status, a control for every input (a checkbox, a number or text
 * field, or *Fire* for events) that drives the running machine, and a log of everything it does (transitions, input changes, events
 * it sends to the host app). Inputs can also be added, edited and deleted here, and the whole file opened as JSON.
 *
 * Without a running machine the inputs show their initial values from the file, read-only.
 */
class StateMachinePanel(private val callbacks: Callbacks) : JPanel(BorderLayout()) {

    interface Callbacks {
        fun setInput(name: String, type: InputType, value: Any?)
        fun fire(name: String)
        fun reset(name: String)
        /** Starts the shown state machine again (or for the first time). */
        fun restart()
        fun editJson()
        fun addInput()
        fun editInput(input: StateMachineInput)
        fun deleteInput(input: StateMachineInput)
    }

    /** A running machine as the player reports it. */
    data class Snapshot(val id: String?, val running: Boolean, val status: String, val state: String, val values: Map<String, Any?>) {
        companion object {
            @Suppress("UNCHECKED_CAST")
            fun of(message: Map<String, Any?>) = Snapshot(
                id = message["id"] as? String,
                running = message["running"] == true,
                status = message["status"] as? String ?: "",
                state = message["state"] as? String ?: "",
                values = (message["inputs"] as? List<Map<String, Any?>>).orEmpty().associate { (it["name"] as? String ?: "") to it["value"] },
            )
        }
    }

    private class LogEntry(val time: String, val kind: String, val detail: String, val important: Boolean, val error: Boolean)

    private var machineId: String? = null
    private var machine: StateMachine? = null
    private var snapshot: Snapshot? = null

    /** The input rows by name (declared before init, which builds them). */
    private val rows = LinkedHashMap<String, InputRow>()

    private val title = SimpleColoredComponent()
    private val status = SimpleColoredComponent()
    private val inputsPanel = WidthTrackingPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 8)
    }
    private val log = DefaultListModel<LogEntry>()
    private val logList = JBList(log).apply {
        cellRenderer = LogRenderer()
        emptyText.text = LottieBundle.message("sm.log.empty")
    }

    init {
        val actions = DefaultActionGroup().apply {
            add(Action(LottieBundle.message("sm.action.restart"), AllIcons.Actions.Restart) { callbacks.restart() })
            add(Separator.getInstance())
            add(Action(LottieBundle.message("sm.action.add.input"), AllIcons.General.Add, needsMachine = true) { callbacks.addInput() })
            add(Action(LottieBundle.message("sm.action.edit.json"), AllIcons.Actions.Edit, needsMachine = true) { callbacks.editJson() })
            add(Separator.getInstance())
            add(Action(LottieBundle.message("sm.action.copy.listener"), AllIcons.Actions.Copy) { copy(Snippets.customEventListener()) })
            add(Action(LottieBundle.message("sm.action.clear.log"), AllIcons.Actions.GC) { log.clear() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("LottieStateMachine", actions, true).apply { targetComponent = this@StateMachinePanel }
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineBottom(JBColor.border())
            add(title.apply { border = JBUI.Borders.empty(4, 8, 0, 8) }, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
            add(status.apply { border = JBUI.Borders.empty(0, 8, 4, 8) }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        val logPanel = JPanel(BorderLayout()).apply {
            add(JBLabel(LottieBundle.message("sm.log.title")).apply {
                border = JBUI.Borders.empty(4, 8)
                foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.NORTH)
            add(JBScrollPane(logList).apply { border = JBUI.Borders.customLineTop(JBColor.border()) }, BorderLayout.CENTER)
        }
        add(JBSplitter(true, "cz.xprees.lottie.sm.proportion", 0.6f).apply {
            firstComponent = JBScrollPane(inputsPanel, JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply { border = JBUI.Borders.empty() }
            secondComponent = logPanel
        }, BorderLayout.CENTER)
        rebuild()
    }

    /** Shows state machine [id] as its file defines it ([definition] null: missing or unreadable). */
    fun showDefinition(id: String?, definition: StateMachine?) {
        machineId = id
        machine = definition
        rebuild()
    }

    fun showSnapshot(snapshot: Snapshot) {
        val inputsChanged = this.snapshot?.running != snapshot.running || this.snapshot?.values?.keys != snapshot.values.keys
        this.snapshot = snapshot
        if (inputsChanged) rebuild() else updateValues()
        updateTitle()
    }

    /** Adds a player event ([message] of type `smEvent`) to the log. */
    fun logEvent(message: Map<String, Any?>) {
        val event = message["event"] as? String ?: return
        val (kind, detail) = when (event) {
            "Start" -> LottieBundle.message("sm.log.start") to ""
            "Stop" -> LottieBundle.message("sm.log.stop") to ""
            "Transition" -> LottieBundle.message("sm.log.transition") to "${(message["from"] as? String).orEmpty().ifEmpty { "∅" }} → ${message["to"]}"
            "StateEntered" -> LottieBundle.message("sm.log.entered") to "${message["state"]}"
            "StateExit" -> LottieBundle.message("sm.log.exit") to "${message["state"]}".takeIf { it.isNotEmpty() }.orEmpty()
            "CustomEvent" -> LottieBundle.message("sm.log.custom") to "${message["name"]}"
            "InputFired" -> LottieBundle.message("sm.log.fired") to "${message["input"]}"
            "Error" -> LottieBundle.message("sm.log.error") to "${message["error"]}"
            else -> if (event.endsWith("InputValueChange")) {
                LottieBundle.message("sm.log.input") to "${message["input"]}: ${format(message["from"])} → ${format(message["to"])}"
            } else {
                event to ""
            }
        }
        if (event == "StateExit" && detail.isEmpty()) return // the exit of the empty state before the first transition
        log.add(0, LogEntry(LocalTime.now().format(TIME), kind, detail, event == "CustomEvent", event == "Error"))
        while (log.size() > MAX_LOG) log.remove(log.size() - 1)
    }

    fun clearLog() = log.clear()

    private fun updateTitle() {
        title.clear()
        status.clear()
        val id = machineId
        if (id == null) {
            title.append(LottieBundle.message("sm.none"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return
        }
        title.append(id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        val s = snapshot
        if (s != null && s.running && s.id == id) {
            status.append(LottieBundle.message("sm.state") + " ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            status.append(s.state.ifEmpty { "–" }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            status.append("  · ${s.status}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        } else {
            status.append(LottieBundle.message("sm.not.running"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    /** Lays its rows out at the viewport's width, so that text fields shrink instead of scrolling sideways. */
    private class WidthTrackingPanel : JPanel(), javax.swing.Scrollable {
        override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private val running get() = snapshot?.let { it.running && it.id == machineId } == true

    // ---- inputs ---------------------------------------------------------------------------------------------------------

    private fun rebuild() {
        inputsPanel.removeAll()
        rows.clear()
        val inputs = machine?.inputs.orEmpty()
        when {
            machineId == null -> inputsPanel.add(hint(LottieBundle.message("sm.hint.none")))
            machine == null -> inputsPanel.add(hint(LottieBundle.message("sm.hint.unreadable")))
            inputs.isEmpty() -> inputsPanel.add(hint(LottieBundle.message("sm.hint.no.inputs")))
            else -> {
                if (!running) inputsPanel.add(hint(LottieBundle.message("sm.hint.not.running")))
                for (input in inputs) {
                    val row = InputRow(input)
                    rows[input.name] = row
                    inputsPanel.add(row)
                }
            }
        }
        inputsPanel.add(Box.createVerticalGlue())
        updateValues()
        updateTitle()
        inputsPanel.revalidate()
        inputsPanel.repaint()
    }

    private fun updateValues() {
        val live = running
        for ((name, row) in rows) {
            val value = if (live && snapshot!!.values.containsKey(name)) snapshot!!.values[name] else row.input.value
            row.show(value, live)
        }
    }

    private fun hint(text: String) = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(4, 0)
        alignmentX = LEFT_ALIGNMENT
    }

    /** One input: name and type, a control for its value, reset, copy code, edit and delete. */
    private inner class InputRow(val input: StateMachineInput) : JPanel(BorderLayout(0, JBUI.scale(2))) {
        private var updating = false
        private val checkBox = JBCheckBox()
        private val field = JBTextField(6)
        private val fire = JButton(LottieBundle.message("sm.fire"))
        private val control: JComponent = when (input.type) {
            InputType.BOOLEAN -> checkBox.apply {
                addActionListener { if (!updating) callbacks.setInput(input.name, input.type, isSelected) }
            }
            InputType.EVENT -> fire.apply { addActionListener { callbacks.fire(input.name) } }
            else -> field.apply {
                addActionListener { commit() }
                addFocusListener(object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) = commit()
                })
            }
        }

        init {
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0)
            val name = SimpleColoredComponent().apply {
                append(input.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${input.type.json}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            // two lines, as the panel is narrow: name, type and buttons, then the value at full width
            add(JPanel(BorderLayout()).apply {
                add(name, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
                    if (input.type.hasValue) add(InplaceButton(LottieBundle.message("sm.reset"), AllIcons.General.Reset) { callbacks.reset(input.name) })
                    add(InplaceButton(LottieBundle.message("sm.copy.code"), AllIcons.Actions.Copy) { copy(Snippets.forInput(input, currentValue())) })
                    add(InplaceButton(LottieBundle.message("sm.edit.input"), AllIcons.Actions.Edit) { callbacks.editInput(input) })
                    add(InplaceButton(LottieBundle.message("sm.delete.input"), AllIcons.General.Remove) { callbacks.deleteInput(input) })
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(if (control === field) field else JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(control) }, BorderLayout.CENTER)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        private var shown: Any? = null

        fun show(value: Any?, live: Boolean) {
            updating = true
            shown = value
            when (input.type) {
                InputType.BOOLEAN -> checkBox.isSelected = value == true
                InputType.EVENT -> {}
                else -> if (!field.hasFocus()) field.text = format(value)
            }
            control.isEnabled = live
            updating = false
        }

        private fun currentValue(): Any? = when (input.type) {
            InputType.BOOLEAN -> checkBox.isSelected
            InputType.EVENT -> null
            InputType.NUMERIC -> field.text.trim().toDoubleOrNull() ?: shown
            InputType.STRING -> field.text
        }

        private fun commit() {
            if (updating || !running) return
            val text = field.text
            when (input.type) {
                InputType.NUMERIC -> {
                    val number = text.trim().toDoubleOrNull()
                    if (number == null) {
                        field.text = format(shown)
                        return
                    }
                    if (number != (shown as? Number)?.toDouble()) callbacks.setInput(input.name, input.type, number)
                }
                InputType.STRING -> if (text != shown) callbacks.setInput(input.name, input.type, text)
                else -> {}
            }
        }
    }

    private inner class Action(text: String, icon: javax.swing.Icon, private val needsMachine: Boolean = false, private val run: () -> Unit) :
        DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = machineId != null && (!needsMachine || machine != null)
        }

        override fun actionPerformed(e: AnActionEvent) = run()
    }

    private class LogRenderer : ListCellRenderer<LogEntry> {
        private val component = SimpleColoredComponent()
        override fun getListCellRendererComponent(list: JList<out LogEntry>, value: LogEntry, index: Int, selected: Boolean, focused: Boolean): java.awt.Component {
            component.clear()
            component.background = if (selected) UIUtil.getListSelectionBackground(focused) else list.background
            component.isOpaque = true
            component.append(value.time + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val kindAttributes = when {
                value.error -> SimpleTextAttributes.ERROR_ATTRIBUTES
                value.important -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            component.append(value.kind, kindAttributes)
            if (value.detail.isNotEmpty()) component.append("  " + value.detail, if (value.important) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            return component
        }
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    companion object {
        private const val MAX_LOG = 500
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        internal fun format(value: Any?): String = when (value) {
            null -> ""
            is Double -> if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
            is Number -> value.toString()
            else -> value.toString()
        }
    }
}

/** Code for driving a state machine from a web page with `@lottiefiles/dotlottie-web` (the player the preview runs). */
object Snippets {
    fun forInput(input: StateMachineInput, value: Any?): String = when (input.type) {
        InputType.BOOLEAN -> "dotLottie.stateMachineSetBooleanInput(${js(input.name)}, ${value == true});"
        InputType.NUMERIC -> "dotLottie.stateMachineSetNumericInput(${js(input.name)}, ${StateMachinePanel.format(value ?: 0)});"
        InputType.STRING -> "dotLottie.stateMachineSetStringInput(${js(input.name)}, ${js(value?.toString() ?: "")});"
        InputType.EVENT -> "dotLottie.stateMachineFireEvent(${js(input.name)});"
    }

    fun customEventListener(): String =
        "dotLottie.addEventListener('stateMachineCustomEvent', (event) => {\n  console.log('State machine event:', event.eventName);\n});"

    /** A single-quoted JavaScript string literal. */
    internal fun js(text: String): String = buildString {
        append('\'')
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('\'')
    }
}
