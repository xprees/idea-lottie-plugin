package cz.xprees.lottie.lang

import com.intellij.json.JsonFileType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.lottie.detect.LottieSniffer
import cz.xprees.lottie.settings.LottieSettings

/**
 * Which files are Lottie animations: `.lottie` archives (by file type), `*.lottie.json` files (by name) and other `.json` files
 * whose content looks like Lottie ([LottieSniffer]). Lottie JSON stays a JSON file, so highlighting, formatting and schemas keep
 * working; this only decides where the Lottie features apply.
 */
object LottieFiles {

    const val JSON_SUFFIX = ".lottie.json"

    /** Files larger than this are never sniffed (still recognized by the `.lottie.json` name). */
    private const val MAX_SNIFF_FILE_SIZE = 256L * 1024 * 1024

    private class Sniffed(val stamp: Long, val length: Long, val documentStamp: Long, val lottie: Boolean)

    private val SNIFFED = Key.create<Sniffed>("cz.xprees.lottie.sniffed")

    fun isDotLottie(file: VirtualFile): Boolean =
        !file.isDirectory && (file.fileType == DotLottieFileType || file.extension.equals(DotLottieFileType.EXTENSION, ignoreCase = true))

    fun isLottie(file: VirtualFile): Boolean = isDotLottie(file) || isLottieJson(file)

    fun isLottieJson(file: VirtualFile): Boolean {
        if (file.isDirectory || !isJson(file)) return false
        if (file.name.endsWith(JSON_SUFFIX, ignoreCase = true)) return true
        if (!LottieSettings.getInstance().state.sniffJson) return false
        return sniff(file)
    }

    private fun isJson(file: VirtualFile): Boolean =
        FileTypeRegistry.getInstance().isFileOfType(file, JsonFileType.INSTANCE) || file.extension.equals("json", ignoreCase = true)

    private fun sniff(file: VirtualFile): Boolean {
        // An open document with unsaved changes (a new file pasted in, an edit in progress) is sniffed instead of the disk content
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val documentStamp = document?.modificationStamp ?: -1L
        val cached = file.getUserData(SNIFFED)
        if (cached != null && cached.stamp == file.modificationStamp && cached.length == file.length && cached.documentStamp == documentStamp) {
            return cached.lottie
        }
        val lottie = try {
            if (document != null) {
                val text = document.immutableCharSequence
                val head = text.subSequence(0, minOf(text.length, LottieSniffer.WINDOW)).toString().toByteArray(Charsets.UTF_8)
                LottieSniffer.looksLikeLottie(head)
            } else if (!file.isValid || file.length > MAX_SNIFF_FILE_SIZE) {
                false
            } else {
                file.inputStream.use { input -> input.readNBytes(LottieSniffer.WINDOW) }.let(LottieSniffer::looksLikeLottie)
            }
        } catch (_: Exception) {
            false
        }
        file.putUserData(SNIFFED, Sniffed(file.modificationStamp, file.length, documentStamp, lottie))
        return lottie
    }

    /** `base.ext`, or `base-2.ext`, `base-3.ext`, ... when taken. */
    fun uniqueName(directory: VirtualFile, base: String, extension: String): String {
        var name = "$base.$extension"
        var n = 2
        while (directory.findChild(name) != null) name = "$base-${n++}.$extension"
        return name
    }
}
