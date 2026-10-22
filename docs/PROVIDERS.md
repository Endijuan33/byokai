# Providers (Phase 3, current 0.8.0)

This document describes the provider system **as implemented** (Phase 3,
milestones 3A–3E; extended by reliability fixes in Phase 5, the provider
catalog + generic adapters in Phase 8, and vision/image input in Phase
9B). The contract is
`domain.provider.AiProvider` (`providerId`, `displayName`, `defaultBaseUrl`,
`capabilities`, `listModels`, `testConnection`, `streamChat`); each adapter
owns its auth scheme, message format, streaming protocol, and error mapping —
never assume providers share any of these.

> **Device-test status (2026-10-22).** The Gemini adapter has been tested
> live on the owner's device with the owner's own key
> (`gemini-3.6-flash`). OpenAI, Anthropic, and both generic adapters are
> validated against MockWebServer fixtures only. No real keys or live
> endpoints are ever used in CI or in this repository.

Adapters in `AppContainer.providerRegistry` (all share one
`OkHttpClient` from `core.network.HttpClientFactory`, `Dispatchers.IO`
for streaming):

- `OpenAiAdapter` — OpenAI Chat Completions API
- `AnthropicAdapter` — Anthropic Messages API
- `GeminiAdapter` — Google Gemini Generative Language API
- `OpenAiCompatibleAdapter` — any third-party OpenAI-compatible endpoint
- `AnthropicCompatibleAdapter` — any third-party Anthropic-compatible endpoint

The shared core (`core.network`) every adapter uses:

- `SseReader` — Server-Sent Events framing (skips `:comment` keep-alive
  lines, joins multi-line `data:` fields, ignores `id:`/`retry:`;
  dispatches a trailing event when EOF arrives without a final blank
  line). Hard stream cap: **8 MiB** (`MAX_BYTES`); exceeding it fails
  the stream.
- `RetryPolicy` — retries **only before the first `ChatEvent.Delta` is
  emitted** (retrying after partial output would duplicate text the
  caller already delivered; once the stream has started, every failure
  is terminal). Retryable: `Network`, `Timeout`, 5xx `Server`,
  `RateLimited`. Never retried: `Auth`, `InvalidModel`, `Parse`, other
  4xx. At most **2 retries** after the initial attempt; backoff is
  `1000ms × 2^attempt` plus the provider's `Retry-After` hint (seconds,
  capped at 30 s).
