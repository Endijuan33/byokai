# Providers

## Status: NOT STARTED

This document is the design placeholder for the multi-provider BYOK system
(Phase 3). No provider code exists yet. Nothing below is implemented.

## Goals

- One shared `ProviderAdapter` interface; one adapter implementation per
  provider (OpenAI, Anthropic, Google Gemini, DeepSeek, xAI, OpenRouter,
  generic OpenAI-compatible endpoints).
- Capability detection: UI only offers features (streaming, tool calling,
  vision, audio) the configured provider + model actually support.
- Per-conversation provider/model selection, per-provider settings, connection
  testing, and safe error surfacing.

## Non-goals for adapters

- Never assume providers share auth schemes, message formats, streaming
  protocols, tool-call schemas, or error shapes. Each adapter owns its mapping.
- No API key is ever embedded in the app, logged, or sent anywhere except the
  provider endpoint the user configured.

## Credential storage (security-critical)

- API keys are encrypted at rest with keys held in the Android Keystore
  (Tink is the planned primitive; `com.google.crypto.tink:tink-android:1.24.0`
  is already in the version catalog).
- Encrypted stores are excluded from device backups (see
  `res/xml/backup_rules.xml` and `SECURITY.md`).
- Documented limitation: on-device encryption raises the bar but cannot make
  keys unextractable from a compromised device.

## Planned validation (Phase 3 acceptance)

- Adapter contract tests with recorded HTTP fixtures per provider.
- Credential redaction tests on logs.
- Streaming, cancellation, timeout, retry, auth-failure, and rate-limit tests.
- UI tests for add/edit/delete/test of provider configurations.
