package cz.xprees.lottie

import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import cz.xprees.lottie.editor.LottieColorProvider
import cz.xprees.lottie.editor.LottieInlayHintsProvider
import cz.xprees.lottie.editor.LottiePsi
import cz.xprees.lottie.editor.LottieStructureViewFactory
import cz.xprees.lottie.lang.DotLottieFileType
import cz.xprees.lottie.lang.LottieFileIconProvider
import cz.xprees.lottie.lang.LottieFiles
import cz.xprees.lottie.preview.DotLottieEditorProvider
import cz.xprees.lottie.preview.LottieJsonEditorProvider
import org.assertj.core.api.Assertions.assertThat
import java.awt.Color

class LottiePlatformTest : BasePlatformTestCase() {

    private val sample get() = TestData.text("sample.json")

    override fun setUp() {
        super.setUp()
        // the bundled schema is read from the plugin jar in the test sandbox
        val schema = LottieFiles::class.java.getResource("/schemas/lottie.schema.json")!!
        val jar = schema.path.substringAfter("file:").substringBefore("!/")
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, java.io.File(jar).parent ?: jar)
    }

    private fun lottieFile(name: String = "anim.json", text: String = sample): JsonFile = myFixture.configureByText(name, text) as JsonFile

    fun `test Lottie JSON is recognized by content and by name`() {
        assertThat(LottieFiles.isLottieJson(lottieFile().virtualFile)).isTrue()
        assertThat(LottieFiles.isLottieJson(myFixture.configureByText("package.json", TestData.text("not-lottie.json")).virtualFile)).isFalse()
        assertThat(LottieFiles.isLottieJson(myFixture.configureByText("broken.lottie.json", "{").virtualFile)).isTrue()
    }

    fun `test recognition follows unsaved edits`() {
        val file = myFixture.configureByText("later.json", "{}").virtualFile
        assertThat(LottieFiles.isLottieJson(file)).isFalse()
        myFixture.editor.document.let { doc ->
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { doc.setText(sample) }
        }
        assertThat(FileDocumentManager.getInstance().isFileModified(file)).isTrue()
        assertThat(LottieFiles.isLottieJson(file)).isTrue()
    }

    fun `test icons and editors`() {
        val json = lottieFile().virtualFile
        assertThat(LottieFileIconProvider().getIcon(json, 0, project)).isSameAs(LottieIcons.LOTTIE)
        assertThat(LottieJsonEditorProvider().accept(project, json)).isTrue()
        assertThat(DotLottieEditorProvider().accept(project, json)).isFalse()

        val dot = myFixture.tempDirFixture.createFile("sample.lottie")
        com.intellij.openapi.application.WriteAction.runAndWait<Exception> { dot.setBinaryContent(TestData.bytes("sample.lottie")) }
        assertThat(dot.fileType).isEqualTo(DotLottieFileType)
        assertThat(DotLottieEditorProvider().accept(project, dot)).isTrue()
        assertThat(LottieJsonEditorProvider().accept(project, dot)).isFalse()
    }

    fun `test the Lottie schema applies to Lottie files only`() {
        val lottie = lottieFile().virtualFile
        val schemas = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(lottie)
        assertThat(schemas.map { it.name }).contains("lottie.schema.json")
        val other = myFixture.configureByText("other.json", TestData.text("not-lottie.json")).virtualFile
        assertThat(JsonSchemaService.Impl.get(project).getSchemaFilesForFile(other).map { it.name }).doesNotContain("lottie.schema.json")
    }

    fun `test the schema reports invalid values`() {
        myFixture.enableInspections(com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection::class.java)
        lottieFile(text = sample.replaceFirst("\"fr\": 30", "\"fr\": \"thirty\""))
        val problems = myFixture.doHighlighting().filter { it.severity.myVal >= com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING.myVal }
        assertThat(problems.map { myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset)) })
            .contains("\"thirty\"")
    }

    fun `test path navigation`() {
        val file = lottieFile()
        val fill = LottiePsi.findByPath(file, listOf("layers", 0, "shapes", 0, "it", 2, "c", "k"))!!
        assertThat(fill.text.replace(Regex("\\s"), "")).isEqualTo("[1,1,1,1]")
        assertThat(LottiePsi.pathOf(fill)).isEqualTo(listOf("layers", 0, "shapes", 0, "it", 2, "c", "k"))
        val badgeOffset = file.text.indexOf("\"refId\"")
        assertThat(LottiePsi.rootLayerIndexAt(file, badgeOffset)).isEqualTo(1)
        assertThat(LottiePsi.rootLayerIndexAt(file, file.text.indexOf("\"markers\""))).isNull()
        val keyframe = file.text.indexOf("\"t\": 30")
        assertThat(LottiePsi.keyframeTimeAt(file, keyframe)).isEqualTo(30.0)
    }

    fun `test color swatches and write back`() {
        val file = lottieFile()
        val provider = LottieColorProvider()
        val colors = SyntaxTraverser.psiTraverser(file).filter { it.firstChild == null }.mapNotNull { leaf -> provider.getColorFrom(leaf)?.let { leaf to it } }.toList()
        // stroke (white), two fill keyframes, slotted fill, its slot value, solid background; not positions or scales
        assertThat(colors.map { it.second }).containsExactlyInAnyOrder(
            Color(255, 51, 51), Color(51, 102, 255), Color(255, 255, 255), Color(0, 153, 128), Color(0, 153, 128), Color(255, 204, 0),
        )
        val stroke = colors.first { it.second == Color(255, 255, 255) }.first
        provider.setColorTo(stroke, Color(0, 128, 255))
        assertThat(myFixture.editor.document.text).contains("\"k\": [0, 0.502, 1, 1]")
        val solid = colors.first { it.second == Color(255, 204, 0) }.first
        provider.setColorTo(solid, Color(17, 34, 51))
        assertThat(myFixture.editor.document.text).contains("\"sc\": \"#112233\"")
    }

    fun `test no swatches outside Lottie files`() {
        val file = myFixture.configureByText("data.json", """{"c": {"k": [1, 0, 0, 1]}, "sc": "#ff0000"}""")
        val provider = LottieColorProvider()
        assertThat(SyntaxTraverser.psiTraverser(file).filter { it.firstChild == null }.mapNotNull { provider.getColorFrom(it) }.toList()).isEmpty()
    }

    fun `test inlay hints`() {
        val file = lottieFile()
        val collector = LottieInlayHintsProvider().createCollector(file, myFixture.editor) as SharedBypassCollector
        val sink = RecordingSink()
        SyntaxTraverser.psiTraverser(file).forEach { collector.collectFromElement(it, sink) }
        val hints = sink.hints.map { it.second }
        assertThat(hints).contains("Shape", "Shape “Circle”", "Ellipse", "Fill", "Gradient fill", "Solid", "Null", "→ “Controller”", "1.00 s", "2.00 s")
        // the text after "parent": 3
        val parent = sink.hints.first { it.second == "→ “Controller”" }.first
        assertThat(file.text.substring(0, parent)).endsWith("\"parent\": 3")
    }

    fun `test structure view shows layers and shapes`() {
        val file = lottieFile()
        val builder = LottieStructureViewFactory().getStructureViewBuilder(file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(null)
        try {
            fun render(element: StructureViewTreeElement, depth: Int, out: StringBuilder) {
                val p = element.presentation
                out.append("  ".repeat(depth)).append(p.presentableText).append(p.locationString?.let { " [$it]" } ?: "").append('\n')
                element.children.forEach { render(it as StructureViewTreeElement, depth + 1, out) }
            }
            val text = StringBuilder().also { render(model.root, 0, it) }.toString()
            assertThat(text).contains(
                "Sample [200×200 · 30 fps · 2.00 s]",
                "  Layers [5]",
                "    Circle [Shape · #1 · 0–60 · parent 3]",
                "      Circle group [Group]",
                "        Ellipse [Ellipse]",
                "    Badge [Precomposition · #2 · 0–60]",
                "  Assets [1]",
                "    Badge [precomposition, 1 layers]",
                "  Markers [2]",
                "    outro [30–59]",
                "  Slots [1]",
            )
        } finally {
            model.dispose()
        }
        assertThat(LottieStructureViewFactory().getStructureViewBuilder(myFixture.configureByText("x.json", "{\"a\":1}") as JsonFile)).isNull()
    }

    private class RecordingSink : InlayTreeSink {
        val hints = mutableListOf<Pair<Int, String>>()

        override fun addPresentation(position: InlayPosition, payloads: List<InlayPayload>?, tooltip: String?, hintFormat: HintFormat, builder: PresentationTreeBuilder.() -> Unit) {
            val texts = StringBuilder()
            val b = object : PresentationTreeBuilder {
                override fun list(builder: PresentationTreeBuilder.() -> Unit) = builder(this)
                override fun collapsibleList(state: CollapseState, expandedState: CollapsiblePresentationTreeBuilder.() -> Unit, collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit) {}
                override fun text(text: String, actionData: InlayActionData?) {
                    texts.append(text)
                }
                override fun clickHandlerScope(actionData: InlayActionData, builder: PresentationTreeBuilder.() -> Unit) = builder(this)
            }
            b.builder()
            hints += (position as InlineInlayPosition).offset to texts.toString()
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) = block()
    }
}
