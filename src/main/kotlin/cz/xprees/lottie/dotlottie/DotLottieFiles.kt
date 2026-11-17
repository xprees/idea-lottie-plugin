package cz.xprees.lottie.dotlottie

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Changes `.lottie` files on disk. */
object DotLottieFiles {

    /**
     * Replaces entries of the `.lottie` [file] (see [DotLottieWriter.replace]) as one command. With [undoable], the change can be
     * undone and redone like a text edit (Ctrl+Z in the file's editor restores the previous archive bytes).
     */
    fun replaceEntries(project: Project, file: VirtualFile, changes: Map<String, ByteArray?>, command: String, undoable: Boolean = true) =
        rewrite(project, file, command, undoable) { DotLottieWriter.replace(it, changes) }

    /** Replaces the whole content of the `.lottie` [file] with what [transform] makes of it, as one undoable command. */
    fun rewrite(project: Project, file: VirtualFile, command: String, undoable: Boolean = true, transform: (ByteArray) -> ByteArray) {
        WriteCommandAction.writeCommandAction(project).withName(command).run<RuntimeException> {
            val before = file.contentsToByteArray()
            val after = transform(before)
            file.setBinaryContent(after)
            if (undoable) {
                UndoManager.getInstance(project).undoableActionPerformed(object : BasicUndoableAction(file) {
                    override fun undo() = file.setBinaryContent(before)
                    override fun redo() = file.setBinaryContent(after)
                })
            }
        }
    }
}
