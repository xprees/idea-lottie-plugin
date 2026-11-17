import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// ---- Plugin version ---------------------------------------------------------------------------------------------
// 1. -PpluginVersion / PLUGIN_VERSION: used as given.
// 2. A version tag on HEAD (0.2.1, v0.2.1, 0.3.0-beta.1): that version. This is what the release workflow builds, so the
//    tag name, the plugin version and the Marketplace channel (taken from the suffix) always agree.
// 3. Otherwise <major>.<minor>.<next patch>: major/minor from PLUGIN_VERSION_MAJOR/MINOR, else pluginVersionMajor/Minor
//    (gradle.properties or -P), else 0.1, next patch = highest released <major>.<minor>.N tag + 1, or 0 without tags.
class VersionTag(val major: Int, val minor: Int, val patch: Int, val suffix: String) {
    val version get() = "$major.$minor.$patch$suffix"
}

fun parseVersionTag(tag: String): VersionTag? =
    Regex("""v?(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.]+)?""").matchEntire(tag)?.destructured
        ?.let { (major, minor, patch, suffix) -> VersionTag(major.toInt(), minor.toInt(), patch.toInt(), suffix) }

/** Output lines of a git command, or null when git or the repository isn't available. */
fun git(vararg args: String): List<String>? = runCatching {
    val output = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    if (output.result.get().exitValue == 0) output.standardOutput.asText.get().lines().map(String::trim).filter(String::isNotEmpty) else null
}.getOrNull()

/** GitHub Actions passes an undefined `vars.X` as an empty string, so blank counts as not set. */
fun versionVariable(property: String, env: String, fallback: String) =
    providers.environmentVariable(env).map(String::trim).filter(String::isNotEmpty)
        .orElse(providers.gradleProperty(property).map(String::trim).filter(String::isNotEmpty))
        .orElse(fallback).get()

val explicitVersion = providers.gradleProperty("pluginVersion").orElse(providers.environmentVariable("PLUGIN_VERSION"))
    .map(String::trim).filter(String::isNotEmpty).orNull
val tagOnHead = git("tag", "--points-at", "HEAD").orEmpty().mapNotNull(::parseVersionTag)
    .maxWithOrNull(compareBy<VersionTag>({ it.major }, { it.minor }, { it.patch }))

/** Where [pluginVersion] came from: "explicit", "tag" or "derived" (only the first two may be published). */
val pluginVersionSource = when {
    explicitVersion != null -> "explicit"
    tagOnHead != null -> "tag"
    else -> "derived"
}

val pluginVersion: String = when (pluginVersionSource) {
    "explicit" -> explicitVersion!!.removePrefix("v")
    "tag" -> tagOnHead!!.version
    else -> {
        val major = versionVariable("pluginVersionMajor", "PLUGIN_VERSION_MAJOR", "0").toInt()
        val minor = versionVariable("pluginVersionMinor", "PLUGIN_VERSION_MINOR", "1").toInt()
        val releasedPatches = git("tag", "--list").orEmpty().mapNotNull(::parseVersionTag)
            .filter { it.major == major && it.minor == minor && it.suffix.isEmpty() }
            .map { it.patch }
        "$major.$minor.${(releasedPatches.maxOrNull() ?: -1) + 1}"
    }
}

version = pluginVersion

val platformVersion = providers.gradleProperty("platformVersion")

// "2026.2.3" -> "262": two-digit year followed by the release number, which is how IDE build numbers start.
// (Written out inline: a shared helper function would make these providers reference the build script, which the configuration cache refuses.)
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild").orElse(
    platformVersion.map { version ->
        val (year, release) = version.split('.').map(String::toInt)
        "${year - 2000}$release"
    },
)
// No upper bound by default: the plugin is declared compatible with every future IDE and narrowed (-PpluginUntilBuild=263.*)
// only if a newer release turns out to break it.
val pluginUntilBuild = providers.gradleProperty("pluginUntilBuild")

/** Comma separated version list from gradle.properties. */
fun versionList(property: String) = providers.gradleProperty(property).map { list -> list.split(',').map(String::trim).filter(String::isNotEmpty) }

// ---- Bundled web assets -----------------------------------------------------------------------------------------
// npm packages are downloaded from the registry at build time (no binaries in git), verified against the integrity hash pinned
// in gradle.properties and unpacked into generated resources. Only the files the plugin needs are kept.

/** Downloads one npm tarball, checks its `sha512-...` integrity and copies the selected files (paths inside `package/`). */
@CacheableTask
abstract class FetchNpmPackage : DefaultTask() {
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val packageVersion: Property<String>
    @get:Input abstract val integrity: Property<String>
    /** `package/` relative path -> destination path inside [outputDir]. */
    @get:Input abstract val files: MapProperty<String, String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Internal abstract val tempDir: DirectoryProperty

    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun fetch() {
        val name = packageName.get()
        val version = packageVersion.get()
        val url = "https://registry.npmjs.org/$name/-/${name.substringAfter('/')}-$version.tgz"
        val tarball = tempDir.file("${name.replace('/', '_')}-$version.tgz").get().asFile
        tarball.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input -> tarball.outputStream().use { input.copyTo(it) } }

