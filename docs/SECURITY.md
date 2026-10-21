# Credential Security (Milestone 3B)

How BYOKAI protects provider API keys. All code/comments are in the
`data.security` and `domain.security` packages; the DI wiring lives in
`di/AppContainer.kt`.

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

## Network rules

- Provider endpoints must be HTTPS — validated by
  `core.network.EndpointValidator`; the validator is the gate for any
  user-supplied base URL.
- Authorization headers must not leak through redirects. The HTTP layer
  (Milestone 3C) strips the `Authorization` header on cross-origin
  redirects and follows no redirect that would downgrade HTTPS to HTTP.
- API keys travel only as request headers over TLS; they are never placed
  in URLs or query strings.

## Honest caveat

On-device encryption raises the cost of extraction but does NOT make API
keys unextractable from a compromised or rooted device, nor from the
device owner. Treat a key entered on-device as exposed to the device.
This design protects against casual file access, lost-device forensics at
the filesystem level, and backup theft — it is not a defense against a
rooted device, a malicious accessibility service, memory dumping, or the
device owner themselves.