- `ProviderJson` — shared lenient kotlinx.serialization `Json`:
  `ignoreUnknownKeys = true`, `explicitNulls = false`,
  `encodeDefaults = true` (serialization 1.12.0 defaults this to
  `false`, which would silently drop e.g. OpenAI's `stream: true`).
- Transport defaults: 15 s connect, **60 s read** (streams stay open),
  15 s write. Redirects are disabled; error bodies are capped at 64 KiB
  and model listings at 1 MiB.

Chat protocol (`domain.chat`): `streamChat` returns a cold `Flow` of
`ChatEvent` — zero or more `Delta(text)` chunks, then exactly one
terminal event: `Done(fullText)` on success or `Error(ProviderError)`
on failure. The flow never throws; collector cancellation cancels the
in-flight HTTP call (via `SendChannel.invokeOnClose`), unblocking the
socket read. `ChatRepositoryImpl` resolves each request in order —
config must exist → `modelId` must be one of the config's `modelIds` →
a key must be stored (missing/keystore-invalid/decrypt-fail → `Auth`;
other storage failures → `Network`) → an adapter must be registered
for the config's `ProviderId` — each failure emitted as a single
terminal `Error`.

`ProviderConfig` invariants (enforced by `validate()` and by
`DataStoreProviderConfigRepository` on the write path): non-blank
`displayName`; `baseUrl` passing `EndpointValidator`; non-empty
`modelIds`; `defaultModelId` (when set) ∈ `modelIds`. Configs carry **no
API key** — keys live only in the `CredentialStore` keyed by config id.

## Base URL convention

OpenAI SDK style: the base URL includes the version prefix **exactly as
the provider documents it** (e.g. `https://api.openai.com/v1`); adapters
append only the endpoint path (`/chat/completions`, `/models`,
`/messages`). The adapters never bake `/v1` into request paths —
third-party endpoints document their own prefixes:

| Provider | Base URL to paste | Resolves to |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | `…/v1/chat/completions` |
| DeepSeek | `https://api.deepseek.com/v1` | `…/v1/chat/completions` |
| Groq | `https://api.groq.com/openai/v1` | `…/openai/v1/chat/completions` |
| Perplexity | `https://api.perplexity.ai` (no `/v1`) | `…/chat/completions` |
| Ollama | `http://localhost:11434/v1` | `…/v1/chat/completions` |

When adding a custom provider, paste the base URL from the provider's
own docs verbatim. If chat calls 404, the first thing to check is the
prefix: a doubled `/v1` (`…/v1/v1/chat/completions`) is the usual
cause.

Migration note (v0.8.1): configs stored before v0.8.1 carry the old
defaults without `/v1` (the adapters used to append it). On load, exact
matches of those old defaults are migrated to the new form
automatically; custom URLs are never touched.

## OpenAI

- **Auth**: `Authorization: Bearer <apiKey>` header; key never logged,
  never stored outside the credential store, never in error messages.
- **Base URL** (default): `https://api.openai.com/v1`.
- **Chat**: `POST {baseUrl}/chat/completions`, body
  `{model, messages, stream: true}`. System prompt goes first as role
  `"system"`; domain roles map `SYSTEM→"system"`, `USER→"user"`,
  `ASSISTANT→"assistant"`.
- **Streaming wire format**: SSE `data:` lines; each payload is
  `{choices: [{delta: {content: "..."}}]}`; text chunks → `Delta`.
  Terminal sentinel: `data: [DONE]` → `Done`. EOF without `[DONE]` is a
  `Parse` error (`"stream ended without [DONE]"`).
- **Error envelope**: `{"error": {"message": "..."}}`, best-effort;
  falls back to `"HTTP <code>"`.
- **listModels**: `GET {baseUrl}/models`; blank ids filtered out.
- **testConnection**: cheap `GET {baseUrl}/models` — no model call,
  so no quota burn beyond the listing call itself.

## Provider catalog (Phase 8)

The add-provider flow starts from a searchable, **bundled** catalog
(`domain.provider.ProviderCatalog`) — 13 curated entries: OpenAI,
Anthropic, Google Gemini, DeepSeek, xAI, OpenRouter, Groq, Together AI,
Fireworks AI, Mistral AI, Perplexity, Ollama, LM Studio.

- The catalog ships **inside the app** — no network fetch, works
  offline, and the list can never be tampered with in transit.
- Each entry carries: unique id, display name, adapter family, default
  base URL, suggested models, website.
- Selecting an entry only **pre-fills** the editor (name, base URL,
  family, suggested models); every field stays editable before saving.
- The search field filters by name (case-insensitive); a "Custom
  provider" section covers endpoints not in the list, with an explicit
  note that **the API key will be sent to the chosen endpoint** — only
  use endpoints you trust.
- **Suggested models are suggestions, not verified data.** Model ids
  change over time; the UI labels them "suggested", and endpoints that
  implement model listing can refresh the real list after saving (the
  "Refresh models" button in the editor).

Inclusion rule: an entry is listed only when its base URL and protocol
are known with confidence. Local servers (Ollama, LM Studio) use
cleartext loopback URLs, which `EndpointValidator` allows with a
UI-visible warning.

## Generic adapters (Phase 8)

Instead of one bespoke adapter per third-party provider, two generic
adapters reuse the first-party wire implementations:

- **`OpenAiCompatibleAdapter`** (`ProviderId.OPENAI_COMPATIBLE`)
  subclasses `OpenAiAdapter`: identical SSE streaming, error envelopes,
  retries, and per-call `EndpointValidator` checks. No fixed base URL —
  the user picks one from the catalog or types a custom endpoint.
  **Model listing is best-effort**: endpoints that do not implement
  `GET /models` (HTTP 404, or a body that is not the OpenAI
  envelope) fall back to the config's manually entered model ids
  instead of failing. Auth, rate-limit, network, and timeout failures
  propagate unchanged, so a bad key is never silently masked.
- **`AnthropicCompatibleAdapter`** (`ProviderId.ANTHROPIC_COMPATIBLE`)
  subclasses `AnthropicAdapter`: identical Messages-API wire protocol
  (`x-api-key`, `anthropic-version`, `max_tokens: 1024`). No model
  listing (like the first-party adapter); the connection test is a
  minimal real completion and burns micro-quota by design.

Security posture is identical to the first-party adapters: the key
travels only in its auth header, the base URL is re-validated on every
call (HTTPS-only except loopback, userinfo rejected, cloud metadata
address blocked), redirects stay disabled, and keys never appear in
logs, errors, or exports.

## OpenAI-compatible endpoints (legacy note)

Before Phase 8, `ProviderId.OPENAI_COMPATIBLE` existed in the enum but
no adapter was registered for it — custom OpenAI-shaped endpoints had
no working path. The generic adapters above close that gap; the
per-call `EndpointValidator` guarantees from the old design are kept.

**Switching provider family** (`ProvidersViewModel.selectProviderFamily`):
a blank base-URL field, or one still holding the previous family's
default, is replaced with the new family's default; a hand-typed URL is
left alone, so a custom proxy or OpenAI-compatible endpoint is never
clobbered by accident.

## Anthropic

- **Auth**: `x-api-key: <apiKey>` header (plus the required
  `anthropic-version: 2023-06-01` header); key never logged, never
  stored outside the credential store, never in error messages.
- **Base URL** (default): `https://api.anthropic.com/v1`.
- **Chat**: `POST {baseUrl}/messages`, body
  `{model, max_tokens, stream: true, system?, messages}`.
  - `max_tokens` is **required by the API (no server default)**; chat
    uses **1024** — a deliberately modest generation ceiling that bounds
    accidental quota burn while still allowing typical chat answers to
    stream incrementally.
  - Anthropic has no `"system"` message role: the system prompt plus any
    `SYSTEM`-role turns are joined with blank lines into the top-level
    `system` field; `messages` carries user/assistant turns only.
- **Streaming wire format** (tracked by `event:` name):
  - `content_block_delta` → `data.delta.text` accumulated and emitted
    as `Delta` per non-empty chunk.
  - `message_stop` → `Done` with the accumulated text.
  - `error` → SSE error envelope parsed and mapped by type (see below).
  - Everything else (`message_start`, `content_block_start`,
    `content_block_stop`, `message_delta`, `ping`, …) is ignored.
  - There is **no `[DONE]` sentinel**: clean EOF without
    `message_stop` completes the stream (`Done`) — Anthropic closes it
    cleanly, so this is not a parse failure.
- **Error envelope**: `{"type": "error", "error": {"message": ...}}`.
  SSE `error` events map by Anthropic type:
  `authentication_error`/`permission_error` → `Auth`,
  `rate_limit_error` → `RateLimited`, `not_found_error` →
  `InvalidModel`, anything else → `Server`.
- **listModels**: Anthropic exposes **no model-listing endpoint**, so per
  the contract this returns the config's known `modelIds` as `ModelInfo`
  entries — no network call, no quota burn.
- **testConnection**: a minimal **real** non-streaming
  `POST {baseUrl}/messages` (`max_tokens: 1`, one one-word user
  message) against the config's `defaultModelId` (falling back to the
  first `modelId`); fails with `Config` when no model id is configured.
  **Quota note**: unlike the OpenAI/Gemini listing-based tests, this
  performs an actual model call — tiny (one output token), but it burns
  a small amount of quota on every test. There is no cheaper way to
  verify an Anthropic key, and no automatic connection test runs on
  page open.

