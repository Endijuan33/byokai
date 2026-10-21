plugins {
    alias(libs.plugins.android.application)
    // NOTE: org.jetbrains.kotlin.android is intentionally NOT applied.
    // AGP 9.x ships built-in Kotlin support; applying the old plugin is an
    // error. The Kotlin toolchain version (2.4.21) is pinned by the
    // Compose compiler plugin below. See docs/BUILD.md.
    alias(libs.plugins.kotlin.compose)
    // kotlinx.serialization for provider API DTOs (Milestone 3C).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.byokai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.byokai"
        minSdk = 28
        targetSdk = 37
        versionCode = 4
        // Overridable from the release workflow via -Pbyokai.versionName=<x.y.z>.
        versionName = (findProperty("byokai.versionName") as String?) ?: "0.4.0"
        // Milestone 3B ships an instrumented test for the keystore-backed
        // credential path; runs on-device only (Phase 5 device matrix), not CI.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Stable debug keystore committed to the repo: every CI/local
        // build shares ONE debug signature, so debug APK updates install
        // cleanly over each other instead of conflicting
        // (INSTALL_FAILED_UPDATE_INCOMPATIBLE). This key is for debug
        // builds only and must NEVER be used for release signing.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "byokai-debug"
            keyAlias = "byokai-debug"
            keyPassword = "byokai-debug"
        }
        create("release") {
            // Release signing is driven exclusively by environment variables, which the
            // release workflow populates from GitHub Actions secrets. When the variables
            // are absent (local builds, PR/CI), the config stays incomplete and any
            // release build fails instead of silently falling back to the debug key.
            val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
            val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            if (!keystoreFile.isNullOrBlank()
                && !keystorePassword.isNullOrBlank()
                && !keyAlias.isNullOrBlank()
                && !keyPassword.isNullOrBlank()
            ) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 / minification is intentionally OFF in Phase 1 and lands in Phase 5
            // (security & compatibility) together with a full ProGuard rules review.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        // Explicit: the Settings About section reads BuildConfig.VERSION_NAME
        // and BuildConfig.APPLICATION_ID.
        buildConfig = true
    }
    lint {
        // Fail the build on lint errors; warnings stay informational in Phase 1.
        abortOnError = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val bom = libs.compose.bom
    implementation(platform(bom))
    androidTestImplementation(platform(bom))

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // Milestone 3B: Android Keystore + Tink encryption for API keys at rest.
    implementation(libs.tink.android)
    // Milestone 3C: HTTP transport (OkHttp 5.x) + JSON (kotlinx.serialization).
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    // Milestone 3E: hand-rolled SQLite driver for conversation history (no Room — no KSP for Kotlin 2.4.x).
    implementation(libs.sqlite.framework)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Scripted HTTP servers for provider adapter tests (no real network).
    testImplementation(libs.mockwebserver3)
    // JVM Tink for pure-JVM credential-store tests (no Android APIs).
    testImplementation(libs.tink)
    // Pure-JVM SQLite engine for migration tests (4A). Test-scoped only.
    testImplementation(libs.sqlite.jdbc)
    // Milestone 4B: Compose Material3 markdown rendering for AI answers.
    implementation(libs.markdown.renderer.m3)

    // Instrumented tests (keystore path): on-device only, not CI.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.junit)
}
