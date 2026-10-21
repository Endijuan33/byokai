# Testing strategy

## Principles

- GitHub Actions is the only validation that counts. Local test runs are for
  iteration.
- Tests must be deterministic: no network, no wall-clock dependence, no
  order dependence.
- A failing test is fixed or the code is fixed — tests, lint, and security
  checks are never disabled to turn the pipeline green.

## Layers

| Layer | Tool | Status |
|---|---|---|
| JVM unit tests | JUnit 4 (`gradle testDebugUnitTest`) | Active (Phase 1) |
| Android lint | `gradle lintDebug`, `abortOnError = true` | Active (Phase 1) |
| Dependency review | `dependency-review-action` on PRs | Active (Phase 1) |
| Dependabot | Weekly Gradle + Actions updates | Active (Phase 1) |
| Instrumented tests | `connectedDebugAndroidTest` | Planned (Phase 2+) |
| Screenshot tests | Planned | Planned (Phase 4) |
| Provider adapter contract tests | Recorded HTTP fixtures | Planned (Phase 3) |

## Current coverage (Phase 1)

- `VersionInfoTest` — 5 tests covering `VersionInfo.parse` / `format`:
  valid input, multi-digit components, malformed input rejection, canonical
  formatting, and parse/format round-trip.

## Required coverage as features land

- Provider config parsing and validation (per adapter).
- Credential redaction in logs.
- Streaming response assembly, partial responses, and cancellation.
- Timeout, retry, auth-failure, and rate-limit handling.
- Model/provider switching mid-app.
- Conversation persistence, rename, search, archive, delete.
- Database migrations (once Room lands).
- Tool-calling argument handling and user-confirmation gates.
- Offline / connection-loss behavior.

## Running tests

```bash
gradle testDebugUnitTest --stacktrace   # unit tests (JVM, no device needed)
gradle lintDebug --stacktrace          # lint, fails the build on errors
```

Reports: `app/build/reports/tests/` (HTML) and
`app/build/test-results/` (XML), also uploaded as CI artifacts.
