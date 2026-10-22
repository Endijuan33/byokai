# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.7.0] — 2026-10-22

Phase 7: Release engineering. Everything preparable without the release
keystore is prepared and CI-validated; the first signed release itself is
the owner's one-time action (signing secrets still expected, not yet set).

### Added

- `scripts/check-apk-signer.sh`: release-signer guard that refuses APKs
  signed with the committed debug keystore. The Release workflow runs it
  after `apksigner verify --print-certs`; CI proves the guard fires on
  every push by running it against the deliberately debug-signed
  R8-validation APK and requiring it to fail.
- Release workflow: AAB signature verification (`jarsigner -verify`).
- Release workflow: version-discipline gate — the requested `versionName`
  must match the repository's `versionName`; bump the repo
  (`app/build.gradle.kts` + CHANGELOG) before running the workflow.
- `docs/RELEASE.md` rewritten: one-time keystore setup with fingerprint
  recording, secret storage, pre-release checklist, independent release
  verification (`apksigner` / `keytool -printcert` + checksums),
  install/update guidance (APK vs AAB), key recovery and rotation honesty.

### Changed

- Version bump 0.7.0 (versionCode 7).

## [0.6.0] — 2026-10-22

Phase 6: Security & Compatibility.

### Added

- R8 enabled for release builds (`isMinifyEnabled=true`,
  `isShrinkResources=true`) with rule-by-rule reviewed keep rules
  (`app/proguard-rules.pro`): kotlinx-serialization scoped to
  `com.byokai.**`, full keep for Tink (the published artifact ships
  no consumer ProGuard rules).
- `res/xml/network_security_config.xml`: cleartext forbidden platform-wide
  (defense in depth over `EndpointValidator`); no certificate pinning
  (BYOK custom endpoints must keep working).
- `res/xml/data_extraction_rules.xml` (API 31+): cloud backup excludes
  key material AND `byokai.db`; device-to-device transfer excludes key
  material only (conversations migrate with the user).
- `SecurityConfigTest`: 6 unit tests locking in the backup and network
  policy decisions (253 tests total).
- CI step validating the full R8 pipeline (`assembleRelease` signed
  with the committed debug keystore; validation artifact only — the
  real release gate is untouched).

### Changed

- Backup policy decided and documented: `byokai.db` is excluded from
  Auto Backup (conversation text is unencrypted at rest by design and
  users paste secrets into chats). Portability remains via explicit
  JSON export, which never contains credentials.
