package cz.xprees.lottie.lang

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.lottie.LottieIcons
import javax.swing.Icon

/** The Lottie icon for Lottie `.json` files in the project view, editor tabs and Go to File. */
class LottieFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, @Iconable.IconFlags flags: Int, project: Project?): Icon? =
        if (LottieFiles.isLottieJson(file)) LottieIcons.LOTTIE else null
}
