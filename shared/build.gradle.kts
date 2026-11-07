// ── Shared Core — Kotlin Multiplatform Module ───────────────────────────
//
// Everything in here is pure: record models, tolerant JSON decoding, XRPC
// URL construction and the statistics aggregation. No HTTP, no storage, no
// platform types. Android consumes it as a Gradle module; iOS consumes the
// framework this module produces.
//
// The reason the boundary is drawn here rather than at "models only" is that
// the dashboard's arithmetic is the part most likely to drift between the two
// apps, and a figure that disagrees with itself on two phones is worse than
// one that is merely wrong.

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "uk.ewancroft.fathom.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        // Warnings are not errors here, but a shared module is small enough
        // that a new one is always worth looking at.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Both JVM-consuming targets must match :app, which compiles against
    // JVM 17 and cannot inline bytecode built for a newer target. Left to
    // the environment they follow the JDK on PATH (21 on CI), and
    // :app:compileDebugKotlin then fails on every inline function here.
    // jvmTarget is a target-level option, so it is set inside each target
    // rather than in the common compilerOptions block above.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "FathomShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// XCFramework assembly lives in scripts/build-xcframework.sh, which links the
// slices and combines them with xcodebuild. It is deliberately not a Gradle
// task: there would then be two ways to produce the same artefact, and only
// one of them would be the one CI and the docs use.
