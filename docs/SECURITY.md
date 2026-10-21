# Credential Security (Milestone 3B, extended in 3F)

How BYOKAI protects provider API keys. All code/comments are in the
`data.security` and `domain.security` packages; the DI wiring lives in
`di/AppContainer.kt`.

> **MANDATORY HONEST CAVEAT — read this first.**
>
> On-device encryption does **NOT** prevent API-key extraction on a
> compromised or rooted device, nor from the device owner. It raises the
> bar — against casual file access, lost-device filesystem forensics,
> and backup theft — but it is **not** a defense against a rooted
> device, a malicious accessibility service, memory dumping, a
> compromised app process, or the device owner themselves. Treat any key
> entered on-device as exposed to that device. Nothing in this document
> claims otherwise; any security review that does is wrong.

## Credential storage design

API keys are encrypted at rest with an envelope scheme:

- **DEK**: a Tink AES-256-GCM keyset (`AeadKeyTemplates.AES256_GCM`),
  generated on first use and persisted via `AndroidKeysetManager` in the
  shared-preferences file `byokai_tink_keyset.xml`.
- **Keyset encryption**: the keyset file itself is encrypted by a master
  key held in the Android Keystore at
  `android-keystore://byokai_master_key`. The master key never leaves the
  Keystore; ciphertext can only be decrypted on this device while the
  user's lock-screen credentials are intact.
- **Ciphertext storage**: encrypted keys are Base64-encoded and stored in
  an isolated DataStore preferences file named `byokai_credentials`
  (`data/security/DataStoreCredentialStorage`), never the settings file.
  One entry per provider config: key `credential_<configId>`, value the
  Base64 ciphertext.
- **Associated data**: the provider config id is bound as AEAD
  associated data (`associatedData = configId.toByteArray(UTF_8)`), so a
  ciphertext copied under a different config id will not decrypt.
- **API surface**: `domain.security.CredentialStore` — `save` (blank keys
  rejected), `load` (null when absent), `delete`, `clearAll`. The
  implementation `data.security.TinkCredentialStore` uses
  `java.util.Base64` (not `android.util.Base64`) so the same code and its
  tests run on the plain JVM.
- **Plaintext lifetime**: a key exists in plaintext only in memory during
  `save` (before encryption) and briefly after `load` (before being handed
  to the HTTP layer that signs the request).

## Key lifecycle

- **First use**: the Tink keyset and the Keystore master key are generated
  lazily the first time a key is saved. Generation requires no user
  interaction but the Keystore key is bound to the device lock screen.
