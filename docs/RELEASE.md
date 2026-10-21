# Release process

> **Status (0.6.0):** no signed release has been published yet — the four
> signing secrets below are expected but not configured, so the Release
> workflow currently fails fast with a clear error instead of producing
> unsigned artifacts. Setting them up is part of Phase 7.

## One-time signing setup

The release keystore is created **once** by the project owner on a trusted
machine and stored as GitHub Actions secrets. It is never committed to the
repository.

### 1. Generate the release keystore (once)

On a trusted machine with a JDK installed:

```bash
keytool -genkeypair \
  -keystore byokai-release.keystore \
  -alias byokai \
  -keyalg RSA -keysize 4096 \
  -validity 9125 \
  -storetype PKCS12
```

Choose strong, distinct passwords for the keystore and the key, and back the
keystore file up in **two** independent secure locations (e.g. an encrypted
USB drive and a password manager with file attachments). **Losing this
keystore means published apps can never be updated in place.**

### 2. Store the four secrets

In the repository: *Settings → Secrets and variables → Actions → New
repository secret*. Create exactly these names:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 byokai-release.keystore` output |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias (e.g. `byokai`) |
| `ANDROID_KEY_PASSWORD` | key password |

Do not share these values in chat, issues, or logs. They are only ever
consumed by the `release.yml` workflow.

### 3. Key recovery

If a password is lost but the keystore file survives, it cannot be recovered —
rotate by generating a new keystore and re-storing all four secrets. If the
keystore file itself is lost, published apps signed with it cannot be updated;
a new applicationId lineage would be required. This is why step 1 mandates
offline backups.

## Cutting a release

1. Ensure `main` is green on the **CI** workflow.
2. Update `CHANGELOG.md` (move entries from *Unreleased* to the new version).
3. Run the **Release** workflow (*Actions → Release → Run workflow*) with the
   desired `versionName` (must be `MAJOR.MINOR.PATCH`).
4. The workflow:
   - fails fast with a clear error if any signing secret is missing;
   - decodes the keystore to a temporary file, builds `assembleRelease` and
     `bundleRelease` with `-Pbyokai.versionName=<input>`;
   - deletes the temporary keystore immediately after the build;
   - verifies the APK signature with `apksigner verify --print-certs`;
   - writes SHA-256 checksums;
   - publishes a GitHub Release tagged `v<versionName>` with the signed APK,
     the AAB, and the checksums attached.

## Guarantees and non-guarantees

- A release build **fails** (does not silently degrade) when signing secrets
  are absent or invalid — the debug key is never used for releases.
- No keystore is ever generated automatically by CI; key continuity across
  releases is the owner's responsibility.
- Passing CI and a valid signature do not guarantee Play Protect / Play
  Integrity acceptance on any given device; those are device- and
  policy-dependent.
