# Build

GitHub Actions is the **sole** build/test/sign/release machine for BYOKAI.
Local builds are for fast iteration only and never count as validation.

## Workflows

| Workflow | Trigger | Purpose |
|---|---|---|
| `pr-validation.yml` | Pull request | assembleDebug, unit tests, lint, dependency review; uploads reports on failure |
| `ci.yml` | Push to `main` | assembleDebug, unit tests, lint; uploads debug APK + reports |
| `release.yml` | Manual dispatch | Signed release APK + AAB, apksigner verification, SHA-256, GitHub Release |

All actions are pinned to full semver tags (verified 2026-10-21):

| Action | Pinned version |
|---|---|
| `actions/checkout` | v7.0.1 |
| `actions/setup-java` | v6.1.0 |
| `android-actions/setup-android` | v4.0.4 |
| `gradle/actions/setup-gradle` | v6.4.0 |
| `actions/upload-artifact` | v7.0.2 |
| `actions/dependency-review-action` | v5.0.0 |
| `softprops/action-gh-release` | v3.0.3 |

## Toolchain

JDK 17 (Temurin) · Gradle 9.8.0 · AGP 9.4.1 · Kotlin 2.4.21 ·
compileSdk/targetSdk 37 · minSdk 28.

**Built-in Kotlin:** AGP 9.x compiles Kotlin without the legacy
`org.jetbrains.kotlin.android` plugin — applying it is a build error
([migration guide](https://developer.android.com/build/migrate-to-built-in-kotlin)).
The Kotlin toolchain version (2.4.21) is pinned by the version of the applied
`org.jetbrains.kotlin.plugin.compose` compiler plugin, which raises AGP's
KGP floor to match. Compiler options use the top-level
`kotlin { compilerOptions { } }` block; `jvmTarget` follows
`compileOptions.targetCompatibility` (17) unless overridden.

## Gradle wrapper note

This repository does **not** ship a Gradle wrapper yet: the project
scaffolding was created on a machine without a JVM, so the wrapper JAR could
not be generated. CI does not need it — workflows install Gradle 9.8.0 via
`gradle/actions/setup-gradle` (`gradle-version: "9.8.0"`) and invoke `gradle`
directly, with dependency caching handled by the action.

To generate the wrapper locally (one time, requires any JDK 17 + Gradle):

```bash
gradle wrapper --gradle-version 9.8.0 --distribution-type bin
```

then use `./gradlew` for all local commands. A follow-up commit adding the
wrapper is welcome once generated on a machine with a JVM.

## Reproducing CI locally

```bash
gradle assembleDebug --stacktrace        # debug APK
gradle testDebugUnitTest --stacktrace    # JVM unit tests
gradle lintDebug --stacktrace           # Android lint (fails on errors)
```

## Debug signing

Debug builds are signed with the stable `debug.keystore` at the repo
root (password/alias `byokai-debug`, committed intentionally). Every
CI and local build therefore shares one debug signature, so debug
APK updates install cleanly over each other instead of failing with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` (each fresh CI runner would
otherwise generate its own throwaway debug key). This key is for
debug builds only and must never be used for release signing; the
release key lives exclusively in GitHub Secrets (see
`docs/RELEASE.md`).

## Artifacts

- `ci.yml` uploads `byokai-debug-apk` (the debug APK, 30-day retention) and
  `ci-reports` (test + lint reports, 30-day retention).
- `pr-validation.yml` uploads `pr-validation-reports` only when the job fails
  (14-day retention).
- `release.yml` attaches the signed APK, AAB, and `checksums-sha256.txt` to
  the GitHub Release.
