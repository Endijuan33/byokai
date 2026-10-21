# Architecture

## Status: Phase 1 — foundation only

This document describes the architecture **as implemented today** plus the
planned module boundaries for later phases. Anything marked "planned" does
not exist yet — see [ROADMAP.md](ROADMAP.md) for the honest phase statuses.

## Implemented (Phase 1)

Single-module Android app (`:app`, namespace `com.byokai`):

```
com.byokai/
├── ByokaiApplication.kt        # Application subclass (minimal for now)
├── MainActivity.kt             # Single activity, edge-to-edge Compose host
├── ui/
│   ├── ByokaiApp.kt            # Root NavHost (one route: "home")
│   ├── home/HomeScreen.kt      # Honest Phase-1 placeholder screen
│   └── theme/                  # Material 3 theme: Color, Theme, Type
└── util/
    └── VersionInfo.kt          # Pure Kotlin version-name helpers (unit-tested)
```

Design decisions:

- **Single activity + Compose Navigation.** No fragments, no XML layouts.
- **Material 3 + Dynamic Color.** `ByokaiTheme` uses dynamic color schemes on
  Android 12+ and static light/dark seeds below that.
- **minSdk 28 / targetSdk 37.** New APIs are used conditionally with
  `Build.VERSION.SDK_INT` guards; `enableEdgeToEdge()` handles insets.
- **No permissions in Phase 1.** The manifest declares none because the app
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
  conversation-history schema lands with Phase 2/3.
- **Minification:** R8 is off until Phase 5, when ProGuard rules are reviewed
  rule-by-rule.
