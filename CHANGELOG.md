# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

## [0.2.0] — 2026-10-21

Phase 2: application foundation.

### Added

- Navigation graph with `home` (conversation list with an honest empty
  state — no fake items, no dead buttons) and `settings` destinations;
  settings is reachable from the home TopAppBar.
- Fully functional Settings screen backed by DataStore Preferences: theme
  mode (System/Light/Dark, applies immediately), Dynamic Color toggle
  (applies immediately), language (English/Indonesia, persists; the
  activity recreates so the new locale takes effect), and an About section
  showing the real `BuildConfig.VERSION_NAME` and
  `BuildConfig.APPLICATION_ID`.
- Per-app language applied via `MainActivity.attachBaseContext` locale
  wrapping. The DataStore read there is synchronous by necessity with a
  2-second timeout and a default fallback — tradeoff documented in
  docs/ARCHITECTURE.md.
- Layered architecture: `domain.model` (`ThemeMode`, `AppLanguage` enums
  with storage-key mapping), `domain.repository.SettingsRepository`
  interface, `data.local.datastore.SettingsDataStore` (+ process-wide
  holder shared with the startup locale path),
  `data.repository.SettingsRepositoryImpl`, `core.common` (`Result`,
  `UiText` for error handling without holding a Context).
- Manual DI via `di.AppContainer` (lazy singletons) as interim until Hilt
  returns with KSP for Kotlin 2.4.x.
- `SettingsViewModel` (`StateFlow<SettingsUiState>` + error `Channel`
  surfaced to a Snackbar; real DataStore IO failures reach the user).
- Full localization: `values/strings.xml` (English, default) and
  `values-in/strings.xml` (Indonesian); no hardcoded UI text.
- New unit tests: enum storage-key round-trips (`ThemeModeTest`,
  `AppLanguageTest`), `SettingsViewModel` with a fake repository (state
  updates + error forwarding), and DataStore integration tests against a
  real preferences file (`SettingsDataStoreTest`). `VersionInfoTest`
  (Phase 1) still green.
- Dependencies (all stable, verified 2026-10-21): kotlinx-coroutines
  1.11.0 (core/android/test, repo1.maven.org), Compose Material
  icons-core (BOM-managed), DataStore Preferences 1.2.1 now wired in.

### Changed

- `versionCode` 2, `versionName` 0.2.0.

## [0.1.0] — 2026-10-21

Phase 1: repository and CI foundation.

### Added

- Android application scaffold (`com.byokai`, minSdk 28, targetSdk/compileSdk 37)
  with a single-activity Compose shell, Material 3 theme (Dynamic Color,
  light/dark), and a single-route navigation graph.
- Honest placeholder home screen stating the project phase; no fake features.
- Original launcher icon (key mark) as an adaptive-icon vector.
- Version catalog (`gradle/libs.versions.toml`) with fully verified versions:
  AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.0, JDK 17, Compose BOM 2026.09.00,
  activity-compose 1.13.0, lifecycle 2.11.0, navigation-compose 2.10.2,
  core-ktx 1.19.1, kotlinx-serialization-json 1.12.0, OkHttp 5.5.0,
  Tink 1.24.0, DataStore 1.2.1, JUnit 4.13.2.
- Pure-Kotlin `VersionInfo` helper with JVM unit tests (`VersionInfoTest`).
- GitHub Actions workflows: PR validation (build/test/lint/dependency review),
  main CI (build/test/lint + debug APK artifact), and manual signed-release
  workflow (secret-gated, apksigner-verified, SHA-256 checksums).
- Dependabot weekly updates for Gradle dependencies and GitHub Actions.
- Documentation set: README, CONTRIBUTING, SECURITY, and docs/
  (ARCHITECTURE, BUILD, RELEASE, PROVIDERS, TESTING, ROADMAP).

### Notes

- Hilt and Room are deferred: KSP has no release for Kotlin 2.4.x yet
  (latest is 2.3.12), so they cannot run annotation processing in this
  toolchain. See docs/ROADMAP.md.
- R8/minification is deferred to Phase 5.

[Unreleased]: https://github.com/Endijuan33/byokai/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Endijuan33/byokai/releases/tag/v0.2.0
[0.1.0]: https://github.com/Endijuan33/byokai/releases/tag/v0.1.0