- **Invalidation**: if the Android Keystore master key is permanently
  invalidated — lock screen removed or reset
  (`KeyPermanentlyInvalidatedException`, detected directly or anywhere in
  Tink's cause chain) — `AndroidKeysetAeadProvider` throws
  `KeyInvalidatedException`. `TinkCredentialStore` responds by wiping every
  stored ciphertext (the DEK is gone, so they are permanently
  undecryptable anyway) and returning
  `CredentialException.KeyInvalidated`. The UI must tell the user their
  stored keys were wiped and ask them to re-enter API keys. This is a
  wipe-and-recover path, not a data-loss event.
- **Restore from backup**: the encrypted store is explicitly excluded
  from full backup and auto-backup (`res/xml/backup_rules.xml`):
  `datastore/byokai_credentials.preferences_pb` and
  `byokai_tink_keyset.xml`. The Keystore master key does not travel
  between devices regardless, so a restored device starts with an empty
  credential store; any attempt to use a pre-restore keyset against a new
  Keystore surfaces as `KeyInvalidated`, and the user re-enters keys.
- **Decryption failure**: a ciphertext that fails authentication (wrong
  key, corrupted or tampered data, malformed Base64) surfaces as
  `CredentialException.DecryptionFailed(configId)` — the config id is a
  non-secret identifier, safe to display. Key material never appears in
  the exception message.

## What is never stored or logged

- No plaintext API keys in DataStore, any database, shared preferences,
  resources, config files, or bundled assets.
- No API keys in logs, exception messages, exception causes visible to
  callers, analytics events, crash reports, or CI output.
- `CredentialException` messages are static strings; only the non-secret
  config id is ever included (`DecryptionFailed`).
- The credential DataStore file and the Tink keyset file are excluded
  from device backups (see above).

## Credential redaction in errors and exceptions

`ProviderConfig` deliberately carries **no** `apiKey` field, so configs
can be exported, logged, or displayed without exposing a secret. At the
chat layer, `ChatRepositoryImpl` maps credential-store failures to
generic `ProviderError`s — keystore invalidation or decryption failure
→ `Auth("Stored API key is no longer available / could not be
decrypted; please re-enter it")` — never the key. Adapter error
messages are built from the provider's error envelope or a static
`"HTTP <code>"` fallback; the key is never interpolated into a message.
`ChatUiState.keyReady` is a map of config id → boolean: it records
*that* a key exists, never the key itself. The providers editor keeps
typed keystrokes in the composable's local state and hands them to
`saveDraft`/`testDraft` only at the moment of the action, so a key
never lingers in ViewModel state.

## Network (Phase 3, Milestones 3A/3C)

- **HTTPS required.** `core.network.EndpointValidator` is the gate for
  every user-supplied base URL and is re-run on every provider call
  (not just at config save time). Non-HTTPS schemes are rejected;
  cleartext HTTP is accepted **only** for loopback hosts
  (`127.0.0.1`, `::1`, `localhost`) — for local dev servers — and is
  flagged with a UI-visible `cleartext loopback` warning. Embedded
  credentials (`https://user@host`) are rejected, as is the cloud
  metadata address `169.254.169.254` (SSRF guard).
- **No redirects.** `HttpClientFactory` sets `followRedirects(false)`
  and `followSslRedirects(false)`: a redirect target never inherits
  the `Authorization` header, so an API key cannot leak to a
  third-party host via an HTTP redirect.
- **No logging interceptor — by design.** Authorization headers and
  request/response bodies are never logged anywhere; adding a
  body/header logger at the HTTP layer would put API keys in logcat.
- **Keys travel only as request headers over TLS** (`Authorization:
  Bearer`, `x-api-key`, or `x-goog-api-key`, per adapter); never in
  URLs or query strings, never embedded in the app.

## Retry and cancellation

`RetryPolicy` retries only **before** the first `ChatEvent.Delta` is
emitted (Network, Timeout, 5xx Server, RateLimited; max 2 retries;
exponential backoff plus the provider's `Retry-After`, capped at 30 s).
`Auth`, `InvalidModel`, `Parse`, and other 4xx are never retried —
they fail deterministically and a retry would only burn quota or time.
Collector cancellation cancels the in-flight OkHttp call
(`channel.invokeOnClose { call?.cancel() }`), unblocking the socket
read immediately; no dangling streams keep the key in memory longer
than needed. The read side is bounded: streams are capped at 8 MiB
(`SseReader.MAX_BYTES`), error bodies at 64 KiB, model listings at
1 MiB — a misbehaving server cannot force unbounded allocation.

## Database (Phase 3, Milestone 3E)

`byokai.db` (`data.local.db.ByokaiDatabase`, hand-rolled SQLite via
`androidx.sqlite:sqlite-framework:2.7.1`, schema v1:
`conversations`, `messages`, one index, FK cascade on delete) holds
conversation text and metadata **only — NEVER API keys**. Keys live
exclusively in the Tink-backed `CredentialStore`. The conversation
database file is *not* encrypted by this app; anyone with filesystem
access to the app's private storage can read conversation text. This
is a deliberate scope boundary: the DB holds chat content the user
already sees on screen, not secrets. If a threat model requires
encrypted conversation history, that is a future milestone, not a
claim made here.

## Honest caveat (restated)

On-device encryption raises the cost of extraction but does NOT make API
keys unextractable from a compromised or rooted device, nor from the
device owner. Treat a key entered on-device as exposed to the device.
This design protects against casual file access, lost-device forensics at
the filesystem level, and backup theft — it is not a defense against a
rooted device, a malicious accessibility service, memory dumping, or the
device owner themselves.
