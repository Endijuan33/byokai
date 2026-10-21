# Roadmap

Phase plan with honest statuses. A phase is *done* only when its acceptance
criteria are met **and** validated by CI — never on code existing alone.

## Phase 1 — Repository and CI foundation — COMPLETED (2026-10-21)

Acceptance:

- [x] Repository created (`Endijuan33/byokai`, public).
- [x] Gradle/Kotlin/SDK configuration with fully verified versions.
- [x] Minimal app scaffold that compiles (`com.byokai`, minSdk 28).
- [x] Real unit test that passes in CI.
- [x] Workflows: PR validation, main CI (debug APK artifact), manual release.
- [x] Baseline docs set.
- [x] CI green (run 39615455479, `completed/success`, verified via API).
- [x] Branch protection enabled on `main` (PR + required checks + review).

## Phase 2 — Application foundation — COMPLETED (2026-10-21)

Acceptance:

- [x] Navigable app shell: `home` + `settings` destinations in the NavHost.
- [x] Fully functional Settings screen (theme mode, Dynamic Color,
      language, About) backed by DataStore Preferences.
- [x] Domain/data layering: `domain.model`, `domain.repository`,
      `data.local.datastore`, `data.repository`, `core.common`, `core.ui`.
- [x] Manual DI via `di.AppContainer` (interim until Hilt/KSP).
- [x] Error handling: `Result`/`UiText`; real IO failures reach a Snackbar.
- [x] Full en/id localization; no hardcoded UI strings.
- [x] Real unit tests green in CI (enum round-trips, ViewModel + fake,
      DataStore integration); Phase 1 tests still green.
- [x] `versionCode` 2 / `versionName` 0.2.0; CHANGELOG + docs updated.
- [x] CI green on the Phase 2 commit.

Note: conversation persistence is hand-rolled SQLite via
`androidx.sqlite:sqlite-framework:2.7.1` (Phase 3, Milestone 3E) — Room
stays deferred on KSP, but persistence no longer waits on it.

## Phase 3 — BYOK core — IN PROGRESS (milestones 3A–3E complete, 3F in progress)

Provider registry, `ProviderAdapter` interface + first adapters, encrypted
credential storage (Keystore + Tink), model selector, streaming chat,
conversation history, error/timeout/retry/cancellation handling.
Acceptance: real streamed chat against at least two providers with the user's
own key; adapter contract tests green; credential redaction tests green.

Milestones (all CI green at their merge commits):

- [x] **3A** — provider domain foundation: `AiProvider` contract,
  `ProviderRegistry`, `ProviderConfig`/`ProviderCapabilities`/`ProviderError`,
  `EndpointValidator`.
- [x] **3B** — encrypted credential storage: Tink AES-256-GCM + Android
  Keystore master key, backup-excluded store, key-invalidation wipe path.
- [x] **3C** — OpenAI adapter (Chat Completions SSE), streaming
  `ChatRepository`, providers screen (add/edit/delete/test configs) and
  streaming chat UI.
- [x] **3D** — Anthropic + Gemini adapters with MockWebServer contract
  tests; registry wiring.
- [x] **3E** — hand-rolled SQLite persistence (`byokai.db`, schema v1),
  `ConversationRepository`, conversation history UI.
- [ ] **3F** — documentation + version bump (in progress): provider docs,
  security/network docs, architecture/roadmap/testing docs, CHANGELOG,
  `versionCode` 3 / `versionName` 0.3.0.

Remaining acceptance gap: streamed chat has been validated against
scripted MockWebServer fixtures, not yet against the real provider
endpoints with the user's own key — that is a device-side validation,
explicitly not done in CI (no real keys in this repository, ever).

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
  2.8.5 needs KSP). Phase 3 ships conversation persistence anyway, as
  hand-rolled SQLite (`androidx.sqlite:sqlite-framework:2.7.1`); Room
  stays the long-term option once KSP supports Kotlin 2.4.x.
- **R8/minification:** intentionally off until Phase 5.

## Version decisions (all verified 2026-10-21 unless marked otherwise)

| Artifact | Version | Source |
|---|---|---|
| AGP | 9.4.1 | developer.android.com release notes |
| Kotlin | 2.4.21 | kotlinlang.org releases; pinned via the applied `org.jetbrains.kotlin.plugin.compose` plugin version (AGP 9.x built-in Kotlin — the legacy `kotlin-android` plugin is not applied) |
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
| kotlinx-coroutines (core/android/test) | 1.11.0 | repo1.maven.org maven-metadata |
| mockwebserver3 | 5.5.0 | repo1.maven.org maven-metadata (verified 2026-10-22) |
| androidx.sqlite sqlite-framework | 2.7.1 | dl.google.com maven-metadata (verified 2026-10-22) |
| compose material-icons-core | BOM-managed | Compose BOM 2026.09.00 |
| junit | 4.13.2 | repo1.maven.org maven-metadata |
