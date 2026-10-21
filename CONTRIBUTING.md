# Contributing to BYOKAI

## Ground rules

1. **GitHub Actions is the sole build machine.** Never treat a local build,
   emulator, or any internal execution environment as a substitute for CI.
   A change is done only when the relevant workflow is green.
2. **English everywhere** — all code, comments, commit messages, and docs.
3. **Evidence over claims.** Diagnose failures from actual CI logs, not assumptions.
4. **Minimal, reviewable patches.** Prefer small focused changes over rewrites.
5. **Never commit secrets.** No keystores, passwords, API keys, or tokens —
   in code, resources, or build outputs. Ever.

## Workflow

1. Open an issue (or pick an existing one) describing the problem and the
   acceptance criteria.
2. Create a branch from `main`: `git checkout -b <type>/<short-description>`
   (`feature/`, `fix/`, `docs/`, `chore/`).
3. Make the change, keeping it minimal and consistent with
   [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
4. Add or update tests for behavior changes (see [docs/TESTING.md](docs/TESTING.md)).
5. Update docs and [CHANGELOG.md](CHANGELOG.md) when behavior or setup changes.
6. Open a pull request against `main`. The **PR Validation** workflow must be
   green (build, unit tests, lint, dependency review). Address every review
   comment before merge.

## Code standards

- Kotlin with the official code style (`kotlin.code.style=official`).
- Jetpack Compose for UI; Material 3 components; no XML layouts for screens.
- Coroutines/Flow for async work; lifecycle-aware state in ViewModels.
- Repository pattern: UI talks to repositories, never directly to network/DB.
- No hardcoded secrets, URLs with credentials, or plaintext key storage.
- Redact API keys and authorization headers in all logs.

## Commit messages

Use short, imperative subjects with a scope prefix:

```
feat(chat): add streaming message renderer
fix(build): pin setup-java to v6.1.0
docs(roadmap): mark Phase 2 as in progress
```

## Definition of done

A feature is done when: it is implemented (not just designed), unit-tested
where applicable, lint-clean, documented, and the relevant CI workflow is
green. See [docs/ROADMAP.md](docs/ROADMAP.md) for phase-level acceptance.
