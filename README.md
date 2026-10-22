# BYOKAI

**BYOKAI** — *Bring Your Own Key* AI agent for Android.

BYOKAI is a native Android application (Kotlin, Jetpack Compose, Material 3)
for conversing with multiple AI providers using your own API keys: OpenAI,
Anthropic, and Google Gemini first-party, plus a searchable catalog of
popular providers (DeepSeek, xAI, OpenRouter, Groq, Together AI, Fireworks
AI, Mistral AI, Perplexity, Ollama, LM Studio) served through generic
OpenAI-compatible and Anthropic-compatible adapters — all with streaming
chat, conversation history, prompt templates, and import/export.

> **Project status: Phase 8 — Provider expansion (in progress, 0.8.0).**
> The app builds, runs on-device, and is validated by CI (unit tests, lint
> green, R8 release pipeline validated) and by device testing on the owner's
> own phone (Phases 4–6 manual checklists passed; Gemini tested live with
> the owner's key). A signed release (v0.7.0) is published on GitHub
> Releases. See [docs/ROADMAP.md](docs/ROADMAP.md) for the honest
> status of every phase.

## Features (current, 0.8.0)

- **Multi-provider BYOK chat**: OpenAI (Chat Completions SSE), Anthropic
  (Messages API), Gemini (`streamGenerateContent`) — each with its own auth
  scheme, wire format, and error normalization; plus generic
  OpenAI-compatible and Anthropic-compatible adapters that reuse the
  first-party wire logic for third-party and self-hosted endpoints
  ([docs/PROVIDERS.md](docs/PROVIDERS.md))
- **Provider catalog with search**: 13 curated providers bundled in-app
  (offline, no network fetch); search filters by name; selecting an entry
  pre-fills the add-provider form (suggested models included, editable);
  a Custom-provider option covers unlisted endpoints
- **Streaming chat UI**: live message bubbles with Markdown rendering
  (code blocks with copy buttons, safe link taps), Stop while generating,
  error banner with Retry
- **Provider management**: add/edit/delete configs, save and test API keys;
  typed keystrokes never linger in ViewModel state
- **Encrypted credential storage**: API keys encrypted at rest with Tink
  AES-256-GCM, master key in the Android Keystore; backup-excluded; wipe
  and re-prompt on keystore invalidation
  ([docs/SECURITY.md](docs/SECURITY.md))
- **Local-first conversation history**: hand-rolled versioned SQLite
  (`byokai.db`, schema v2) — conversations, messages, rename, delete
- **Prompt management**: 4 editable built-in prompt templates, management
  screen (Settings → Prompts), per-conversation system prompt sent via each
  adapter's native channel
- **Context management**: heuristic token estimate (labeled "est."),
  request-side truncation that keeps the system prompt + newest messages
  within the context window; persisted history is never modified
- **Export/import**: versioned JSON (`byokai-export` v1) via the Storage
  Access Framework; 50 MB cap; fresh IDs on import; exports carry
  conversation text only, never credentials
  ([docs/EXPORT.md](docs/EXPORT.md))
- **Settings**: theme System/Light/Dark (applies immediately), Dynamic Color
  toggle, English/Indonesian language (persists, applies on activity
  recreate), About with the real build version
- **Full EN/ID localization**; no hardcoded UI strings

## Architecture highlights

- Single-module Android app (`:app`, namespace `com.byokai`),
  layered `ui` → `domain` → `data`/`core`
- Manual dependency injection via `di.AppContainer` (Hilt deferred: KSP has
  no release for Kotlin 2.4.x); pure-Kotlin `domain` layer, unit-testable
  on the JVM
- `core.common` (`Result`, `UiText`) — error handling without holding
  a Context; failures surface as Snackbar messages
- Hand-rolled SQLite (`androidx.sqlite:sqlite-framework`) with a versioned,
  transactional `MigrationRunner` — Room deferred for the same KSP reason
- Provider adapters stream via `callbackFlow`; collector cancellation kills
  the in-flight OkHttp call immediately
- Network hardening by default: HTTPS-only base URLs (loopback HTTP only
  with a visible warning), redirects disabled, no logging interceptor,
  8 MiB stream cap, retries only before the first response chunk

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 |
| Gradle | 9.8.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.4.21 |
| compileSdk / targetSdk | 37 (Android 17) |
| minSdk | 28 (Android 9) |

You do **not** need a local Android SDK or Gradle installation to validate
changes: GitHub Actions is the sole build/test/sign/release machine
(see [docs/BUILD.md](docs/BUILD.md)).

## Quick start

```bash
# 1. Generate the Gradle wrapper locally (one time; needs any JDK 17 + Gradle).
#    The repo intentionally does not ship the wrapper JAR; CI installs Gradle
#    directly via gradle/actions/setup-gradle.
gradle wrapper --gradle-version 9.8.0 --distribution-type bin

# 2. Build the debug APK:
./gradlew assembleDebug

# 3. Run unit tests:
./gradlew testDebugUnitTest

# 4. Run lint:
./gradlew lintDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
Debug builds are signed with the committed debug-only `debug.keystore`
at the repo root — every CI/local build shares one debug signature so
debug APK updates install cleanly. It is **never** used for release
signing (see [docs/RELEASE.md](docs/RELEASE.md)).

## Validation status

| Layer | Tool / method | Status |
|---|---|---|
| JVM unit tests | `testDebugUnitTest` | Active — **253 tests, 0 failures** (CI) |
| Android lint | `lintDebug` (`abortOnError = true`) | Active (CI) |
| Dependency review | PR checks | Active |
| Dependabot | Weekly (Gradle + Actions) | Active |
| Instrumented tests | `src/androidTest` (keystore path) | Written, device-only, **not run in CI** |
| Device testing | Owner's own phone, own keys | Phases 4 & 5 manual checklists **passed**; Gemini live-tested |

Real API keys and real provider endpoints are **never** used in tests or CI —
that validation happens only with the owner's own key on their own device.
OpenAI, Anthropic, and both generic adapters are MockWebServer-only so far;
Gemini has been tested live on-device. Details: [docs/TESTING.md](docs/TESTING.md).

## Repository layout

```
.
├── app/                        # Android application module (com.byokai)
│   └── src/main/java/com/byokai/
│       ├── ByokaiApplication.kt
│       ├── MainActivity.kt
│       ├── core/               # common (Result, UiText), chat (token
│       │                       #   estimate/context policy), network
│       │                       #   (EndpointValidator, SseReader,
│       │                       #   RetryPolicy), ui (LocaleHelper,
│       │                       #   LinkSafety)
│       ├── data/               # chat, export, local/datastore,
│       │                       #   local/db (+migration), provider/
│       │                       #   (openai, anthropic, gemini),
│       │                       #   repository, security
│       │                       #   (TinkCredentialStore)
│       ├── di/                 # AppContainer (manual DI)
│       ├── domain/             # chat, model, provider (AiProvider,
│       │                       #   ProviderError), repository,
│       │                       #   security (CredentialStore)
│       ├── ui/                 # theme, navigation, home, chat,
│       │                       #   conversations, providers, prompts,
│       │                       #   settings, common
│       └── util/               # Pure Kotlin helpers (unit-tested)
├── debug.keystore              # Debug-only signing key (committed
│                               #   intentionally; never for releases)
├── gradle/libs.versions.toml   # Version catalog (all versions verified)
├── .github/workflows/          # CI: pr-validation, ci, release
└── docs/                       # Architecture, build, export, providers,
                                #   release, roadmap, security, testing
```

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — module structure and design decisions
- [docs/BUILD.md](docs/BUILD.md) — how CI builds, tests, and lints
- [docs/EXPORT.md](docs/EXPORT.md) — export/import file format
- [docs/PROVIDERS.md](docs/PROVIDERS.md) — provider system: auth, endpoints, wire formats, error mapping
- [docs/RELEASE.md](docs/RELEASE.md) — signing setup and release process
- [docs/ROADMAP.md](docs/ROADMAP.md) — phase plan with verified statuses
- [docs/SECURITY.md](docs/SECURITY.md) — credential security design
- [docs/TESTING.md](docs/TESTING.md) — testing strategy and coverage
- [CONTRIBUTING.md](CONTRIBUTING.md) — contribution standards
- [SECURITY.md](SECURITY.md) — vulnerability reporting and security practices
- [CHANGELOG.md](CHANGELOG.md) — release history

## License

TBD — no license has been chosen yet; there is no `LICENSE` file in the
repository. A license will be chosen before the first public release.
