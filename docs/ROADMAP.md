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

## Phase 3 — BYOK core — COMPLETED (2026-10-22, v0.3.0)

Provider registry, `ProviderAdapter` interface + first adapters, encrypted
credential storage (Keystore + Tink), model selector, streaming chat,
conversation history, error/timeout/retry/cancellation handling.
Acceptance: streamed chat against real provider endpoints with the user's
own key — **met 2026-10-22 on the owner's device** (Gemini live,
`gemini-3.6-flash`; OpenAI and Anthropic remain validated against
MockWebServer fixtures only — see "Validation honesty" below); adapter
contract tests green; credential redaction tests green.

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
- [x] **3F** — documentation + version bump: provider docs,
  security/network docs, architecture/roadmap/testing docs, CHANGELOG,
  `versionCode` 3 / `versionName` 0.3.0.

## Phase 4 — Advanced Chat Experience, Prompt Management & Data Portability — SHIPPED (v0.4.0, 2026-10-22)

- **4A database migration**: versioned, transactional migration
  framework; schema v2 (`conversations.system_prompt`, `prompts`
  table); JVM migration tests on a real SQLite engine.
- **4B markdown rendering**: assistant messages via mikepenz
  renderer-m3 0.46.0 (headings, lists, code blocks + copy, tables,
  safe links); raw HTML cannot execute; streaming-safe.
- **4C prompt management**: template CRUD + 4 editable builtins,
  management screen, per-conversation system prompt via each
  adapter's native channel (wire-tested per provider).
- **4D context management**: heuristic token ESTIMATE ("est." label),
  request-side truncation (system prompt + newest within
  contextWindow − 2048; fallback 50 msgs / ~128k chars); history
  never modified; LLM summarization deferred.
- **4E export/import**: versioned JSON (`docs/EXPORT.md`), SAF,
  50 MB cap, import confirmation, fresh UUIDs, no credentials.
- Not shipped (remain future work): vision/file input, tool calling,
  agent orchestration.

## Phase 5 — Reliability & Conversation Experience — SHIPPED (0.5.0)

Reliability and error handling (synchronous send claim against
double-tap duplicates, empty-response error, no silent request
repetition, cancellation that really stops, loading/error states that
always recover), conversation experience (turn-order-safe stop/send
sequencing, mid-stream provider/model switch stops the stale stream,
retry-after-failure kept), context/token audit (4D policy verified:
system prompt + newest messages prioritized, "est." labeling kept
honest, conservative fallback retained — per-model context windows
deliberately not hardcoded: providers don't report them and tables go
stale), security/privacy audit (no credential in logs/errors/exports;
export files documented as plaintext JSON, user-responsible).
Acceptance: build + full unit suite green, no known Phase 4
regressions, manual device checklist in the Phase 5 report.

## Validation honesty (read before claiming a phase is done)

Two different kinds of validation exist in this project and they are not
interchangeable:

- **Unit-tested (CI)** — JVM unit tests, lint, dependency review. All
  phases are validated this way (253 unit tests green at 0.6.0).
- **Device-tested (owner's own phone, own keys)** — manual checklists the
  owner runs on his own device. Completed: **Phase 4** (copy button,
  Prompts UI, link taps, system prompt per conversation, export/import)
  and **Phase 5** (double-tap send, stop→send, mid-stream provider/model
  switch, airplane mode, retry after failure, force-stop) — all passed
  2026-10-22. **Gemini was tested live on-device with the owner's key**
  (`gemini-3.6-flash`); OpenAI and Anthropic remain validated against
  MockWebServer fixtures only. No real keys ever enter this repository
  or CI.

## Phase 6 — Security and compatibility — SHIPPED (0.6.0)

Documentation audit and sync (README, CHANGELOG, all docs/* vs. code),
Dependabot review (0 open alerts), R8/minification enabled for release
with reviewed ProGuard rules (CI validates the R8 pipeline via
`assembleRelease` signed with the debug keystore — validation artifact
only), backup policy decided (`byokai.db` excluded from Auto Backup;
D2D transfer keeps conversations, drops key material), network
security config (cleartext forbidden, no pinning), dependency audit
(all pins current except datastore-preferences 1.3.0, deliberately not
upgraded), API 28–37 audit (no unguarded newer-API calls; lint NewApi
green; device-only behaviors honestly listed).

## Phase 7 — Release engineering — NOT STARTED

Release signing operational (the four signing secrets configured — they
are still expected, not yet set), APK signature validation, AAB
publishing, changelog/versioning discipline, GitHub Releases, install
and key-recovery documentation. Acceptance: a signed release installable
and updatable on a real device.

## Phase 8 — Continuous improvement — NOT STARTED

Issue triage, regression fixes, controlled dependency updates, periodic
quality/security audits.

### Uncommitted future candidates (not promised, not scheduled)

- Compatible-endpoint adapters (DeepSeek, xAI, OpenRouter) once the
  foundation is stable — the `OPENAI_COMPATIBLE` provider id exists for
  this, but no adapter work has started.
- Automatic context summarization (currently: heuristic estimate +
  request-side truncation only).
- Encrypted conversation history (optional; the DB is deliberately
  unencrypted today).
- Instrumented on-device Keystore test in the CI matrix (currently
  device-only).

## Deferred technical items

- **Hilt (DI):** deferred. Hilt 2.60.1 exists, but KSP — required for its
  annotation processing — has no release for Kotlin 2.4.x (latest on Maven
  Central is 2.3.12, verified 2026-10-21). Reintroduce when KSP publishes a
  2.4.x-compatible version; until then, manual constructor injection.
- **Room (local database):** deferred for the same KSP reason (latest Room
  2.8.5 needs KSP). Phase 3 ships conversation persistence anyway, as
  hand-rolled SQLite (`androidx.sqlite:sqlite-framework:2.7.1`); Room
  stays the long-term option once KSP supports Kotlin 2.4.x.
- **R8/minification:** enabled for release builds in Phase 6 (0.6.0)
  with reviewed ProGuard rules; CI validates the R8 pipeline on every
  push.

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
| mikepenz multiplatform-markdown-renderer-m3-android | 0.46.0 | repo1.maven.org maven-metadata (verified 2026-10-22, Phase 4B) |
| org.xerial sqlite-jdbc (test-scoped) | 3.53.4.0 | repo1.maven.org maven-metadata (verified 2026-10-22, Phase 4A) |
| compose material-icons-core | BOM-managed | Compose BOM 2026.09.00 |
| junit | 4.13.2 | repo1.maven.org maven-metadata |
