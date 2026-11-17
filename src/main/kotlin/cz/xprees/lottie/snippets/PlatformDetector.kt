package cz.xprees.lottie.snippets

/** A platform the project looks like it targets, and the file that suggests it ([evidence], relative to the project). */
data class DetectedPlatform(val platform: CodePlatform, val evidence: String)

/**
 * Guesses which runtime a project plays animations with, from its build files: `package.json`, Gradle build and version catalog
 * files, `Package.swift` / `Package.resolved` / `Podfile`, and `pubspec.yaml`. A Lottie runtime among the dependencies counts most,
 * then the kind of project (a React app, an Android app with Compose, ...). The result is ranked, best first, without duplicates.
 */
object PlatformDetector {

    /** The build files to read: by file name, at the project root or up to [MAX_DEPTH] folders below it. */
    val FILE_NAMES = setOf(
        "package.json",
        "build.gradle", "build.gradle.kts", "libs.versions.toml",
        "Package.swift", "Package.resolved", "Podfile",
        "pubspec.yaml",
    )
    const val MAX_DEPTH = 3

    private class Rule(val score: Int, val platform: CodePlatform, val test: (String) -> Boolean)

    private fun has(vararg needles: String): (String) -> Boolean = { text -> needles.any { text.contains(it) } }
    private fun dependency(name: String): (String) -> Boolean = { text -> Regex("\"" + Regex.escape(name) + "\"\\s*:").containsMatchIn(text) }

    private val NPM = listOf(
        Rule(101, CodePlatform.DOTLOTTIE_REACT_NATIVE, dependency("@lottiefiles/dotlottie-react-native")),
        Rule(100, CodePlatform.DOTLOTTIE_REACT, dependency("@lottiefiles/dotlottie-react")),
        Rule(100, CodePlatform.DOTLOTTIE_VUE, dependency("@lottiefiles/dotlottie-vue")),
        Rule(100, CodePlatform.DOTLOTTIE_SVELTE, dependency("@lottiefiles/dotlottie-svelte")),
        Rule(100, CodePlatform.DOTLOTTIE_SOLID, dependency("@lottiefiles/dotlottie-solid")),
        Rule(100, CodePlatform.DOTLOTTIE_WEB_COMPONENT, dependency("@lottiefiles/dotlottie-wc")),
        Rule(100, CodePlatform.REACT_NATIVE, dependency("lottie-react-native")),
        Rule(95, CodePlatform.DOTLOTTIE_WEB, dependency("@lottiefiles/dotlottie-web")),
        Rule(90, CodePlatform.LOTTIE_WEB, has("\"lottie-web\"", "\"lottie-react\"", "\"react-lottie\"", "\"vue3-lottie\"", "\"ngx-lottie\"")),
        Rule(60, CodePlatform.DOTLOTTIE_REACT_NATIVE, dependency("react-native")),
        Rule(55, CodePlatform.DOTLOTTIE_VUE, dependency("vue")),
        Rule(55, CodePlatform.DOTLOTTIE_SVELTE, dependency("svelte")),
        Rule(55, CodePlatform.DOTLOTTIE_SOLID, dependency("solid-js")),
        Rule(50, CodePlatform.DOTLOTTIE_REACT, dependency("react")),
        Rule(30, CodePlatform.DOTLOTTIE_WEB) { true },
    )

    private val GRADLE = listOf(
        Rule(100, CodePlatform.DOTLOTTIE_ANDROID_COMPOSE) { it.contains("dotlottie-android") && compose(it) },
        Rule(98, CodePlatform.DOTLOTTIE_ANDROID_VIEW) { it.contains("dotlottie-android") },
        Rule(97, CodePlatform.LOTTIE_COMPOSE, has("lottie-compose")),
        Rule(96, CodePlatform.LOTTIE_ANDROID, has("com.airbnb.android:lottie", "group = \"com.airbnb.android\"", "module = \"com.airbnb.android:lottie\"")),
        Rule(45, CodePlatform.DOTLOTTIE_ANDROID_COMPOSE) { android(it) && compose(it) },
        Rule(40, CodePlatform.DOTLOTTIE_ANDROID_VIEW) { android(it) },
    )

    private val APPLE = listOf(
        Rule(100, CodePlatform.DOTLOTTIE_IOS, has("dotlottie-ios", "DotLottie'", "'DotLottie")),
        Rule(99, CodePlatform.LOTTIE_IOS, has("lottie-ios", "lottie-spm", "pod 'lottie-ios'")),
        Rule(40, CodePlatform.DOTLOTTIE_IOS) { true },
    )

    private val PUB = listOf(
        Rule(101, CodePlatform.DOTLOTTIE_FLUTTER, Regex("(?m)^\\s+dotlottie_flutter\\s*:")::containsMatchIn),
        Rule(100, CodePlatform.FLUTTER, Regex("(?m)^\\s+lottie\\s*:")::containsMatchIn),
        Rule(50, CodePlatform.DOTLOTTIE_FLUTTER, has("flutter:", "sdk: flutter")),
    )

    private fun compose(text: String) = text.contains("compose", ignoreCase = true)
    private fun android(text: String) = text.contains("com.android.application") || text.contains("com.android.library") ||
        text.contains("android {") || text.contains("androidx.")

    /** [files]: build files by path relative to the project (`/`-separated) to their text. */
    fun detect(files: Map<String, String>): List<DetectedPlatform> {
        val found = ArrayList<Pair<Int, DetectedPlatform>>()
        // Gradle projects spread dependencies over the root, module and catalog files: judge them together
        val gradle = files.filterKeys { name(it) in setOf("build.gradle", "build.gradle.kts", "libs.versions.toml") }
        if (gradle.isNotEmpty()) {
            val all = gradle.values.joinToString("\n")
            GRADLE.filter { it.test(all) }.forEach { rule -> found += rule.score to DetectedPlatform(rule.platform, evidence(gradle, rule)) }
        }
        for ((path, text) in files.toSortedMap(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })) {
            val rules = when (name(path)) {
                "package.json" -> NPM
                "Package.swift", "Package.resolved", "Podfile" -> APPLE
                "pubspec.yaml" -> PUB
                else -> continue
            }
            if (path.split('/').any { it == "node_modules" || it.startsWith(".") }) continue
            rules.filter { it.test(text) }.forEach { found += it.score to DetectedPlatform(it.platform, path) }
        }
        return found.sortedByDescending { it.first }.map { it.second }.distinctBy { it.platform }
    }

    /** The Gradle file that matched best: the one the rule matches on its own, else the first. */
    private fun evidence(files: Map<String, String>, rule: Rule): String =
        files.entries.firstOrNull { rule.test(it.value) }?.key ?: files.keys.sortedBy { it.count { c -> c == '/' } }.first()

    private fun name(path: String) = path.substringAfterLast('/')
}
