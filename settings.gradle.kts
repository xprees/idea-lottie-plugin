import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "lottie"

// Maven Central is read through Google's mirror first: repo.maven.apache.org rate-limits shared CI runners (HTTP 429), which
// failed whole CI runs on a cold Gradle cache. The mirror serves the same artifacts; Maven Central stays as the fallback.
val mavenCentralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.20.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        maven(mavenCentralMirror)
        mavenCentral()

        intellijPlatform {
            defaultRepositories()
        }
    }
}
