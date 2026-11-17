package cz.xprees.lottie

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.lottie.editor.LottieMarkerEditor
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.json.arr
import cz.xprees.lottie.json.obj
import cz.xprees.lottie.model.LottieModel
import org.assertj.core.api.Assertions.assertThat

class LottieMarkerEditorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // reformatting consults the bundled schema, read from the plugin jar in the test sandbox
        val schema = cz.xprees.lottie.lang.LottieFiles::class.java.getResource("/schemas/lottie.schema.json")!!
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, java.io.File(schema.path.substringAfter("file:").substringBefore("!/")).parent)
    }

    private fun editor(text: String = TestData.text("sample.json")): LottieMarkerEditor {
        myFixture.configureByText("anim.json", text)
        return LottieMarkerEditor(project, myFixture.editor.document)
    }

    private val text get() = myFixture.editor.document.text
    private val markers get() = LottieModel.read(text)!!.markers

    fun `test adding keeps markers sorted and the JSON valid`() {
        editor().add("hit", 12.0)
        assertThat(MiniJson.errorOf(text)).isNull()
        assertThat(markers.map { it.name to it.start }).containsExactly("intro" to 0.0, "hit" to 12.0, "outro" to 30.0)
        assertThat(text).contains("\"cm\": \"hit\"", "\"tm\": 12", "\"dr\": 0")
    }

    fun `test renaming keeps the After Effects JSON form`() {
        val e = editor()
        e.rename(1, "ending")
        val raw = MiniJson.parse(text).obj()!!["markers"].arr()!![1].obj()!!["cm"] as String
        assertThat(MiniJson.parse(raw).obj()!!["name"]).isEqualTo("ending")
        assertThat(markers.map { it.name }).containsExactly("intro", "ending")
        e.rename(0, "start")
        assertThat(MiniJson.parse(text).obj()!!["markers"].arr()!![0].obj()!!["cm"]).isEqualTo("start")
    }

    fun `test deleting and changing the range`() {
        val e = editor()
        e.setRange(0, 5.0, 10.5)
        assertThat(markers.first().let { it.start to it.duration }).isEqualTo(5.0 to 10.5)
        e.delete(0)
        assertThat(markers.map { it.name }).containsExactly("outro")
        e.delete(0)
        assertThat(markers).isEmpty()
        assertThat(MiniJson.errorOf(text)).isNull()
    }

    fun `test the markers array is created when missing`() {
        editor("""{"v": "5.7.0", "fr": 30, "ip": 0, "op": 60, "w": 10, "h": 10, "layers": []}""").add("tap", 20.0, 5.0)
        assertThat(MiniJson.errorOf(text)).isNull()
        assertThat(markers.map { Triple(it.name, it.start, it.duration) }).containsExactly(Triple("tap", 20.0, 5.0))
    }

    fun `test an edit is one undoable command`() {
        val e = editor()
        val before = text
        e.add("hit", 12.0)
        assertThat(text).isNotEqualTo(before)
        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(myFixture.file.virtualFile) as TextEditor
        UndoManager.getInstance(project).undo(fileEditor)
        assertThat(text).isEqualTo(before)
    }
}
