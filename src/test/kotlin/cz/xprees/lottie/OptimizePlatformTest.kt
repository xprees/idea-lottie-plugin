package cz.xprees.lottie

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.lottie.actions.OptimizeLottieAction
import cz.xprees.lottie.optimize.OptimizeOptions
import cz.xprees.lottie.optimize.Optimizer
import org.assertj.core.api.Assertions.assertThat

class OptimizePlatformTest : BasePlatformTestCase() {

    fun `test optimizing a JSON file in place is one undoable change`() {
        val text = TestData.text("sample.json")
        myFixture.configureByText("anim.lottie.json", text)
        val file = myFixture.file.virtualFile
        val result = Optimizer.optimizeJson(text, OptimizeOptions())
        OptimizeLottieAction().rewrite(project, file, result)
        assertThat(myFixture.editor.document.text).isEqualTo(result.bytes.toString(Charsets.UTF_8))

        val editor: TextEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)
        assertThat(undo.isUndoAvailable(editor)).isTrue()
        undo.undo(editor)
        assertThat(myFixture.editor.document.text).isEqualTo(text)
    }
}
