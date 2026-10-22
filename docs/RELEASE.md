# Release process

> **Status (0.7.0):** no signed release has been published yet — the four
> signing secrets below are expected but not configured, so the Release
> workflow currently fails fast with a clear error instead of producing
> unsigned artifacts. Configuring them is the owner's one-time action;
> everything else in this phase is already prepared and CI-validated.

## One-time signing setup

The release keystore is created **once** by the project owner on a trusted
machine and stored as GitHub Actions secrets. It is never committed to the
repository. Nobody else — no contributor, no agent, no CI job — ever needs
the keystore file or its passwords.

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

`keytool` will prompt for a keystore password and a key password — use
**strong, distinct** values for each and store them in a password manager.

Record the certificate fingerprints immediately and keep them with the
passwords:

```bash
keytool -list -v -keystore byokai-release.keystore -alias byokai
```

Note the `SHA256:` line. This fingerprint is the ground truth you compare
against every future release (see "Verifying a release" below).

Back the keystore file up in **two** independent secure locations (e.g. an
encrypted USB drive and a password manager with file attachments).
**Losing this keystore means published apps can never be updated in place —
a new applicationId lineage would be required.**

### 2. Store the four secrets

In the repository: *Settings → Secrets and variables → Actions → New
repository secret*. Create exactly these names:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | output of `base64 -w0 byokai-release.keystore` (single line, no wrapping) |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias (e.g. `byokai`) |
| `ANDROID_KEY_PASSWORD` | key password |

On macOS, `base64` wraps by default — use `base64 -b 0`. Verify the decoded
round-trip locally before storing:

```bash
base64 -w0 byokai-release.keystore > /tmp/ks.b64
base64 -d /tmp/ks.b64 > /tmp/ks-check.keystore
keytool -list -keystore /tmp/ks-check.keystore -storepass <password>
rm /tmp/ks.b64 /tmp/ks-check.keystore
```

Do not share these values in chat, issues, or logs. They are only ever
consumed by the `release.yml` workflow, which deletes the decoded keystore
immediately after the build.

### 3. Key recovery and rotation

- **Password lost, keystore survives:** passwords cannot be recovered.
  Generate a brand-new keystore (step 1) and re-store all four secrets.
  Apps signed with the old keystore can no longer be updated — treat this
  as a new app lineage.
- **Keystore file lost:** published apps signed with it cannot be updated,
  ever. This is why step 1 mandates offline backups.
- **Keystore compromised:** generate a new keystore, re-store all four
  secrets immediately, and assume the old lineage is burned — you cannot
  "un-sign" what was already published with it.

## Pre-release checklist

Before running the Release workflow for version `X.Y.Z`:

- [ ] `main` is green on the **CI** workflow.
- [ ] `versionName`/`versionCode` bumped in `app/build.gradle.kts`
      (the workflow **refuses** a versionName that does not match the repo).
- [ ] `CHANGELOG.md` has a section for `X.Y.Z`.
- [ ] The four signing secrets exist (*Settings → Secrets and variables →
      Actions*).

## Cutting a release

1. Complete the checklist above.
2. Run the **Release** workflow (*Actions → Release → Run workflow*) with
   `versionName` = `X.Y.Z` (must be `MAJOR.MINOR.PATCH` and must match the
   repository).
3. The workflow:
   - fails fast with a clear error if any signing secret is missing;
   - refuses a versionName that does not match the repo (version discipline);
   - decodes the keystore to a temporary file, builds `assembleRelease` and
     `bundleRelease` with `-Pbyokai.versionName=<input>`;
   - deletes the temporary keystore immediately after the build;
   - verifies the APK signature with `apksigner verify --print-certs`;
   - **refuses the build if the APK is signed with the committed debug
     keystore** (`scripts/check-apk-signer.sh` — the same guard is proven
     to fire in CI on every push);
   - verifies the AAB signature with `jarsigner -verify`;
   - writes SHA-256 checksums;
   - publishes a GitHub Release tagged `v<X.Y.Z>` with the signed APK,
     the AAB, and the checksums attached.

## Verifying a release

After the workflow publishes, verify independently — never trust the
pipeline alone:

```bash
# 1. Download the APK from the GitHub Release and check its signer:
apksigner verify --print-certs byokai-vX.Y.Z.apk
# 2. Compare the "certificate SHA-256 digest" with the fingerprint you
#    recorded in step 1 of the setup. They must match exactly.
# Alternative without build-tools:
keytool -printcert -jarfile byokai-vX.Y.Z.apk
# 3. Compare the SHA-256 checksum of the file with checksums-sha256.txt.
```

If the digest does not match your recorded fingerprint, **do not install
it** — something in the signing chain changed.

## Installing and updating on a device

- **APK** (`byokai-vX.Y.Z.apk`): installs directly. On the device, allow
  "Install unknown apps" for the browser/file manager you download with,
  then open the APK. This is the artifact for direct distribution.
- **AAB** (`byokai-vX.Y.Z.aab`): Android App Bundle — **cannot be installed
  directly**; it is the upload format for Google Play. Keep it attached to
  the GitHub Release as the Play lineage artifact.
- **Updates keep data** (conversations, settings, prompts) as long as the
  new APK is signed with the **same release key** and carries a **higher
  versionCode**. A signature mismatch fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — the same conflict the committed
  debug keystore was introduced to prevent for debug builds.
- Debug builds (`com.byokai.debug`) and release builds (`com.byokai`) are
  different applicationIds and install side by side; one never updates the
  other.

## Guarantees and non-guarantees

- A release build **fails** (does not silently degrade) when signing secrets
  are absent or invalid — the debug key is never used for releases, and the
  signer guard refuses debug-signed output even if it somehow occurred.
- No keystore is ever generated automatically by CI; key continuity across
  releases is the owner's responsibility.
- Passing CI and a valid signature do not guarantee Play Protect / Play
  Integrity acceptance on any given device; those are device- and
  policy-dependent.
