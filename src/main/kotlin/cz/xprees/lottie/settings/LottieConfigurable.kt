package cz.xprees.lottie.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.ColorUtil
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selectedValueIs
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.settings.LottieSettings.Background
import cz.xprees.lottie.settings.LottieSettings.DefaultLayout
import java.awt.Color

/** Settings › Languages & Frameworks › Lottie. */
class LottieConfigurable : BoundSearchableConfigurable(LottieBundle.message("settings.display.name"), "cz.xprees.lottie.settings") {
    private val state get() = LottieSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        group(LottieBundle.message("settings.group.files")) {
            row {
                checkBox(LottieBundle.message("settings.sniff"))
                    .bindSelected(state::sniffJson)
                    .comment(LottieBundle.message("settings.sniff.comment"))
            }
        }
        group(LottieBundle.message("settings.group.preview")) {
            row(LottieBundle.message("settings.layout")) {
                comboBox(DefaultLayout.entries, textListCellRenderer<DefaultLayout?> { it?.let { layout -> LottieBundle.message("settings.layout.${layout.name.lowercase()}") } })
                    .bind({ it.selectedItem as? DefaultLayout ?: DefaultLayout.EDITOR_AND_PREVIEW }, { c, v -> c.selectedItem = v }, state::defaultLayout.toMutableProperty())
                    .comment(LottieBundle.message("settings.layout.comment"))
            }
            lateinit var background: com.intellij.ui.dsl.builder.Cell<javax.swing.JComboBox<Background>>
            row(LottieBundle.message("settings.background")) {
                background = comboBox(Background.entries, textListCellRenderer<Background?> { it?.let { b -> LottieBundle.message("background.${b.name.lowercase()}") } })
                    .bind({ it.selectedItem as? Background ?: Background.CHECKERBOARD }, { c, v -> c.selectedItem = v }, state::background.toMutableProperty())
                val colorPanel = ColorPanel()
                cell(colorPanel)
                    .bind(
                        { it.selectedColor?.let { c -> "#" + ColorUtil.toHex(c) } ?: state.customBackground },
                        { c, v -> c.selectedColor = runCatching { ColorUtil.fromHex(v) }.getOrDefault(Color.GRAY) },
                        state::customBackground.toMutableProperty(),
                    )
                    .visibleIf(background.component.selectedValueIs(Background.CUSTOM))
            }
            row {
                checkBox(LottieBundle.message("settings.autoplay")).bindSelected(state::autoplay)
            }
            row {
                checkBox(LottieBundle.message("settings.loop")).bindSelected(state::loop)
            }
        }
        group(LottieBundle.message("settings.group.editor")) {
            row {
                checkBox(LottieBundle.message("settings.swatches"))
                    .bindSelected(state::colorSwatches)
                    .comment(LottieBundle.message("settings.swatches.comment"))
            }
            row {
                checkBox(LottieBundle.message("settings.inlays"))
                    .bindSelected(state::inlayHints)
            }
            row {
                checkBox(LottieBundle.message("settings.sync"))
                    .bindSelected(state::previewSync)
                    .comment(LottieBundle.message("settings.sync.comment"))
            }
        }
    }

    override fun apply() {
        super.apply()
        LottieSettings.getInstance().update { }
        // File icons, inlays and gutter swatches depend on these settings
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            ProjectView.getInstance(project).refresh()
            DaemonCodeAnalyzer.getInstance(project).restart("Lottie settings changed")
        }
    }
}

private fun <T> kotlin.reflect.KMutableProperty0<T>.toMutableProperty() = com.intellij.ui.dsl.builder.MutableProperty({ get() }, { set(it) })
