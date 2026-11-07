// ── Root Project ────────────────────────────────────────────────────────
//
// Declares the plugins the subprojects use without applying any of them.
// Every version comes from the catalog so the app and the shared core cannot
// disagree about Kotlin.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
