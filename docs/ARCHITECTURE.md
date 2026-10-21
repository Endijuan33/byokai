# Architecture

## Status: Phase 2 — application foundation

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

Design decisions:

- **Single activity + Compose Navigation.** No fragments, no XML layouts.
  Phase 2 graph: `home` → `settings`.
- **Material 3 + Dynamic Color.** `ByokaiTheme` uses dynamic color schemes on
  Android 12+ and static light/dark seeds below that. The theme mode and
  dynamic-color toggle are reactive: `MainActivity` collects the settings
  Flows with `collectAsStateWithLifecycle`, so changes apply instantly.
- **Settings persistence via DataStore Preferences.** `SettingsDataStore`
  is a thin wrapper: reads recover from `IOException` with documented
  defaults; writes return `Result<Unit>` so failures reach the UI.
  `SettingsDataStoreHolder` keeps one process-wide instance because
  DataStore forbids two active instances on the same file.
- **Per-app language without restart UX friction.** The persisted language
  is read in `MainActivity.attachBaseContext` and applied by wrapping the
  base context (`LocaleHelper`). Tradeoff, documented deliberately:
  DataStore is async by design, so this read is synchronous via
  `runBlocking` with a 2-second `withTimeoutOrNull` and an English
  fallback. The timeout bounds any ANR risk; in practice the read is
  milliseconds. Changing language in Settings writes the preference, then
  recreates the activity so the new locale takes effect.
- **Manual DI (`di.AppContainer`).** Hilt is deferred until KSP supports
  Kotlin 2.4.x (see below). The container exposes repository interfaces as
  lazy singletons; screens resolve it via the Application. When Hilt
  returns, `AppContainer` is replaced by Hilt modules without changing
  repository interfaces or call sites.
- **Error handling.** Repositories return `Result`; ViewModels translate
  failures to `UiText` on an error channel; screens show a Snackbar.
  ViewModels never hold a `Context`.
- **Credential storage (Phase 3, Milestone 3B).** `domain.security`
  defines the `CredentialStore` interface (`save`/`load`/`delete`/
  `clearAll`) and `CredentialException` (`KeyInvalidated`,
  `DecryptionFailed`, `StorageError`) — pure Kotlin, no Android APIs.
  `data.security` implements it with Tink AES-256-GCM: the DEK keyset is
  encrypted by an Android Keystore master key
  (`android-keystore://byokai_master_key`), ciphertexts live in a
  dedicated DataStore file (`byokai_credentials`, excluded from backups),
  and the config id is AEAD associated data. `ProviderConfig`
  deliberately has no `apiKey` field — configs can be exported, logged, or
  displayed without ever exposing a secret. Keystore invalidation wipes
  the store and forces key re-entry. Full design: [SECURITY.md](SECURITY.md).
- **Localization.** All UI strings live in `values/strings.xml` (English,
  default) and `values-in/strings.xml` (Indonesian). No hardcoded text in
  Composables.
- **minSdk 28 / targetSdk 37.** New APIs are used conditionally with
  `Build.VERSION.SDK_INT` guards; `enableEdgeToEdge()` handles insets.
- **No permissions in Phase 2.** The manifest declares none because the app
  does no I/O beyond its own process yet. Every future permission must be
  justified in its pull request and recorded here.
- **Release signing is environment-driven** (`ANDROID_KEYSTORE_*` variables);
  without them a release build fails loudly instead of using the debug key.

## Planned module boundaries (Phase 2+)

When the codebase outgrows one module, it splits along these lines
(packages below become modules):

| Layer | Responsibility |
|---|---|
| `ui` / `feature:*` | Compose screens, navigation, ViewModels |
| `domain` | Use cases, domain models, repository interfaces — pure Kotlin |
| `data` | Repository implementations: Room DB, DataStore prefs, provider adapters |
| `core:security` | Keystore-backed encryption, credential redaction |
| `core:network` | OkHttp/Retrofit clients, SSE streaming, timeouts/retries |

Rules that already apply:

- UI never touches network or disk directly — only repositories.
- `domain` has no Android framework dependencies (unit-testable on JVM).
- API keys never appear in logs, crash reports, or backups.

## Deferred decisions

- **Dependency injection:** Hilt is deferred because KSP has no release for
  Kotlin 2.4.x (latest KSP is 2.3.12). Manual constructor injection is used
  until Hilt returns in a later phase.
- **Local database:** Room is deferred for the same KSP reason; the
  conversation-history schema lands with Phase 3.
- **Minification:** R8 is off until Phase 5, when ProGuard rules are reviewed
  rule-by-rule.
