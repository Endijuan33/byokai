# Security Policy

## Reporting a vulnerability

**Do not open a public issue for security vulnerabilities.**

Report them privately via GitHub's
[private vulnerability reporting](https://github.com/Endijuan33/byokai/security/advisories/new)
("Security" tab → "Advisories" → "New draft security advisory").

Include:

- A description of the vulnerability and its impact
- Steps to reproduce (proof of concept welcome)
- Affected versions / commits, if known

You will receive an acknowledgment, and we will coordinate disclosure and a
fix before any public mention. Please allow reasonable time for remediation.

## Security practices in this project

- **No secrets in the repository.** Keystores, passwords, API keys, and tokens
  are never committed. Release signing uses GitHub Actions secrets only.
- **Encrypted credential storage.** Provider API keys are encrypted at rest
  using keys protected by the Android Keystore (from Phase 3 onward).
  Encrypted stores are excluded from device backups.
- **Network security.** HTTPS only for production endpoints; cleartext traffic
  is rejected by default; user-supplied base URLs are validated to prevent
  SSRF-style abuse.
- **Least privilege.** The manifest declares only permissions the app truly
  needs; every addition must be justified in the pull request.
- **Dependency hygiene.** Dependabot scans weekly; dependency review runs on
  every pull request; known-vulnerable dependencies block merging.
- **Honest limits.** On-device encrypted storage reduces risk but cannot make
  API keys unextractable from a compromised or rooted device. This is
  documented for users rather than hidden.

## Supported versions

| Version | Supported |
|---|---|
| Latest release | Yes |
| Pre-release / main branch | Best effort |

Until the first release, `main` is the supported line.
