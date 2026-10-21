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
  Device validation so far: **Gemini** was tested live on the owner's
  phone (`gemini-3.6-flash`, 2026-10-22); **OpenAI and Anthropic remain
  MockWebServer-only**.
- **Real SQLite on the JVM** — `SqliteConversationRepository`,
  `SqlitePromptRepository`, and `ByokaiDatabase` are Android-only (they
  need a `Context` and the `sqlite-framework` driver); they are not
  instantiated in JVM unit tests. Their contracts are covered through
  in-memory fakes (`ConversationsViewModelTest`,
  `PromptsViewModelTest`). **Exception (Phase 4A):** the migration
  *logic* (`DatabaseMigration` objects) IS tested against a real
  SQLite engine on the JVM via `org.xerial:sqlite-jdbc` (test-scoped):
  `MigrationTest` builds a v1 database, migrates, and asserts data
  preservation — the Android `SupportSQLiteDatabase` wrapper itself is
  the only untested seam.
- **On-device Keystore** — `src/androidTest` ships
  `TinkCredentialStoreKeystoreTest` for the keystore-backed path, but it
  requires a real device/emulator and **does not run in CI**. The JVM Tink
  tests above cover the encryption logic; the Keystore binding is
  documented but untested until a device run. Owner manual device
  checklists (Phases 4 and 5) exercised the app on-device but did not
  cover this test.
- Instrumented UI/screenshot tests — planned (Phase 4, still not done).

## Layers

| Layer | Tool | Status |
|---|---|---|
| JVM unit tests | JUnit 4 (`gradle testDebugUnitTest`) | Active (Phases 1–6) — **253 tests** at 0.6.0 |
| Android lint | `gradle lintDebug`, `abortOnError = true` | Active (Phase 1) |
| Dependency review | `dependency-review-action` on PRs | Active (Phase 1) |
| Dependabot | Weekly Gradle + Actions updates | Active (Phase 1) |
| Instrumented tests | `connectedDebugAndroidTest` | Written (keystore path), **not run in CI** — no device matrix |
| Device testing | Owner's own phone + own keys | Phases 4 & 5 manual checklists **passed** (see below) |
| Screenshot tests | Planned | Planned (Phase 4, not started) |

## Current coverage (Phase 5)

- `VersionInfoTest` (Phase 1) — still green.
- Phase 2: enum storage-key round-trips, `SettingsViewModel` + fake,
  DataStore integration tests — still green.
- Phase 3: per-adapter MockWebServer streaming/error/retry/cancellation
  tests; `SseReader`/`RetryPolicy`/`EndpointValidator` unit tests;
  JVM Tink credential-store tests; provider-config repository tests;
  `ChatViewModel`/`ChatPersistence`/`ProvidersViewModel`/
  `ConversationsViewModel` tests with fakes.
- Phase 4 (all green in CI):
  - `MigrationTest` — v1→v2 on a real JVM SQLite engine (data
    preservation, prompts table, rollback on failure, fail-fast on a
    missing migration).
  - `LinkSafetyTest` — the http/https allowlist (dangerous schemes,
    relative and malformed URLs rejected).
  - `BuiltinPromptsTest` — seed set validity.
  - `PromptsViewModelTest` — seeding, CRUD, delete confirmation.
  - Adapter tests extended: explicit assertions that the system
    prompt travels only via the native channel (OpenAI system
    message, Anthropic `system`, Gemini `systemInstruction`) and
    never leaks into user messages.
  - `TokenEstimatorTest` / `ContextPolicyTest` — heuristic and
    truncation (order, system-prompt budgeting, fallbacks).
  - `ByokaiExportTest` — envelope, round-trip fidelity with fresh
    ids, no-credential-material assertion, all rejection paths.
- Phase 5 (all green in CI) — in `ChatViewModelTest`/`ChatRepositoryImplTest`:
  synchronous send-claim rejects a double `send()` (no duplicate user
  turn, single stream), empty completions normalize to
  `ProviderError.EmptyResponse` (nothing empty persisted),
  stop→send ordering, mid-stream config/model switch stops the stale
  stream first; `ContextPolicyTest` audits the 4D truncation policy.
- Honest gap: no Compose UI/golden tests (need instrumented tests;
  device-only). Markdown rendering correctness relies on the pinned
  upstream library (0.46.0) plus on-device validation.

## Device testing (owner's own phone, own keys — 2026-10-22)

Unit tests are necessary but not sufficient; the owner runs manual
device checklists. Both passed:

- **Phase 4**: markdown copy button in code blocks, Prompts management
  UI, link-tap gating, per-conversation system prompt (all three
  adapters), export/import round-trip.
- **Phase 5**: double-tap send (no duplicate user turn, single stream),
  stop→send turn order, mid-stream provider/model switch, airplane mode
  error + retry, retry after failure, force-stop recovery.
- **Live provider test**: Gemini only (`gemini-3.6-flash`) — streaming
  chat and connection test with the owner's own key. OpenAI/Anthropic
  remain MockWebServer-only.

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
