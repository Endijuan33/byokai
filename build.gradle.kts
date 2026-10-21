// Top-level build file for the BYOKAI project.
// Shared build logic lives in the version catalog (gradle/libs.versions.toml).
// NOTE: org.jetbrains.kotlin.android is intentionally absent: AGP 9.x provides
// built-in Kotlin support and applying that plugin is a build error.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
