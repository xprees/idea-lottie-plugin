package cz.xprees.lottie.snippets

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The platform the Code tab generates snippets for, per project: the one picked in the tab, or else the one the project's build files
 * suggest ([detected], found once in the background).
 */
@Service(Service.Level.PROJECT)
class CodePlatforms(private val project: Project) : Disposable {

    @Volatile
    var detected: List<DetectedPlatform> = emptyList()
        private set

    @Volatile
    var detectionDone = false
        private set

    private val started = AtomicBoolean()

    /** The picked platform, or null to follow [detected]. */
    var picked: CodePlatform?
        get() = PropertiesComponent.getInstance(project).getValue(KEY)?.let { name -> CodePlatform.entries.firstOrNull { it.name == name } }
        set(value) {
            PropertiesComponent.getInstance(project).setValue(KEY, value?.name)
            changed()
        }

    val current: CodePlatform get() = picked ?: detected.firstOrNull()?.platform ?: CodePlatform.DOTLOTTIE_WEB

    /** Starts reading the build files, once; listeners hear about the result. */
    fun detect() {
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                ReadAction.nonBlocking<List<DetectedPlatform>> { PlatformDetector.detect(buildFiles()) }.expireWith(this).executeSynchronously()
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                LOG.info("Could not read the build files to guess the project's platform", e)
                emptyList()
            }
            detected = result
            detectionDone = true
            ApplicationManager.getApplication().invokeLater({ changed() }, project.disposed)
        }
    }

    /** The build files [PlatformDetector] reads, by path relative to the project, skipping dependencies, build output and dot folders. */
    private fun buildFiles(): Map<String, String> {
        val root = project.guessProjectDir() ?: return emptyMap()
        val files = LinkedHashMap<String, String>()
        VfsUtilCore.visitChildrenRecursively(root, object : com.intellij.openapi.vfs.VirtualFileVisitor<Unit>(limit(PlatformDetector.MAX_DEPTH)) {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) return file == root || file.name !in SKIPPED && !file.name.startsWith(".")
                if (file.name in PlatformDetector.FILE_NAMES && file.length < MAX_FILE && files.size < MAX_FILES) {
                    VfsUtilCore.getRelativePath(file, root)?.let { path ->
                        runCatching { VfsUtilCore.loadText(file) }.getOrNull()?.let { files[path] = it }
                    }
                }
                return true
            }
        })
        return files
    }

    private fun changed() {
        if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).platformChanged(current)
    }

    override fun dispose() {}

    fun interface Listener {
        fun platformChanged(platform: CodePlatform)
    }

    companion object {
        private val LOG = logger<CodePlatforms>()
        private const val KEY = "cz.xprees.lottie.code.platform"
        private const val MAX_FILE = 512 * 1024L
        private const val MAX_FILES = 200
        private val SKIPPED = setOf("node_modules", "build", "dist", "out", "target", "Pods", "DerivedData", "bin", "obj")

        @JvmField
        @Topic.ProjectLevel
        val TOPIC = Topic.create("Lottie code platform", Listener::class.java)

        fun getInstance(project: Project): CodePlatforms = project.getService(CodePlatforms::class.java)

        private fun limit(depth: Int) = com.intellij.openapi.vfs.VirtualFileVisitor.limit(depth + 1)
    }
}
