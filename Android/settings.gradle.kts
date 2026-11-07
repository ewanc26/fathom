// ── Gradle Settings ─────────────────────────────────────────────────────
//
// Android is the only Gradle root, exactly as in Inkwell. `:shared` is a
// Kotlin Multiplatform module that lives beside this directory and is
// consumed by Android directly and by iOS as an XCFramework.
//
// The foojay toolchain resolver is deliberately absent: the build takes the
// JVM it is launched with, and pinning a toolchain download into a project
// this small would add a network dependency to every fresh checkout.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fathom-android"
include(":app")
include(":shared")

project(":shared").projectDir = java.io.File(rootDir, "../shared")