        val expected = integrity.get()
        check(expected.startsWith("sha512-")) { "Unsupported integrity format for $name: $expected" }
        val actual = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(tarball.readBytes()))
        check(actual == expected) { "Integrity check failed for $name@$version: expected $expected, got $actual" }

        val wanted = files.get()
        fileSystem.delete { delete(outputDir) }
        wanted.forEach { (source, destination) ->
            fileSystem.copy {
                from(archives.tarTree(tarball)) { include("package/$source") }
                eachFile { path = destination }
                includeEmptyDirs = false
                into(outputDir)
            }
            check(outputDir.file(destination).get().asFile.isFile) { "$source not found in $name@$version" }
        }
    }
}

val fetchDotLottieWeb = tasks.register<FetchNpmPackage>("fetchDotLottieWeb") {
    group = "build setup"
    description = "Downloads the pinned @lottiefiles/dotlottie-web player (JS + WebAssembly) used by the preview."
    packageName = "@lottiefiles/dotlottie-web"
    packageVersion = providers.gradleProperty("dotlottieWebVersion")
    integrity = providers.gradleProperty("dotlottieWebIntegrity")
    files = mapOf(
        "dist/index.js" to "web/player/dotlottie-web.js",
        "dist/dotlottie-player.wasm" to "web/player/dotlottie-player.wasm",
        "LICENSE" to "web/player/LICENSE",
    )
    outputDir = layout.buildDirectory.dir("generated/npm/dotlottie-web")
    tempDir = layout.buildDirectory.dir("tmp/npm")
}

val fetchLottieSchema = tasks.register<FetchNpmPackage>("fetchLottieSchema") {
    group = "build setup"
    description = "Downloads the pinned Lottie Animation Community JSON Schema (@lottie-animation-community/lottie-specs)."
    packageName = "@lottie-animation-community/lottie-specs"
    packageVersion = providers.gradleProperty("lottieSpecsVersion")
    integrity = providers.gradleProperty("lottieSpecsIntegrity")
    files = mapOf(
        "src/data/lottie.schema.json" to "schemas/lottie.schema.json",
        "COPYING" to "schemas/LICENSE",
    )
    outputDir = layout.buildDirectory.dir("generated/npm/lottie-specs")
    tempDir = layout.buildDirectory.dir("tmp/npm")
}

sourceSets {
    main {
        resources.srcDir(fetchDotLottieWeb)
        resources.srcDir(fetchLottieSchema)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")

    intellijPlatform {
        intellijIdea(platformVersion)
        // Lottie .json files stay JSON files: the split editor, schema, structure view, inlays and color swatches build on it
        bundledPlugins("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    // Java 21 bytecode runs on every supported IDE (2026.1 ships a Java 21 runtime, 2026.2+ run Java 25). Code compiled against a
    // 2026.2+ IDE must therefore avoid the platform's inline Kotlin helpers, which are built for Java 25.
    jvmToolchain(21)
    compilerOptions {
        // Classes inherit the JVM default methods of platform interfaces instead of getting generated overrides for every one of
        // them: those would "override" deprecated and experimental platform methods the code never touches.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    // Kotlin only, no .form files: bytecode instrumentation (@NotNull checks, UI Designer) buys nothing.
    instrumentCode = false

    pluginConfiguration {
        name = "Lottie"
        version = pluginVersion

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = version.map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = pluginUntilBuild
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 0.1.0 -> default channel, 0.1.0-beta.1 -> "beta"
        channels = listOf(pluginVersion.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
    }

    pluginVerification {
        ides {
            // every supported IDE generation, so a break on any of them shows up in CI
            versionList("verifyIdeaVersions").get().forEach { create(IntelliJPlatformType.IntellijIdea, it) }
            versionList("verifyWebStormVersions").get().forEach { create(IntelliJPlatformType.WebStorm, it) }
            versionList("verifyRiderVersions").get().forEach {
                // Rider has no installer-based resolution (intellij-platform-gradle-plugin#1852)
                create(IntelliJPlatformType.Rider, it) { useInstaller = false }
            }
        }
    }
}

// `runIdeLatest`: the plugin in the newest IDEA the Plugin Verifier checks (the last of verifyIdeaVersions), next to `runIde`'s
// oldest supported one. Platform changes such as modules moving into separate plugins only show up when the plugin really runs there.
intellijPlatformTesting {
    runIde {
        register("runIdeLatest") {
            type = IntelliJPlatformType.IntellijIdea
            version = versionList("verifyIdeaVersions").map { it.last() }
        }
    }
}

changelog {
    groups.empty()
    // tags are plain versions (0.1.0), so compare/commit links in the changelog must not add the default "v" prefix
    versionPrefix = ""
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Publishing is only allowed for tagged (or explicitly versioned) builds, so a Marketplace upload can never carry a guessed version.
val checkPublishable = tasks.register("checkPublishable") {
    val source = pluginVersionSource
    val version = pluginVersion
    doLast {
        check(source != "derived") {
            "Refusing to publish $version: HEAD has no version tag. Publish a GitHub release (its tag becomes the version) " +
                "or pass -PpluginVersion. If the tag exists, make sure it was fetched (checkout with fetch-depth: 0)."
        }
    }
}

tasks {
    named("signPlugin") { dependsOn(checkPublishable) }
    named("publishPlugin") { dependsOn(checkPublishable) }

    wrapper {
        gradleVersion = "9.9.0"
    }

    // The licenses of the bundled player and schema require their notices to ship with the plugin
    processResources {
        from(files("LICENSE", "THIRD_PARTY_NOTICES.md")) {
            into("META-INF")
        }
    }

    test {
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
