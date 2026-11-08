// ── App Module ──────────────────────────────────────────────────────────
//
// The dashboard itself. All of the arithmetic, record models and JSON
// decoding come from :shared; nothing in this module should decide what a
// figure means, only how it is drawn.

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The shared version source. iOS mirrors these values through
// scripts/sync-version.py; Android reads them directly so it cannot drift.
val versionPropertiesFile = rootProject.file("../version.properties")
check(versionPropertiesFile.isFile) {
    "Missing ${versionPropertiesFile.path}; run from a complete checkout."
}
val versionProperties = Properties().apply {
    versionPropertiesFile.inputStream().use { load(it) }
}
val appVersionName = versionProperties.getProperty("versionName")
    ?.takeIf { it.isNotBlank() }
    ?: error("versionName is missing from ${versionPropertiesFile.path}")
val appVersionCode = versionProperties.getProperty("versionCode")
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: error("versionCode must be a positive integer in ${versionPropertiesFile.path}")

android {
    namespace = "uk.ewancroft.fathom"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.ewancroft.fathom"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Side-by-side install with a release build.
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":shared"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
