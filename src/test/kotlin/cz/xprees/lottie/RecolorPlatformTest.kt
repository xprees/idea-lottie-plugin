package cz.xprees.lottie

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.lottie.dotlottie.DotLottieArchive
import cz.xprees.lottie.json.MiniJson
import cz.xprees.lottie.palette.LottieColor
import cz.xprees.lottie.palette.PaletteScanner
import cz.xprees.lottie.palette.RecolorWriter
import org.assertj.core.api.Assertions.assertThat

class RecolorPlatformTest : BasePlatformTestCase() {
    private val green = LottieColor.fromHex("#00ff00")!!

    private fun palette(text: String) = PaletteScanner.palette(PaletteScanner.scan(MiniJson.parse(text)))

    fun `test recoloring a JSON file changes every use in one undoable command`() {
        val text = TestData.text("sample.json")
        myFixture.configureByText("anim.lottie.json", text)
        val document = myFixture.editor.document
        val white = palette(text).single { it.color.hex == "#ffffff" }
        RecolorWriter.toDocument(project, document, white.usages, green)
        assertThat(palette(document.text).map { it.color.hex }).doesNotContain("#ffffff").contains("#00ff00")
        // only the numbers changed: same number of lines
        assertThat(document.text.lines().size).isEqualTo(text.lines().size)

        val editor: TextEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)
        undo.undo(editor)
        assertThat(document.text).isEqualTo(text)
    }

    private fun archive(): VirtualFile {
        val file = myFixture.tempDirFixture.createFile("interactive.lottie")
        WriteAction.runAndWait<Exception> { file.setBinaryContent(TestData.bytes("interactive.lottie")) }
        return file
    }

    fun `test recoloring a dotLottie animation or theme is one undoable change`() {
        val file = archive()
        val editor = cz.xprees.lottie.preview.LottiePreviewFileEditor(project, file, null)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, editor)
        val original = file.contentsToByteArray()
        val animation = DotLottieArchive.read(original).animationText("sample")!!
        val white = palette(animation).single { it.color.hex == "#ffffff" }
        val undo = UndoManager.getInstance(project)

        RecolorWriter.toDotLottieAnimation(project, file, "sample", white.usages, green)
        assertThat(palette(DotLottieArchive.read(file.contentsToByteArray()).animationText("sample")!!).map { it.color.hex }).doesNotContain("#ffffff")
        undo.undo(editor)
        assertThat(file.contentsToByteArray()).isEqualTo(original)

        val (themed, skipped) = RecolorWriter.toDotLottieTheme(project, file, "sample", white.usages, white.color, green, "night", "Night")
        assertThat(themed).isEqualTo(white.count)
        assertThat(skipped).isZero()
        val archive = DotLottieArchive.read(file.contentsToByteArray())
        assertThat(archive.manifest.themes.map { it.id }).containsExactly("night")
        assertThat(archive.themeText("night")).contains("\"Color\"")
        // without the theme the animation keeps its colors
        assertThat(palette(archive.animationText("sample")!!).map { it.color.hex }).contains("#ffffff")
        undo.undo(editor)
        assertThat(file.contentsToByteArray()).isEqualTo(original)
    }
}
