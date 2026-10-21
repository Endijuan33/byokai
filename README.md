# BYOKAI

**BYOKAI** — *Bring Your Own Key* AI agent for Android.

BYOKAI is a native Android application (Kotlin, Jetpack Compose, Material 3) that
combines a modern conversational experience with multi-provider model management:
you supply your own API keys, and BYOKAI talks to the providers you configure.

> **Project status: Phase 1 — repository & CI foundation.**
> The app currently builds, passes unit tests and lint, and ships a debug APK
> from CI. Chat, provider adapters, and AI features are **not implemented yet** —
> see [docs/ROADMAP.md](docs/ROADMAP.md) for the honest status of every phase.

## Highlights (target vision)

- Streaming chat with Markdown and code rendering
- Multi-provider BYOK: OpenAI, Anthropic, Google Gemini, DeepSeek, xAI,
  OpenRouter, and OpenAI-compatible endpoints
- Encrypted on-device credential storage (Android Keystore)
- Local-first conversation history
- Material 3 UI with Dynamic Color, light/dark themes

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
# 1. Generate the Gradle wrapper locally (one time; needs any JDK 17 + Gradle):
gradle wrapper --gradle-version 9.8.0 --distribution-type bin

# 2. Build the debug APK:
./gradlew assembleDebug

# 3. Run unit tests:
./gradlew testDebugUnitTest

# 4. Run lint:
./gradlew lintDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Repository layout

```
.
├── app/                        # Android application module (com.byokai)
│   └── src/main/java/com/byokai/
│       ├── ByokaiApplication.kt
│       ├── MainActivity.kt
│       ├── ui/                 # Compose UI: theme, navigation, screens
│       └── util/               # Pure Kotlin helpers (unit-tested)
├── gradle/libs.versions.toml   # Version catalog (all versions verified)
├── .github/workflows/          # CI: pr-validation, ci, release
└── docs/                       # Architecture, build, release, testing, roadmap
```

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — module structure and design decisions
- [docs/BUILD.md](docs/BUILD.md) — how CI builds, tests, and lints
- [docs/RELEASE.md](docs/RELEASE.md) — signing setup and release process
- [docs/PROVIDERS.md](docs/PROVIDERS.md) — provider adapter plan (not started)
- [docs/TESTING.md](docs/TESTING.md) — testing strategy and coverage
- [docs/ROADMAP.md](docs/ROADMAP.md) — phase plan with verified statuses
- [CONTRIBUTING.md](CONTRIBUTING.md) — contribution standards
- [SECURITY.md](SECURITY.md) — vulnerability reporting and security practices
- [CHANGELOG.md](CHANGELOG.md) — release history

## License

TBD — a license will be chosen before the first public release (Phase 6).
