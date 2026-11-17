package cz.xprees.lottie.statemachine

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import cz.xprees.lottie.LottieBundle
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Adds an input to a state machine, or edits one ([existing]): its name, type (fixed when editing) and initial value. Names must be
 * valid and not used by another input ([taken]).
 */
class StateMachineInputDialog(
    project: Project,
    private val existing: StateMachineInput?,
    private val taken: Set<String>,
) : DialogWrapper(project) {

    private val nameField = JBTextField(existing?.name ?: "", 20)
    private val typeBox = JComboBox(DefaultComboBoxModel(InputType.entries.toTypedArray())).apply {
        renderer = textListCellRenderer<InputType?> { it?.json }
        selectedItem = existing?.type ?: InputType.BOOLEAN
        isEnabled = existing == null
    }
    private val valueField = JBTextField(StateMachinePanel.format(existing?.value), 20)
    private val valueBox = JBCheckBox(LottieBundle.message("sm.dialog.true"), existing?.value == true)

    private val type get() = typeBox.selectedItem as InputType

    init {
        title = LottieBundle.message(if (existing == null) "sm.dialog.add.title" else "sm.dialog.edit.title")
        typeBox.addActionListener { updateValueControl() }
        init()
        updateValueControl()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(LottieBundle.message("sm.dialog.name")) { cell(nameField).align(AlignX.FILL).focused() }
        row(LottieBundle.message("sm.dialog.type")) { cell(typeBox) }
        row(LottieBundle.message("sm.dialog.value")) {
            cell(valueField).align(AlignX.FILL)
            cell(valueBox)
        }
        if (existing != null) row { comment(LottieBundle.message("sm.dialog.rename.comment")) }
    }

    private fun updateValueControl() {
        valueField.isVisible = type == InputType.NUMERIC || type == InputType.STRING
        valueBox.isVisible = type == InputType.BOOLEAN
        valueField.isEnabled = type.hasValue
        if (type == InputType.NUMERIC && valueField.text.isBlank()) valueField.text = "0"
    }

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text
        return when {
            !StateMachine.isValidInputName(name) -> ValidationInfo(LottieBundle.message("sm.dialog.name.invalid"), nameField)
            name != existing?.name && name in taken -> ValidationInfo(LottieBundle.message("sm.dialog.name.taken", name), nameField)
            type == InputType.NUMERIC && valueField.text.trim().toDoubleOrNull() == null -> ValidationInfo(LottieBundle.message("sm.dialog.value.number"), valueField)
            else -> null
        }
    }

    /** The input as entered. */
    val result: StateMachineInput
        get() = StateMachineInput(
            nameField.text,
            type,
            when (type) {
                InputType.BOOLEAN -> valueBox.isSelected
                InputType.EVENT -> null
                else -> StateMachine.defaultValue(type, valueField.text)
            },
        )
}
