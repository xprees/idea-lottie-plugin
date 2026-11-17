package cz.xprees.lottie.preview

import com.intellij.openapi.util.text.StringUtil
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.dotlottie.DotLottieArchive

/** The manifest and contents of a `.lottie` file as HTML for the details panel. */
internal object DotLottieDetails {

    fun html(archive: DotLottieArchive): String {
        val m = archive.manifest
        val sb = StringBuilder("<html><body>")
        fun row(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            sb.append("<tr><td valign='top'><b>").append(esc(label)).append("</b></td><td>").append(esc(value)).append("</td></tr>")
        }
        sb.append("<h3>").append(esc(LottieBundle.message("details.title"))).append("</h3><table>")
        row(LottieBundle.message("details.spec"), "dotLottie ${archive.specVersion}.0" + (m.version?.let { " (manifest $it)" } ?: ""))
        row(LottieBundle.message("details.generator"), m.generator)
        row(LottieBundle.message("details.author"), m.author)
        row(LottieBundle.message("details.initial"), listOfNotNull(m.initialAnimation, m.initialStateMachine?.let { "state machine $it" }).joinToString(", "))
        sb.append("</table>")

        sb.append("<h4>").append(esc(LottieBundle.message("details.animations", m.animations.size))).append("</h4><ul>")
        for (a in m.animations) {
            sb.append("<li><code>").append(esc(a.id)).append("</code>")
            val extras = listOfNotNull(
                a.initialTheme?.let { LottieBundle.message("details.initial.theme", it) },
                a.themes.takeIf { it.isNotEmpty() }?.let { LottieBundle.message("details.scoped.themes", it.joinToString(", ")) },
                a.background?.let { LottieBundle.message("details.background", "#%08x".format(it)) },
            )
            if (extras.isNotEmpty()) sb.append(" — ").append(esc(extras.joinToString("; ")))
            sb.append("</li>")
        }
        sb.append("</ul>")
        if (m.themes.isNotEmpty()) {
            sb.append("<h4>").append(esc(LottieBundle.message("details.themes", m.themes.size))).append("</h4><ul>")
            m.themes.forEach { sb.append("<li><code>").append(esc(it.id)).append("</code>").append(it.name?.let { n -> " — " + esc(n) } ?: "").append("</li>") }
            sb.append("</ul>")
        }
        if (m.stateMachines.isNotEmpty()) {
            sb.append("<h4>").append(esc(LottieBundle.message("details.state.machines", m.stateMachines.size))).append("</h4><ul>")
            m.stateMachines.forEach { sb.append("<li><code>").append(esc(it.id)).append("</code>").append(it.name?.let { n -> " — " + esc(n) } ?: "").append("</li>") }
            sb.append("</ul>")
        }
        val entries = archive.entries
        sb.append("<h4>").append(esc(LottieBundle.message("details.files", entries.size, StringUtil.formatFileSize(entries.sumOf { it.size })))).append("</h4><table>")
        for (e in entries) {
            sb.append("<tr><td><code>").append(esc(e.path)).append("</code></td><td>").append(esc(e.kind)).append("</td><td align='right'>")
                .append(esc(StringUtil.formatFileSize(e.size))).append("</td></tr>")
        }
        sb.append("</table></body></html>")
        return sb.toString()
    }

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)
}
