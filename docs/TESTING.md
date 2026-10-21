# Testing strategy

## Principles

- GitHub Actions is the only validation that counts. Local test runs are for
  iteration.
- Tests must be deterministic: no network, no wall-clock dependence, no
  order dependence.
- A failing test is fixed or the code is fixed — tests, lint, and security
  checks are never disabled to turn the pipeline green.

## Strategy (Phase 3): mock-based, no real endpoints

The provider system is tested entirely against fakes and scripted servers —
**no real API key is ever used in tests**, not locally, not in CI:

- **Adapter contract tests** (`data.provider.openai/anthropic/gemini`): each
  adapter is exercised against a MockWebServer 5.5.0 instance with scripted
  SSE responses per adapter (OpenAI `data:` chunks + `[DONE]`; Anthropic
  `event: content_block_delta`/`message_stop`/`error`; Gemini
  candidate-parts chunks + EOF). Tests cover streaming assembly, Delta →
  Done ordering, cancellation mid-stream, timeouts, retries (pre-Delta
  only), auth failures, rate limits, invalid models, and model-listing
  parse paths. No test touches a real provider endpoint.
- **Core network tests**: `SseReaderTest` (framing, comments, trailing
  event, byte cap), `RetryPolicyTest` (retryable classes, attempt limits,
  backoff math), `EndpointValidatorTest` (HTTPS gate, loopback HTTP,
  userinfo/metadata rejection).
- **Security tests**: `TinkCredentialStoreTest` runs **real AES-256-GCM**
  encryption with the JVM Tink artifact (`com.google.crypto.tink:tink`
  1.24.0) against an ephemeral keyset — ciphertext round-trips,
  associated-data binding, blank-key rejection, wipe on key
  invalidation. JVM-only by design.
- **Fake-repository ViewModel tests**: `ChatViewModelTest`/`ChatPersistenceTest`
  (`ui.chat.ChatFakes`), `ProvidersViewModelTest`
  (`ui.providers.ProviderFakes`), `ConversationsViewModelTest`
  (`ui.conversations.ConversationFakes`), plus the older
  `FakeSettingsRepository`. Standard coroutines test machinery
  (`kotlinx-coroutines-test` 1.11.0): `StandardTestDispatcher` +
  `runTest`, so timing is virtual and deterministic.
- **Data-layer tests**: `SettingsDataStoreTest` and
  `DataStoreCredentialStorageTest` / `DataStoreProviderConfigRepositoryTest`
  run against real Preferences DataStore files on the JVM
  (DataStore works on the plain JVM; `java.util.Base64` is used in
  production code so these tests are honest JVM tests, not Android mocks).

## What is NOT tested (stated honestly)

- **Real API keys / real provider endpoints** — never, by rule. Behavior
  against live servers is validated only with the user's own key on
  their own device; it cannot happen in CI or in this repository.
- **Real SQLite on the JVM** — `SqliteConversationRepository` and
  `ByokaiDatabase` are Android-only (they need a `Context` and the
  `sqlite-framework` driver); they are not instantiated in JVM unit
  tests. The `ConversationRepository` contract they implement is covered
  through the in-memory fake in `ConversationsViewModelTest`.
- **On-device Keystore** — `src/androidTest` ships
  `TinkCredentialStoreKeystoreTest` for the keystore-backed path, but it
  requires a real device/emulator and **does not run in CI** (Phase 5
  device matrix). The JVM Tink tests above cover the encryption logic;
  the Keystore binding is documented but untested until a device run.
- Instrumented UI/screenshot tests — planned (Phase 4).

## Layers

| Layer | Tool | Status |
|---|---|---|
| JVM unit tests | JUnit 4 (`gradle testDebugUnitTest`) | Active (Phases 1–3) |
| Android lint | `gradle lintDebug`, `abortOnError = true` | Active (Phase 1) |
| Dependency review | `dependency-review-action` on PRs | Active (Phase 1) |
| Dependabot | Weekly Gradle + Actions updates | Active (Phase 1) |
| Instrumented tests | `connectedDebugAndroidTest` | Deferred — Phase 5 device matrix |
| Screenshot tests | Planned | Planned (Phase 4) |

## Current coverage (Phase 3)

- `VersionInfoTest` (Phase 1) — still green.
- Phase 2: enum storage-key round-trips, `SettingsViewModel` + fake,
  DataStore integration tests — still green.
- Phase 3: per-adapter MockWebServer streaming/error/retry/cancellation
  tests; `SseReader`/`RetryPolicy`/`EndpointValidator` unit tests;
  JVM Tink credential-store tests; provider-config repository tests;
  `ChatViewModel`/`ChatPersistence`/`ProvidersViewModel`/
  `ConversationsViewModel` tests with fakes. All green in CI at the
  Phase 3E merge commit.

## Required coverage as features land

- Provider config parsing and validation (per adapter).
- Credential redaction in logs.
- Streaming response assembly, partial responses, and cancellation.
- Timeout, retry, auth-failure, and rate-limit handling.
- Model/provider switching mid-app.
- Conversation persistence, rename, search, archive, delete.
- Database migrations (once the schema moves past v1).
- Tool-calling argument handling and user-confirmation gates.
- Offline / connection-loss behavior.

## Running tests

```bash
gradle testDebugUnitTest --stacktrace   # unit tests (JVM, no device needed)
gradle lintDebug --stacktrace          # lint, fails the build on errors
```

For the module command in this repo:

```bash
./gradlew :app:testDebugUnitTest       # same, scoped to the :app module
```

Reports: `app/build/reports/tests/` (HTML) and
`app/build/test-results/` (XML), also uploaded as CI artifacts.
