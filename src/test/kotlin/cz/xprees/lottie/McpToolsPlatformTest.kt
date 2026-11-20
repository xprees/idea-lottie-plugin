package cz.xprees.lottie

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.dotlottie.DotLottieWriter
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.json.str
import cz.xprees.lottie.mcp.LottieMcpTools
import cz.xprees.lottie.mcp.ToolError
import cz.xprees.lottie.model.LottieModel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class McpToolsPlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // the bundled schemas are read from the plugin jar in the test sandbox
        val schema = cz.xprees.lottie.lang.LottieFiles::class.java.getResource("/schemas/lottie.schema.json")!!
        VfsRootAccess.allowRootAccess(testRootDisposable, java.io.File(schema.path.substringAfter("file:").substringBefore("!/")).parent)
    }

    private val tools get() = LottieMcpTools(project, myFixture.tempDirFixture.getFile("")!!)

    private fun file(name: String, bytes: ByteArray = TestData.bytes(name)): VirtualFile {
        val file = myFixture.tempDirFixture.createFile(name)
        WriteAction.runAndWait<Exception> { file.setBinaryContent(bytes) }
        return file
    }

    private fun json(text: String): Map<String, Any?> = MiniJson.parse(text).obj()!!

    @Suppress("UNCHECKED_CAST")
    private fun list(value: Any?): List<Map<String, Any?>> = value.arr()!!.map { it as Map<String, Any?> }

    fun `test describe a Lottie JSON file`() {
        file("sample.json")
        val result = json(tools.describeLottie(tools.resolve("sample.json")))
        assertThat(result["path"]).isEqualTo("sample.json")
        assertThat(result["frameRate"]).isEqualTo(30L)
        assertThat(result["frames"]).isEqualTo(60L)
        assertThat(result["durationSeconds"]).isEqualTo(2L)
        assertThat(list(result["markers"]).map { it["name"] }).containsExactly("intro", "outro")
        assertThat(result["layerCount"]).isEqualTo(5L)
        assertThat(list(result["layers"])).hasSize(5)
        assertThat(list(result["palette"])).isNotEmpty.allSatisfy { assertThat(it["color"].str()).startsWith("#") }

        val limited = json(tools.describeLottie(tools.resolve("sample.json"), limit = 2))
        assertThat(list(limited["layers"])).hasSize(2)
        assertThat(limited["layersTruncated"].str()).contains("2 of 5")
    }

    fun `test describe sees unsaved editor changes`() {
        val file = file("sample.json")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteAction.runAndWait<Exception> { document.setText(document.text.replace("\"fr\": 30", "\"fr\": 60").replace("\"fr\":30", "\"fr\":60")) }
        assertThat(json(tools.describeLottie(file))["frameRate"]).isEqualTo(60L)
    }

    fun `test describe a dotLottie file`() {
        file("interactive.lottie")
        val result = json(tools.describeDotLottie(tools.resolve("interactive.lottie")))
        assertThat(result["dotLottieVersion"]).isEqualTo(2L)
        assertThat(list(result["animations"]).map { it["id"] }).containsExactly("sample")
        assertThat(list(result["stateMachines"]).map { it["id"] }).containsExactly("toggle")
        assertThat(list(result["files"]).map { it["path"] }).contains("manifest.json", "s/toggle.json")
        // an animation inside the archive
        assertThat(list(json(tools.describeLottie(tools.resolve("interactive.lottie")))["markers"]).map { it["name"] }).contains("intro", "outro")

        file("v1.lottie")
        val v1 = json(tools.describeDotLottie(tools.resolve("v1.lottie")))
        assertThat(v1["dotLottieVersion"]).isEqualTo(1L)
        assertThat(v1["note"].str()).contains("dotlottie_convert_to_v2")
    }

    fun `test describe a state machine with its code`() {
        val file = file("interactive.lottie")
        val result = json(tools.describeStateMachine(file, platform = "dotlottie-web"))
        assertThat(result["stateMachine"]).isEqualTo("toggle")
        assertThat(result["initial"]).isEqualTo("off")
        assertThat(list(result["states"]).map { it["name"] }).contains("off", "on")
        assertThat(list(result["inputs"]).map { it["name"] }).containsExactly("isOn", "clicks", "label", "reset")
        assertThat(result["customEvents"].arr()).contains("switched-on")
        assertThat(result["problems"].arr()).isEmpty()
        val code = result["code"].obj()!!
        assertThat(code["platform"]).isEqualTo("@lottiefiles/dotlottie-web")
        assertThat(code["inputs"].obj()!!["isOn"].str()).contains("isOn")
        assertThat(code["start"].str()).contains("toggle")
    }

    fun `test check reports broken references`() {
        file("interactive.lottie")
        assertThat(json(tools.checkStateMachines(tools.resolve("interactive.lottie")))["ok"]).isEqualTo(true)

        val broken = DotLottieArchive.read(TestData.bytes("interactive.lottie")).stateMachineText("toggle")!!.replace("\"toState\": \"on\"", "\"toState\": \"onn\"")
        file("broken.lottie", DotLottieWriter.replace(TestData.bytes("interactive.lottie"), mapOf("s/toggle.json" to broken.toByteArray())))
        val result = json(tools.checkStateMachines(tools.resolve("broken.lottie"), "toggle"))
        assertThat(result["ok"]).isEqualTo(false)
        val problems = list(result["stateMachines"].obj()!!["toggle"])
        assertThat(problems.map { it["message"] }).contains("Unknown state 'onn'")
        assertThat(problems.first()["at"].str()).startsWith("states[")
    }

    fun `test markers are added and removed in a JSON file, saved and undoable`() {
        val file = file("sample.json")
        val added = json(tools.addMarker(file, "hover", 10.0, 5.0))
        assertThat(list(added["markers"]).map { it["name"] }).containsExactly("intro", "hover", "outro")
        // saved: the file on disk has the marker
        assertThat(LottieModel.read(VfsUtilCore.loadText(file))!!.markers.map { it.name }).contains("hover")
        assertThatThrownBy { tools.addMarker(file, "hover", 20.0) }.isInstanceOf(ToolError::class.java).hasMessageContaining("already has a marker 'hover'")
        assertThat(json(tools.addMarker(file, "late", 100.0))["warning"].str()).contains("outside")

        json(tools.removeMarker(file, "late"))
        val removed = json(tools.removeMarker(file, "outro"))
        assertThat(list(removed["markers"]).map { it["name"] }).containsExactly("intro", "hover")
        assertThatThrownBy { tools.removeMarker(file, "nope") }.hasMessageContaining("No marker 'nope'").hasMessageContaining("intro, hover")

        val editor = FileEditorManager.getInstance(project).openFile(file, false).filterIsInstance<TextEditor>().first()
        UndoManager.getInstance(project).undo(editor)
        assertThat(LottieModel.read(FileDocumentManager.getInstance().getDocument(file)!!.text)!!.markers.map { it.name }).contains("outro")
    }

    fun `test markers are added and removed in a dotLottie file`() {
        val file = file("interactive.lottie")
        val added = json(tools.addMarker(file, "hover", 10.0, 5.0, animation = "sample"))
        assertThat(added["animation"]).isEqualTo("sample")
        assertThat(LottieModel.read(DotLottieArchive.read(file.contentsToByteArray()).animationText("sample")!!)!!.markers.map { it.name }).contains("hover")
        json(tools.removeMarker(file, "hover"))
        assertThat(LottieModel.read(DotLottieArchive.read(file.contentsToByteArray()).animationText("sample")!!)!!.markers.map { it.name }).doesNotContain("hover")
        assertThatThrownBy { tools.addMarker(file, "x", 0.0, animation = "missing") }.hasMessageContaining("No animation 'missing'")
    }

    fun `test state machine inputs are added, renamed and deleted`() {
        val file = file("interactive.lottie")
        val added = json(tools.addInput(file, "hovered", "boolean", "true"))
        assertThat(list(added["inputs"]).last()).isEqualTo(mapOf("name" to "hovered", "type" to "Boolean", "value" to true))
        assertThatThrownBy { tools.addInput(file, "hovered", "Boolean") }.hasMessageContaining("already has an input 'hovered'")
        assertThatThrownBy { tools.addInput(file, "x", "Color") }.hasMessageContaining("Unknown input type 'Color'")
        assertThatThrownBy { tools.addInput(file, "\$x", "Boolean") }.hasMessageContaining("not a valid input name")
        assertThatThrownBy { tools.addInput(file, "n", "Numeric", "many") }.hasMessageContaining("is a number")

        val renamed = json(tools.renameInput(file, "isOn", "enabled", "toggle"))
        assertThat(list(renamed["inputs"]).map { it["name"] }).contains("enabled").doesNotContain("isOn")
        assertThat(renamed["problems"].arr()).isEmpty()
        assertThat(DotLottieArchive.read(file.contentsToByteArray()).stateMachineText("toggle")).doesNotContain("\"isOn\"")

        val deleted = json(tools.deleteInput(file, "enabled"))
        assertThat(list(deleted["inputs"]).map { it["name"] }).doesNotContain("enabled")
        // the guards still refer to it
        assertThat(list(deleted["problems"])).isNotEmpty
        assertThatThrownBy { tools.deleteInput(file, "enabled") }.hasMessageContaining("No input 'enabled'")
    }

    fun `test dotLottie 1 files are converted to 2`() {
        val file = file("v1.lottie")
        val result = json(tools.convertToV2(file))
        assertThat(result["converted"]).isEqualTo(true)
        assertThat(DotLottieArchive.read(file.contentsToByteArray()).specVersion).isEqualTo(2)
        assertThat(json(tools.convertToV2(file))).containsEntry("converted", false)
    }

    fun `test extract and pack create files next to the original`() {
        file("interactive.lottie")
        val extracted = json(tools.extract(tools.resolve("interactive.lottie")))
        assertThat(extracted["created"]).isEqualTo("interactive.json")
        assertThat(LottieModel.read(VfsUtilCore.loadText(tools.resolve("interactive.json")))).isNotNull
        // existing files are kept
        assertThat(json(tools.extract(tools.resolve("interactive.lottie")))["created"]).isEqualTo("interactive-2.json")

        file("sample.json")
        val packed = json(tools.pack(tools.resolve("sample.json")))
        assertThat(packed["created"]).isEqualTo("sample.lottie")
        val archive = DotLottieArchive.read(tools.resolve("sample.lottie").contentsToByteArray())
        assertThat(archive.specVersion).isEqualTo(2)
        assertThat(archive.manifest.animations.map { it.id }).containsExactly("sample")
        assertThatThrownBy { tools.pack(tools.resolve("interactive.lottie")) }.hasMessageContaining("already a dotLottie file")
    }

    fun `test paths outside the project, missing files and other files are refused`() {
        file("sample.json")
        file("not-lottie.json")
        file("interactive.lottie")
        assertThat(tools.resolve("./sub/../sample.json").name).isEqualTo("sample.json")
        assertThat(tools.resolve(myFixture.tempDirFixture.getFile("")!!.path + "/sample.json").name).isEqualTo("sample.json")
        assertThatThrownBy { tools.resolve("../sample.json") }.isInstanceOf(ToolError::class.java).hasMessageContaining("outside the project")
        assertThatThrownBy { tools.resolve("/etc/passwd") }.hasMessageContaining("outside the project")
        assertThatThrownBy { tools.resolve("C:\\Windows\\win.ini") }.hasMessageContaining("outside the project")
        assertThatThrownBy { tools.resolve("missing.json") }.hasMessageContaining("No file 'missing.json'")
        assertThatThrownBy { tools.describeLottie(tools.resolve("not-lottie.json")) }.hasMessageContaining("is not a Lottie animation")
        assertThatThrownBy { tools.describeDotLottie(tools.resolve("sample.json")) }.hasMessageContaining("is not a dotLottie (.lottie) file")
        assertThatThrownBy { tools.describeStateMachine(tools.resolve("interactive.lottie"), "nope") }
            .hasMessageContaining("No state machine 'nope'").hasMessageContaining("toggle")
        assertThatThrownBy { tools.describeStateMachine(tools.resolve("interactive.lottie"), platform = "lottie-web") }.hasMessageContaining("Platforms:")
    }

    fun `test a render picks the frame and size and saves the image`() {
        val json = file("sample.json")
        val first = tools.prepareRender(json)
        assertThat(first.frame).isEqualTo(0.0)
        assertThat(first.request.json).isTrue()
        assertThat(first.request.width to first.request.height).isEqualTo(512 to 512)
        val outro = tools.prepareRender(json, marker = "outro", size = 100, background = "FFFFFF")
        assertThat(outro.frame).isEqualTo(30.0)
        assertThat(outro.request.frames).containsExactly(30.0)
        assertThat(outro.request.width).isEqualTo(100)
        assertThat(outro.request.background).isEqualTo("#ffffff")

        val lottie = file("interactive.lottie")
        val archived = tools.prepareRender(lottie, frame = 12.0)
        assertThat(archived.request.json).isFalse()
        assertThat(archived.request.animationId).isEqualTo("sample")

        val png = byteArrayOf(1, 2, 3)
        val temp = json(tools.saveRender(outro, png))
        assertThat(java.io.File(temp["image"].str()!!).readBytes()).isEqualTo(png)
        assertThat(temp["frame"]).isEqualTo(30L)
        assertThat(temp["marker"]).isEqualTo("outro")
        val saved = json(tools.saveRender(tools.prepareRender(json, output = "frame.png"), png))
        assertThat(saved["image"]).isEqualTo("frame.png")
        assertThat(tools.resolve("frame.png").contentsToByteArray()).isEqualTo(png)
        assertThat(json(tools.saveRender(tools.prepareRender(json, output = "frame.png"), png))["image"]).isEqualTo("frame-2.png")
    }

    fun `test bad render calls are refused`() {
        val json = file("sample.json")
        file("interactive.lottie")
        assertThatThrownBy { tools.prepareRender(json, frame = 1.0, marker = "intro") }.hasMessageContaining("either 'frame' or 'marker'")
        assertThatThrownBy { tools.prepareRender(json, frame = 500.0) }.hasMessageContaining("outside the animation's 0–60")
        assertThatThrownBy { tools.prepareRender(json, marker = "nope") }.hasMessageContaining("No marker 'nope'")
        assertThatThrownBy { tools.prepareRender(json, size = 5000) }.hasMessageContaining("16 to 2048")
        assertThatThrownBy { tools.prepareRender(json, background = "red") }.hasMessageContaining("'background'")
        assertThatThrownBy { tools.prepareRender(json, theme = "dark") }.hasMessageContaining("only applies to .lottie")
        assertThatThrownBy { tools.prepareRender(tools.resolve("interactive.lottie"), theme = "dark") }.hasMessageContaining("No theme 'dark'")
        assertThatThrownBy { tools.prepareRender(json, output = "frame.jpg") }.hasMessageContaining(".png path")
        assertThatThrownBy { tools.prepareRender(json, output = "../frame.png") }.hasMessageContaining("outside the project")
    }

    fun `test every tool of the toolset is described`() {
        val tools = cz.xprees.lottie.mcp.LottieToolset::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(com.intellij.mcpserver.annotations.McpTool::class.java)?.name }
        assertThat(tools).containsExactlyInAnyOrder(
            "lottie_describe", "dotlottie_describe", "dotlottie_state_machine", "dotlottie_check",
            "lottie_add_marker", "lottie_remove_marker", "dotlottie_add_input", "dotlottie_rename_input", "dotlottie_delete_input",
            "dotlottie_convert_to_v2", "lottie_extract", "lottie_pack", "lottie_render_frame",
        )
    }
}
