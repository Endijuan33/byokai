plugins {
    alias(libs.plugins.android.application)
    // NOTE: org.jetbrains.kotlin.android is intentionally NOT applied.
    // AGP 9.x ships built-in Kotlin support; applying the old plugin is an
    // error. The Kotlin toolchain version (2.4.21) is pinned by the
    // Compose compiler plugin below. See docs/BUILD.md.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.byokai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.byokai"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        // Overridable from the release workflow via -Pbyokai.versionName=<x.y.z>.
        versionName = (findProperty("byokai.versionName") as String?) ?: "0.2.0"
    }

    signingConfigs {
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
