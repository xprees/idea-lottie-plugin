package cz.xprees.lottie

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object LottieIcons {
    /** Lottie animations stored as JSON. */
    @JvmField
    val LOTTIE: Icon = IconLoader.getIcon("/icons/lottie.svg", LottieIcons::class.java)

    /** dotLottie archives (.lottie). */
    @JvmField
    val DOT_LOTTIE: Icon = IconLoader.getIcon("/icons/dotLottie.svg", LottieIcons::class.java)

    /** Play in the preview's playback controls: neutral like the other controls (the platform's Run icon is green). */
    @JvmField
    val PLAY: Icon = IconLoader.getIcon("/icons/play.svg", LottieIcons::class.java)
}
