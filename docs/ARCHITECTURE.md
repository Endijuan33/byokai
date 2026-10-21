# Architecture

## Status: Phase 4 — Advanced Chat Experience, Prompt Management & Data Portability (milestones 4A–4E complete, 4F in progress)

This document describes the architecture **as implemented today** plus the
planned module boundaries for later phases. Anything marked "planned" does
not exist yet — see [ROADMAP.md](ROADMAP.md) for the honest phase statuses.

## Implemented (Phase 2)

Single-module Android app (`:app`, namespace `com.byokai`):

```
com.byokai/
├── ByokaiApplication.kt        # Owns the AppContainer (manual DI graph)
├── MainActivity.kt             # Single activity; locale wrap in
│                             # attachBaseContext, reactive theme in setContent
├── core/
│   ├── common/                 # Result<T>, UiText (pure Kotlin + Context-free)
│   └── ui/                     # LocaleHelper (context locale wrapping)
├── data/
│   ├── local/datastore/        # SettingsDataStore + process-wide holder
│   ├── repository/             # SettingsRepositoryImpl
│   └── security/               # [Phase 3, Milestone 3B] Credential
│                             #   storage: TinkCredentialStore (Tink
│                             #   AES-256-GCM), DataStoreCredentialStorage
│                             #   (dedicated byokai_credentials file),
│                             #   AndroidKeysetAeadProvider (Android
│                             #   Keystore master key)
├── di/
│   └── AppContainer.kt         # Manual DI: lazy singletons (interim)
├── domain/
│   ├── model/                  # ThemeMode, AppLanguage (pure enums)
│   ├── repository/             # SettingsRepository interface
│   └── security/               # [Phase 3, Milestone 3B] CredentialStore
│                             #   interface + CredentialException (pure
│                             #   Kotlin; ProviderConfig intentionally
│                             #   carries NO apiKey field)
├── ui/
│   ├── ByokaiApp.kt            # Root NavHost (routes: "home", "settings")
│   ├── home/HomeScreen.kt      # Conversation list + honest empty state
│   ├── settings/               # SettingsScreen, SettingsViewModel (+factory),
│   │                         # SettingsUiState
│   └── theme/                  # Material 3 theme: Color, Theme, Type
└── util/
    └── VersionInfo.kt          # Pure Kotlin version-name helpers (unit-tested)
```

## Implemented (Phase 3 — milestones 3A–3E)

The Phase 2 tree grows as follows (new/changed packages only):

