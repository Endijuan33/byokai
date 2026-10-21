# BYOKAI ProGuard / R8 rules (Phase 6: security & compatibility).
#
# Minification is enabled for the release build type only
# (app/build.gradle.kts). These rules were reviewed rule-by-rule for the
# libraries BYOKAI ships. Everything else follows R8's defaults plus the
# default `proguard-android-optimize.txt` and each library's own consumer
# rules (AndroidX libraries such as sqlite-framework and the Compose
# artifacts merge theirs automatically — no app-level rules needed).

# Keep source file names and line numbers so release stack traces stay
# actionable. Obfuscation of identifiers still applies.
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# kotlinx.serialization (1.12.0)
#
# All @Serializable classes live under com.byokai.** (provider DTOs,
# ProviderId/ProviderConfig, ByokaiExport). No SerializersModule /
# polymorphic serialization is used — only compiler-generated serializers
# reached via Companion.serializer(). These are the documented rules from
# the kotlinx.serialization project, scoped to our package.
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# JsonObjectSerializer's INSTANCE is looked up reflectively by the JSON
# parser in some paths; keep it to avoid NoClassDefFoundError under R8.
-keepclassmembers,allowshrinking class kotlinx.serialization.json.JsonObjectSerializer {
    public static final kotlinx.serialization.json.JsonObjectSerializer INSTANCE;
}

# Keep `Companion` objects of serializable classes: the generated
# `serializer()` lives there.
-keepclassmembers class com.byokai.** {
    *** Companion;
}

# Keep the generated `serializer(...)` methods so R8 neither strips nor
# renames the entry point the serialization runtime reflects on.
-keepclasseswithmembernames class com.byokai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Tink (tink-android 1.24.0)
#
# Tink resolves key types, primitives, and key managers via reflection at
# runtime. The published artifact ships no consumer ProGuard rules of its
# own (verified: META-INF/proguard/ in tink-android-1.24.0.jar contains
# only protobuf.pro), so the whole package is kept per Tink's Android
# setup guidance. Shrinking Tink aggressively is not safe: a stripped
# key type breaks keyset decryption and locks the user out of their
# stored API keys.
# ---------------------------------------------------------------------------
-keep class com.google.crypto.tink.** { *; }

# ---------------------------------------------------------------------------
# Deliberately absent
#
# - androidx.sqlite / sqlite-framework, DataStore, OkHttp, Compose,
#   lifecycle/navigation/activity: these ship their own consumer rules in
#   the AAR; R8 merges them automatically.
# - com.byokai.BuildConfig: VERSION_NAME / APPLICATION_ID are compile-time
#   constants inlined at usage sites; no runtime reflection touches them.
# - No -dontwarn suppressions: warnings fail loudly so a dependency
#   upgrade that breaks the R8 pipeline is caught in CI
#   (assembleRelease validation step in .github/workflows/ci.yml).
# ---------------------------------------------------------------------------
