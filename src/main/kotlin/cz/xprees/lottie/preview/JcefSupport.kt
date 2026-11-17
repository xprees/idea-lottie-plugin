package cz.xprees.lottie.preview

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.jcef.JBCefApp

/** Whether the preview's embedded browser (JCEF) can be used. */
internal object JcefSupport {
    private val LOG = logger<JcefSupport>()

    /**
     * True when JCEF is present and supported. From 2026.3 JCEF is a separate plugin; without it (or with a runtime that has no
     * JCEF) its classes are missing, and the preview shows a notice instead of failing to open.
     */
    fun isAvailable(): Boolean = try {
        JBCefApp.isSupported()
    } catch (e: LinkageError) {
        LOG.info("JCEF is not available, the Lottie preview is disabled", e)
        false
    }
}
