# Roadmap

Phase plan with honest statuses. A phase is *done* only when its acceptance
criteria are met **and** validated by CI — never on code existing alone.

## Phase 1 — Repository and CI foundation — IN PROGRESS

Acceptance:

- [x] Repository created (`Endijuan33/byokai`, public).
- [x] Gradle/Kotlin/SDK configuration with fully verified versions.
- [x] Minimal app scaffold that compiles (`com.byokai`, minSdk 28).
- [x] Real unit test that passes in CI.
- [x] Workflows: PR validation, main CI (debug APK artifact), manual release.
- [x] Baseline docs set.
- [ ] CI green on the initial commit (pending at time of writing).
- [ ] Branch protection enabled on `main`.

## Phase 2 — Application foundation — NOT STARTED

UI shell and navigation, Material 3 theme polish, local database schema
design, app settings, domain models + repository layer, error handling and
state management. Acceptance: navigable app shell with settings persistence,
instrumented test harness running in CI.

## Phase 3 — BYOK core — NOT STARTED

Provider registry, `ProviderAdapter` interface + first adapters, encrypted
credential storage (Keystore + Tink), model selector, streaming chat,
conversation history, error/timeout/retry/cancellation handling.
Acceptance: real streamed chat against at least two providers with the user's
own key; adapter contract tests green; credential redaction tests green.

## Phase 4 — Advanced AI features — NOT STARTED

Markdown/code rendering, prompt management, vision/file input where
supported, tool calling with user confirmation, agent orchestration,
context management, export/import. Acceptance: each shipped feature covered
by tests; unimplemented features clearly marked as such in UI.

## Phase 5 — Security and compatibility — NOT STARTED

Network/storage audit, permission review, API-level behavior matrix
(28–37), R8/minification enablement with reviewed ProGuard rules,
dependency security audit, database migration tests, privacy review.
Acceptance: documented test matrix with real device/emulator results.

## Phase 6 — Release engineering — NOT STARTED

Release signing operational (secrets configured), APK signature validation,
AAB publishing, changelog/versioning discipline, GitHub Releases, install
and key-recovery documentation. Acceptance: a signed release installable
and updatable on a real device.

## Phase 7 — Continuous improvement — NOT STARTED

Issue triage, regression fixes, controlled dependency updates, periodic
quality/security audits.

## Deferred technical items

- **Hilt (DI):** deferred. Hilt 2.60.1 exists, but KSP — required for its
  annotation processing — has no release for Kotlin 2.4.x (latest on Maven
  Central is 2.3.12, verified 2026-10-21). Reintroduce when KSP publishes a
  2.4.x-compatible version; until then, manual constructor injection.
- **Room (local database):** deferred for the same KSP reason (latest Room
  2.8.5 needs KSP). Conversation history persistence waits on this.
- **R8/minification:** intentionally off until Phase 5.

## Version decisions (all verified 2026-10-21)

| Artifact | Version | Source |
|---|---|---|
| AGP | 9.4.1 | developer.android.com release notes |
| Kotlin | 2.4.21 | kotlinlang.org releases |
| Gradle | 9.8.0 | gradle.org releases |
| JDK | 17 (Temurin) | actions/setup-java |
| compileSdk / targetSdk | 37 | Android 17, stable Jun 2026 |
| minSdk | 28 | Android 9 |
| Compose BOM | 2026.09.00 | dl.google.com maven-metadata |
| activity-compose | 1.13.0 | dl.google.com maven-metadata |
| lifecycle (runtime/viewmodel compose) | 2.11.0 | dl.google.com maven-metadata |
| navigation-compose | 2.10.2 | dl.google.com maven-metadata |
| core-ktx | 1.19.1 | dl.google.com maven-metadata |
| kotlinx-serialization-json | 1.12.0 | repo1.maven.org maven-metadata |
| okhttp | 5.5.0 | repo1.maven.org maven-metadata |
| tink-android | 1.24.0 | repo1.maven.org maven-metadata |
| datastore-preferences | 1.2.1 | dl.google.com maven-metadata |
| junit | 4.13.2 | repo1.maven.org maven-metadata |
