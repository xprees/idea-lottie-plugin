package cz.xprees.lottie.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion
import cz.xprees.lottie.LottieBundle
import cz.xprees.lottie.lang.LottieFiles

/**
 * Validation and completion for Lottie JSON files from the Lottie Animation Community schema (bundled, see THIRD_PARTY_NOTICES.md),
 * applied automatically to every file recognized as Lottie.
 */
class LottieJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(LottieSchemaProvider)

    private object LottieSchemaProvider : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile) = LottieFiles.isLottieJson(file)

        override fun getName() = LottieBundle.message("schema.name")

        override fun getPresentableName() = LottieBundle.message("schema.name")

        override fun getSchemaFile(): VirtualFile? =
            LottieJsonSchemaProviderFactory::class.java.getResource("/schemas/lottie.schema.json")?.let(VfsUtil::findFileByURL)

        override fun getSchemaType() = SchemaType.embeddedSchema

        override fun getSchemaVersion() = JsonSchemaVersion.SCHEMA_2020_12

        override fun getRemoteSource() = "https://lottie.github.io/lottie-spec/"
    }
}