- Full documentation audit & sync: README, CHANGELOG, CONTRIBUTING,
  SECURITY, and all docs/* brought in line with the 0.6.0 codebase
  and phase history.

### Security

- Dependabot: 0 open alerts (verified).
- Credential audit: no `Log.*` in main source; error paths carry no
  key material; exports still contain no credentials (test-asserted).

## [0.5.0] — 2026-10-22

Phase 5: Reliability & Conversation Experience.

### Added

- `ProviderError.EmptyResponse`: a successful-but-empty completion is
  normalized by `ChatRepositoryImpl` to a typed error, so the UI shows
  a friendly message (EN/ID) instead of an empty bubble — and nothing
  empty is persisted.

### Fixed

- Double-tap/rapid double `send()` can no longer persist the user turn
  twice or start two streams: the send is now claimed synchronously
  (compare-and-set on `canSend`); failed claims return null and abort
  paths release the claim.
- `stop()` immediately followed by `send()` keeps database turn order:
  the partial-turn persist job is tracked and joined before the new
  user turn is written.
- Switching provider or model mid-stream now stops the stale stream
  first (partial text kept, persisted when a conversation is active)
  instead of silently continuing under the new selection.
- Code-block copy button (follow-up to 4B): the 0.4.0 note overstated
  this — the renderer's default code block never shows its header row
  (`showHeader` defaults to false, verified in 0.46.0 sources). 0.5.0
  wires custom code-block components that enable the header explicitly
  (language label + copy button).

### Changed

- `versionCode` 5, `versionName` 0.5.0.

### Security

- `docs/SECURITY.md` now states explicitly that export files are
  plaintext JSON and securing them is the user's responsibility.

### Changed

- Debug signing: committed a stable debug-only keystore
  (`debug.keystore`, documented in `docs/BUILD.md`) so every CI/local
  build shares one debug signature and debug APK updates install
  cleanly (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` fixed). Never for
  release signing.

## [0.4.0] — 2026-10-22

Phase 4: Advanced Chat Experience, Prompt Management & Data Portability
(milestones 4A–4E).

### Added

- Database migrations (4A): versioned, transactional migration
  framework (`DbHandle`/`DatabaseMigration`/`MigrationRunner` — pure
  Kotlin, JVM-tested against a real SQLite engine via
  `org.xerial:sqlite-jdbc:3.53.4.0`, test-scoped). Schema v2:
  `conversations.system_prompt` (nullable TEXT) + `prompts` table
  (id, title, content, is_builtin, created_at, updated_at). No
  destructive migrations.
- Markdown rendering (4B): assistant messages render via
  `com.mikepenz:multiplatform-markdown-renderer-m3-android:0.46.0`
  (headings, bold/italic, lists, inline code, code blocks with
  built-in copy button, tables). Raw HTML cannot execute (pure
  Compose renderer, no WebView; unhandled nodes degrade to plain
  text — verified from the 0.46.0 sources). Links open only on
  explicit tap and only for absolute http/https URLs (`LinkSafety`,
  JVM-tested); anything else is silently dropped. Streaming-safe:
  parsing is async and conflated per text change.
- Prompt management (4C): `PromptTemplate` domain +
  `SqlitePromptRepository` (CRUD, 4 editable built-in seeds inserted
  only when the table is empty), management screen (Settings →
  Prompts), and a per-conversation system prompt picker in the chat
  screen. The system prompt travels via each adapter's native
  channel (OpenAI system message, Anthropic `system` field, Gemini
  `systemInstruction`) — adapter tests assert it never leaks into
  user messages.
- Context management (4D): heuristic token ESTIMATE (labeled "est.",
  never an official count) shown subtly in chat; request-side
  truncation keeps the system prompt + newest messages within
  `contextWindow − 2048` reserve tokens, with conservative fallbacks
  (50 messages / ~128k chars) when the window is unknown
  (`ModelInfo.contextWindow`, currently null for all adapters).
  Persisted history is never modified. LLM summarization deferred.
- Export/import (4E): versioned JSON
  (`{"format":"byokai-export","version":1,…}`, documented in
  `docs/EXPORT.md`) via SAF CreateDocument/OpenDocument, 50 MB cap,
  corrupt files rejected with user-friendly errors, import
  confirmation when conversations exist, fresh UUIDs on import.
  Exports carry conversation text only — never credentials
  (asserted by test).

### Fixed

- `PromptRepository` missing `com.byokai.core.common.Result`
  import (resolved to `kotlin.Result`, breaking all overrides).

## [0.3.0] — 2026-10-22

Phase 3: BYOK core (milestones 3A–3E).

### Added

- Provider system: `AiProvider` adapter contract, `ProviderRegistry`
  (one adapter per `ProviderId`, duplicate registration fails fast),
  typed `ProviderError`s (Auth/RateLimited/Timeout/Network/
  InvalidModel/Server/Parse/Config), and `ProviderConfig` validation
  (HTTPS-only base URLs via `EndpointValidator`, non-empty model ids,
  `defaultModelId` ∈ `modelIds`). Configs carry no API key by design.
- Three streaming adapters: OpenAI (`OpenAiAdapter`, Chat Completions
  SSE with `data: [DONE]`; also serves OpenAI-compatible endpoints via
  a custom base URL), Anthropic (`AnthropicAdapter`, Messages API,
  `event:`-driven SSE, `max_tokens: 1024`, no model-listing endpoint —
  returns the config's known model ids), and Gemini (`GeminiAdapter`,
  `streamGenerateContent`, `x-goog-api-key`, `candidates[0].content.
  parts[].text`, EOF-terminated). All adapters: per-request endpoint
  re-validation, pre-Delta-only retries (max 2, exponential backoff +
  capped `Retry-After`), 401/403→Auth / 429→RateLimited / 404→
  InvalidModel normalization, and cancellation that kills the in-flight
  call.
- Encrypted credential storage (Tink AES-256-GCM + Android Keystore
  master key): isolated `byokai_credentials` DataStore file, config id
  as AEAD associated data, backup exclusions, wipe-and-recover on
  keystore invalidation; JVM-testable via `java.util.Base64`.
- Providers screen: add/edit/delete provider configs, save and test
  API keys; typed keystrokes stay in the composable until the moment of
  the action, never in ViewModel state. Connection tests are cheap
  (`GET /v1/models` or `/v1beta/models`) except Anthropic, which needs
  a minimal real `max_tokens: 1` model call (documented micro-cost).
- Streaming chat UI: config/model selectors (only configs with a saved
  key selectable), live message bubbles, Stop while generating, error
  banner with Retry, honest empty states.
- Conversation history: hand-rolled SQLite (`byokai.db`, schema v1,
  FK cascade) via `ConversationRepository`; conversations screen with
  rename and delete (with confirmation); chat persists turns per
  conversation. `byokai.db` holds conversation text only — never API
  keys.
- Documentation: rewritten `docs/PROVIDERS.md` (auth schemes,
  endpoints, wire formats, error tables, capability flags, honest
  limits), extended `docs/SECURITY.md` (network rules, retry/
  cancellation, database boundary, prominent caveat), updated
  `docs/ARCHITECTURE.md` / `docs/ROADMAP.md` / `docs/TESTING.md`.

### Changed

- `versionCode` 3, `versionName` 0.3.0.

### Notes

- Adapter tests are MockWebServer contract tests — no real provider
  endpoints or real API keys are ever touched in tests or CI. Live
  validation with the user's own key is a device-side step and has not
  happened in this repository.
- Vision and tool calling are not implemented (capability flags are
  false); the UI never offers them.
- The on-device Keystore test (`src/androidTest`) is written but does
  not run in CI (Phase 5 device matrix).

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
  icons-core + icons-extended (BOM-managed; verified against the real
  artifacts that `ChatBubbleOutline` only ships in -extended),
  DataStore Preferences 1.2.1 now wired in via the `preferencesDataStore`
  delegate (`PreferenceDataStoreFactory` was removed in DataStore 1.2.x).

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

[0.6.0]: https://github.com/Endijuan33/byokai/releases/tag/v0.6.0
[0.5.0]: https://github.com/Endijuan33/byokai/releases/tag/v0.5.0
[0.4.0]: https://github.com/Endijuan33/byokai/releases/tag/v0.4.0
[0.3.0]: https://github.com/Endijuan33/byokai/releases/tag/v0.3.0
[0.2.0]: https://github.com/Endijuan33/byokai/releases/tag/v0.2.0
[0.1.0]: https://github.com/Endijuan33/byokai/releases/tag/v0.1.0