## Google Gemini

- **Auth**: `x-goog-api-key: <apiKey>` header (Gemini does not use
  `Authorization: Bearer`); key never logged, never stored outside the
  credential store, never in error messages.
- **Base URL** (default): `https://generativelanguage.googleapis.com`.
- **Chat**: `POST {baseUrl}/v1beta/models/{modelId}:streamGenerateContent?alt=sse`,
  where `{modelId}` is percent-encoded for path use (plain model ids
  pass through untouched). Body `{contents, systemInstruction?}`: turns
  become `contents` with `USER→"user"` and `ASSISTANT→"model"` roles
  (Gemini's contents API accepts only those two); the system prompt plus
  any `SYSTEM`-role turns fold into `systemInstruction`.
- **Streaming wire format**: SSE `data:` lines; text is the
  concatenation of `candidates[0].content.parts[].text` per chunk
  (chunks without candidates — e.g. `promptFeedback`-only — contribute
  nothing). There is **no `[DONE]` sentinel**: clean EOF → `Done`.
- **Error envelope**: `{"error": {"message": "..."}}`, best-effort;
  falls back to `"HTTP <code>"`.
- **listModels**: `GET {baseUrl}/v1beta/models`; resource names like
  `"models/gemini-2.0-flash"` have the `"models/"` prefix stripped so
  the id is what `:streamGenerateContent` expects.
- **testConnection**: cheap `GET {baseUrl}/v1beta/models` — no model
  call, so no quota burn beyond the listing call itself.

## Error normalization (all adapters)

| HTTP status | `ProviderError` | Notes |
|---|---|---|
| 401, 403 | `Auth` | wrong/missing key; never retried |
| 429 | `RateLimited(message, retryAfterSeconds)` | `Retry-After` parsed as seconds when present |
| 404 | `InvalidModel` | unknown/inaccessible model id |
| 5xx, anything else | `Server(statusCode, message)` | 5xx retried pre-Delta per `RetryPolicy` |

Non-HTTP errors: `Timeout` (socket timeout), `Network(cause)`
(transport failure), `Parse` (malformed payload), `Config` (missing
config / unregistered provider / invalid endpoint), and
`EmptyResponse` (the provider returned a successful-but-empty
completion — surfaced in the UI as a friendly message, never an empty
bubble). Error messages come from the provider's envelope on a
best-effort basis and **never contain the API key**.

## Capability flags

| Capability | OpenAI | Anthropic | Gemini | OpenAI-compat | Anthropic-compat |
|---|---|---|---|---|---|
| `streaming` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `systemPrompt` | ✅ | ✅ (top-level `system` field) | ✅ (`systemInstruction`) | ✅ | ✅ |
| `modelListing` | ✅ (`GET /models`) | ❌ (no endpoint — returns config `modelIds`) | ✅ (`GET /v1beta/models`) | ✅ best-effort (falls back to manual ids) | ❌ (manual ids only) |
| `vision` | ✅ protocol | ✅ protocol | ✅ protocol | ✅ protocol | ✅ protocol |
| `toolCalling` | ❌ | ❌ | ❌ | ❌ | ❌ |

### Vision support (Phase 9B)

`vision: true` means the **adapter can encode image attachments into
the provider's native message format** — it is a protocol-level
claim, not a per-model guarantee:

| Adapter | Image wire format |
|---|---|
| OpenAI | Chat Completions content parts: `{"type":"image_url","image_url":{"url":"data:<mime>;base64,…"}}` |
| Anthropic | Messages API image blocks: `{"type":"image","source":{"type":"base64","media_type":"…","data":"…"}}` |
| Gemini | `streamGenerateContent` parts: `{"inlineData":{"mimeType":"…","data":"…"}}` |
| OpenAI-compat / Anthropic-compat | Reuse the first-party wire logic above |

Whether a given **model** accepts images is decided server-side and
varies by model: sending images to a text-only model returns a
provider error (typically HTTP 400, surfaced as `InvalidModel` /
`InvalidRequest`), never a silent wrong answer. The capability flag
therefore gates the *UI affordance* (attach button); model
compatibility remains the server's verdict. Image bytes are read
from the on-device attachment store at send time and base64-encoded
in memory only — they are never persisted to the provider config or
the credential store.

> **Device-test status (2026-10-22).** Vision encoding is validated
> against MockWebServer fixtures only; no live vision call has been
> made from the owner's device yet. What the fixtures prove is the
> wire format per adapter (table above) — not that any particular
> model accepts images.

## Honest limits

- **No tool/function calling** is implemented in any adapter; the
  capability flag exists precisely so the UI never offers it. Vision
  (image input) shipped in Phase 9B — see above; model-level support
  is server-side and varies by model.
- Streaming is the only completion mode; there is no non-streaming chat
  path besides Anthropic's connection test.
- Adapter unit tests use scripted MockWebServer fixtures (see
  [TESTING.md](TESTING.md)), not live provider endpoints — behavior
  against real servers is verified only with the user's own key on
  their own device. Real-key testing never happens in CI or in this
  repository. Live device validation so far: **Gemini only**
  (`gemini-3.6-flash`, 2026-10-22); OpenAI, Anthropic, and both generic
  adapters are MockWebServer-only.
- Catalog suggested models are hints from public documentation,
  re-checked 2026-10-22; they go stale — the UI and this doc label them
  as suggestions, and "Refresh models" (where the endpoint supports it)
  is the source of truth after saving.