```
com.byokai/
├── core/
│   └── network/                # [3A/3C] EndpointValidator (HTTPS-only base
│                             #   URLs; loopback HTTP with warning; rejects
│                             #   userinfo + 169.254.169.254), HttpClientFactory
│                             #   (OkHttp: redirects disabled, no logging
│                             #   interceptor, 15s/60s/15s timeouts),
│                             #   SseReader (SSE framing, 8 MiB cap),
│                             #   RetryPolicy (pre-Delta retries only, max 2,
│                             #   exponential backoff + capped Retry-After),
│                             #   ProviderJson (lenient shared Json)
├── data/
│   ├── chat/                   # [3C] ChatRepositoryImpl: resolves config →
│   │                         #   modelId → stored key → adapter per request;
│   │                         #   cold Flow, never throws (failures are
│   │                         #   ChatEvent.Error)
│   ├── local/
│   │   └── db/                 # [3E] ByokaiDatabase: hand-rolled SQLite
│   │                         #   (androidx.sqlite:sqlite-framework:2.7.1),
│   │                         #   byokai.db schema v1 (conversations,
│   │                         #   messages, index, FK cascade). NO API keys
│   │                         #   ever stored here.
│   ├── provider/
│   │   ├── openai/             # [3C] OpenAiAdapter: OpenAI Chat Completions
│   │   │                     #   (+ OpenAI-compatible endpoints via custom
│   │   │                     #   baseUrl). SSE data:/[DONE].
│   │   ├── anthropic/          # [3D] AnthropicAdapter: Messages API.
│   │   │                     #   event:-driven SSE (content_block_delta /
│   │   │                     #   message_stop / error), no model listing.
│   │   └── gemini/             # [3D] GeminiAdapter: streamGenerateContent.
│   │                         #   candidates[0].content.parts[].text,
│   │                         #   EOF-terminated.
│   ├── repository/             # [3C/3E] DataStoreProviderConfigRepository
│   │                         #   (configs as one JSON string; no key
│   │                         #   material), SqliteConversationRepository
│   └── security/               # [3B] TinkCredentialStore (+ AeadProvider,
│                             #   AndroidKeysetAeadProvider, CredentialStorage,
│                             #   DataStoreCredentialStorage, holders)
├── di/
│   └── AppContainer.kt         # Manual DI: lazy singletons (interim — Hilt
│                             #   deferred; KSP 2.3.12 has no 2.4.x release).
│                             #   Owns credentialStore, httpClient,
│                             #   providerRegistry, providerConfigRepository,
│                             #   chatRepository, conversationRepository.
├── domain/
│   ├── chat/                   # [3A/3C] ChatRequest, ChatEvent
│   │                         #   (Delta/Done/Error), ChatRepository
│   │                         #   interface
│   ├── model/                  # [3A] ProviderId (OPENAI, ANTHROPIC, GEMINI,
│   │                         #   OPENAI_COMPATIBLE), ProviderConfig
│   │                         #   (validate(); NO apiKey field),
│   │                         #   ProviderCapabilities (streaming,
│   │                         #   systemPrompt, modelListing, vision,
│   │                         #   toolCalling), ModelInfo, ChatMessage,
│   │                         #   Conversation, Message (+ Phase 2 enums)
│   ├── provider/               # [3A] AiProvider (adapter contract),
│   │                         #   ProviderRegistry (one adapter per id,
│   │                         #   duplicate registration fails fast),
│   │                         #   ProviderError (Auth/RateLimited/Timeout/
│   │                         #   Network/InvalidModel/Server/Parse/Config),
│   │                         #   ProviderException
│   ├── repository/             # [3C/3E] ProviderConfigRepository,
│   │                         #   ConversationRepository (pure-Kotlin
│   │                         #   interface; impl is Android-only)
│   └── security/               # [3B] CredentialStore + CredentialException
└── ui/
    ├── ByokaiApp.kt            # NavHost gains providers, chat,
    │                         # conversations destinations
    ├── providers/              # [3C] ProvidersScreen: add/edit/delete/test
    │                         #   provider configs. Keystrokes never leave
    │                         #   the composable until save/test; keyReady
    │                         #   tracks only key presence.
    ├── chat/                   # [3C/3E] ChatScreen + ChatViewModel: streaming
    │                         #   bubbles, config/model selectors (only
    │                         #   keyed configs selectable), Stop while
    │                         #   generating, error banner with Retry,
    │                         #   per-conversation persistence.
    └── conversations/          # [3E] ConversationsScreen +
                             #   ConversationsViewModel: history list,
                             #   rename, delete (with confirmation),
                             #   driven by ConversationRepository flows
```

Design decisions (added or changed in Phase 3):

- **Manual DI (`di.AppContainer`) — unchanged shape, larger graph.** Hilt
  is still deferred: KSP has no release for Kotlin 2.4.x (latest KSP is
  2.3.12, verified 2026-10-21). The container exposes repository and
  adapter interfaces as lazy singletons; screens resolve it via the
  Application. When Hilt returns, `AppContainer` is replaced by Hilt
  modules without changing repository interfaces or call sites.
- **Streaming via `callbackFlow`.** Each adapter's `streamChat` is a
  `callbackFlow` collected on `Dispatchers.IO`: a single
  `channel.invokeOnClose { call?.cancel() }` handler gives prompt
  cancellation (kotlinx.coroutines 1.11.0 has no `Job.invokeOnClose`;
  its `awaitClose` lives in `kotlinx.coroutines.channels` and must not
  be combined with another `invokeOnClose` handler), and the channel is
  closed explicitly on completion. Exactly one terminal event per
  collection; retry happens only pre-Delta.
- **Credential flow.** `ChatRepositoryImpl` loads the key from
  `CredentialStore` per request and passes it to the adapter as a plain
  parameter; the adapter sends it only as a request header. The key is
  never in a `ProviderConfig`, never in logs, never in errors.
