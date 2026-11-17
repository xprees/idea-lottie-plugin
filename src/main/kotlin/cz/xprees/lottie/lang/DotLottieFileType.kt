package cz.xprees.lottie.lang

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.LottieIcons
import javax.swing.Icon

/** `.lottie` files: dotLottie ZIP archives, opened in the preview only. */
object DotLottieFileType : FileType {
    const val EXTENSION = "lottie"

    override fun getName() = "dotLottie"

    override fun getDescription() = LottieBundle.message("filetype.dotlottie.description")

    override fun getDisplayName() = LottieBundle.message("filetype.dotlottie.display.name")

    override fun getDefaultExtension() = EXTENSION

    override fun getIcon(): Icon = LottieIcons.DOT_LOTTIE

    override fun isBinary() = true

    override fun isReadOnly() = true

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
