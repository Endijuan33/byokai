# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

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

[Unreleased]: https://github.com/Endijuan33/byokai/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Endijuan33/byokai/releases/tag/v0.1.0