- **ConversationRepository (Milestone 3E).** Pure-Kotlin interface in
  `domain.repository` (conversations/messages Flows; create/rename/
  delete/appendMessage/markComplete); `data.repository.
  SqliteConversationRepository` implements it with hand-rolled SQL —
  Android-only, so it cannot be instantiated on the JVM and ViewModel
  tests use an in-memory fake. Reads are cold Flows re-emitting after
  every write; writes return `Result`.
- **Hand-rolled SQLite, no Room.** `ByokaiDatabase` mirrors the
  DataStore holder pattern (one process-wide `SupportSQLiteOpenHelper`
  via `FrameworkSQLiteOpenHelperFactory`). Schema v2 (Phase 4):
  `conversations.system_prompt` + `prompts` table; upgrades run
  through the versioned `MigrationRunner` — destructive migrations
  are forbidden.
- All other Phase 2 decisions stand: single activity + Compose
  Navigation, Material 3 + Dynamic Color, DataStore settings, per-app
  language with the documented `runBlocking` tradeoff, `Result`/`UiText`
  error handling, full en/id localization, no hardcoded UI text,
  minSdk 28 / targetSdk 37, environment-driven release signing.

## Implemented (Phase 4 — milestones 4A–4E)

- **Database migrations (4A)**: `data.local.db.migration`
  (`DbHandle`, `DatabaseMigration`, `MigrationRunner`) — pure Kotlin,
  transactional, gapless version chain; `SupportDbHandle` adapts
  `SupportSQLiteDatabase` on Android, a JDBC handle backs JVM tests.
  Schema v2: `conversations.system_prompt` + `prompts` table.
- **Markdown rendering (4B)**: `ui.chat.AssistantMarkdownText`
  wraps `com.mikepenz:multiplatform-markdown-renderer-m3-android:0.46.0`;
  `core.ui.LinkSafety` (pure http/https allowlist) gates link taps via
  a `UriHandler` override.
- **Prompt management (4C)**: `domain.model.PromptTemplate` /
  `BuiltinPrompts`, `domain.repository.PromptRepository`,
  `data.repository.SqlitePromptRepository`, `ui.prompts`
  (ViewModel + management screen, route `prompts`, Settings entry),
  per-conversation system prompt in `ChatViewModel`/`ChatScreen`.
- **Context management (4D)**: `core.chat.TokenEstimator` (heuristic,
  "est."-labeled) + `core.chat.ContextPolicy` (request-side
  truncation); `ModelInfo.contextWindow` (nullable); estimate label in
  chat UI.
- **Export/import (4E)**: `data.export` (`ByokaiExport` DTOs,
  `ByokaiExportFormat` validation, `ConversationExportManager`);
  format documented in `docs/EXPORT.md`; SAF UI in Settings → Data.
- All Phase 3 decisions stand.

## Planned module boundaries (Phase 2+)

When the codebase outgrows one module, it splits along these lines
(packages below become modules):

| Layer | Responsibility |
|---|---|
| `ui` / `feature:*` | Compose screens, navigation, ViewModels |
| `domain` | Use cases, domain models, repository interfaces — pure Kotlin |
| `data` | Repository implementations: hand-rolled SQLite DB, DataStore prefs, provider adapters |
| `core:security` | Keystore-backed encryption, credential redaction |
| `core:network` | OkHttp clients, SSE streaming, timeouts/retries |

Rules that already apply:

- UI never touches network or disk directly — only repositories.
- `domain` has no Android framework dependencies (unit-testable on JVM).
- API keys never appear in logs, crash reports, or backups.

## Deferred decisions

- **Dependency injection:** Hilt is deferred because KSP has no release for
  Kotlin 2.4.x (latest KSP is 2.3.12). Manual constructor injection is used
  until Hilt returns in a later phase.
- **Room (local database):** deferred for the same KSP reason — latest
  Room 2.8.5 needs KSP. Conversation persistence is hand-rolled SQLite
  via `androidx.sqlite:sqlite-framework:2.7.1` (verified 2026-10-22),
  with migration tests deferred until the first schema change.
- **Minification:** R8 is off until Phase 5, when ProGuard rules are reviewed
  rule-by-rule.
