package cz.xprees.lottie

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieFiles
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.lang.LottieFiles
import cz.xprees.lottie.statemachine.StateMachine
import cz.xprees.lottie.statemachine.StateMachineDocuments
import cz.xprees.lottie.statemachine.StateMachineReferencesInspection
import org.assertj.core.api.Assertions.assertThat
import java.io.File

class StateMachinePlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // the bundled schemas are read from the plugin jar in the test sandbox
        val schema = LottieFiles::class.java.getResource("/schemas/lottie.schema.json")!!
        VfsRootAccess.allowRootAccess(testRootDisposable, File(schema.path.substringAfter("file:").substringBefore("!/")).parent)
    }

    private fun archive(bytes: ByteArray = TestData.bytes("interactive.lottie"), name: String = "interactive.lottie"): VirtualFile {
        val file = myFixture.tempDirFixture.createFile(name)
        WriteAction.runAndWait<Exception> { file.setBinaryContent(bytes) }
        return file
    }

    private fun stateMachineText(file: VirtualFile) = DotLottieArchive.read(file.contentsToByteArray()).stateMachineText("toggle")!!

    private fun dispatch() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    fun `test archive edits can be undone and redone`() {
        val file = archive()
        // the test editor manager only opens text editors: create the .lottie preview editor directly (no JCEF in tests)
        val editor = cz.xprees.lottie.preview.LottiePreviewFileEditor(project, file, null)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, editor)
        val text = stateMachineText(file)
        val machine = checkNotNull(StateMachine.parse(text)) { "not a state machine: ${text.take(200)}" }
        DotLottieFiles.replaceEntries(project, file, mapOf("s/toggle.json" to machine.renameInput("isOn", "enabled").toJson().toByteArray()), "Rename")
        assertThat(stateMachineText(file)).contains("\"enabled\"").doesNotContain("\"isOn\"")
        // the animation is untouched
        assertThat(DotLottieArchive.read(file.contentsToByteArray()).animationText("sample")).isEqualTo(DotLottieArchive.read(TestData.bytes("interactive.lottie")).animationText("sample"))

        val undo = UndoManager.getInstance(project)
        assertThat(undo.isUndoAvailable(editor)).isTrue()
        undo.undo(editor)
        assertThat(stateMachineText(file)).contains("\"isOn\"")
        undo.redo(editor)
        assertThat(stateMachineText(file)).contains("\"enabled\"")
    }

    fun `test a state machine opens as JSON and edits are written back`() {
        val file = archive()
        val documents = StateMachineDocuments.getInstance(project)
        val opened = documents.open(file, "toggle")!!
        assertThat(opened.path).endsWith("interactive.lottie!/s/toggle.json")
        val document = documents.document(file, "toggle")!!
        assertThat(document.text).contains("\"initial\": \"off\"")

        WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("\"initial\": \"off\"", "\"initial\": \"on\"")) }
        documents.flush(file)
        assertThat(StateMachine.parse(stateMachineText(file))!!.initial).isEqualTo("on")

        // invalid JSON is not written
        WriteCommandAction.runWriteCommandAction(project) { document.setText("{ broken") }
        documents.flush(file)
        assertThat(StateMachine.parse(stateMachineText(file))!!.initial).isEqualTo("on")

        // a change of the archive from elsewhere reaches the document once its own edits are written
        WriteCommandAction.runWriteCommandAction(project) { document.setText(stateMachineText(file)) }
        documents.flush(file)
        val renamed = StateMachine.parse(stateMachineText(file))!!.renameInput("clicks", "taps")
        DotLottieFiles.replaceEntries(project, file, mapOf("s/toggle.json" to renamed.toJson().toByteArray()), "Rename")
        dispatch()
        assertThat(document.text).contains("\"taps\"").doesNotContain("\"clicks\"")
        FileEditorManager.getInstance(project).closeFile(opened)
    }

    fun `test the state machine schema applies to state machine documents`() {
        val opened = StateMachineDocuments.getInstance(project).open(archive(), "toggle")!!
        val schemas = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(opened).map { it.name }
        assertThat(schemas).contains("dotlottie-state-machine.schema.json").doesNotContain("lottie.schema.json")
        FileEditorManager.getInstance(project).closeFile(opened)
    }

    fun `test broken references are reported`() {
        val broken = DotLottieArchive.read(TestData.bytes("interactive.lottie")).stateMachineText("toggle")!!.replace("\"toState\": \"on\"", "\"toState\": \"onn\"").replace("\"segment\": \"outro\"", "\"segment\": \"ending\"")
        val bytes = DotLottieWriter.replace(TestData.bytes("interactive.lottie"), mapOf("s/toggle.json" to broken.toByteArray()))
        val file = archive(bytes, "broken.lottie")
        val opened = StateMachineDocuments.getInstance(project).open(file, "toggle")!!
        myFixture.openFileInEditor(opened)
        myFixture.enableInspections(StateMachineReferencesInspection::class.java)
        val messages = myFixture.doHighlighting().mapNotNull { it.description }
        assertThat(messages).contains("Unknown state 'onn'", "Unknown marker 'ending' in animation 'sample'")
        FileEditorManager.getInstance(project).closeFile(opened)
    }

    fun `test the panel shows inputs, live values and events`() {
        val calls = mutableListOf<String>()
        val panel = cz.xprees.lottie.statemachine.StateMachinePanel(object : cz.xprees.lottie.statemachine.StateMachinePanel.Callbacks {
            override fun setInput(name: String, type: cz.xprees.lottie.statemachine.InputType, value: Any?) { calls += "set $name=$value" }
            override fun fire(name: String) { calls += "fire $name" }
            override fun reset(name: String) { calls += "reset $name" }
            override fun restart() { calls += "restart" }
            override fun editJson() {}
            override fun addInput() {}
            override fun editInput(input: cz.xprees.lottie.statemachine.StateMachineInput) {}
            override fun deleteInput(input: cz.xprees.lottie.statemachine.StateMachineInput) {}
        })
        val machine = StateMachine.parse(DotLottieArchive.read(TestData.bytes("interactive.lottie")).stateMachineText("toggle")!!)!!
        panel.showDefinition("toggle", machine)
        fun components(c: java.awt.Container): List<java.awt.Component> = c.components.flatMap { listOf(it) + ((it as? java.awt.Container)?.let(::components) ?: emptyList()) }
        fun checkBox() = components(panel).filterIsInstance<com.intellij.ui.components.JBCheckBox>().single()
        // not running: initial values, read-only
        assertThat(checkBox().isEnabled).isFalse()

        panel.showSnapshot(cz.xprees.lottie.statemachine.StateMachinePanel.Snapshot("toggle", true, "Running", "off", mapOf("isOn" to true, "clicks" to 3.0, "label" to "x", "reset" to null)))
        // starting the machine rebuilds the rows
        val checkBox = checkBox()
        val fire = components(panel).filterIsInstance<javax.swing.JButton>().single { it.text == "Fire" }
        assertThat(checkBox.isEnabled).isTrue()
        assertThat(checkBox.isSelected).isTrue()
        assertThat(components(panel).filterIsInstance<com.intellij.ui.components.JBTextField>().map { it.text }).containsExactly("3", "x")
        checkBox.doClick()
        fire.doClick()
        assertThat(calls).containsExactly("set isOn=false", "fire reset")

        panel.logEvent(mapOf("type" to "smEvent", "event" to "Transition", "from" to "off", "to" to "on"))
        panel.logEvent(mapOf("type" to "smEvent", "event" to "CustomEvent", "name" to "switched-on"))
        val log = components(panel).filterIsInstance<com.intellij.ui.components.JBList<*>>().single()
        assertThat(log.model.size).isEqualTo(2)
    }
}
