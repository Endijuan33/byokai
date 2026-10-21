# Conversation Export / Import Format

Milestone 4E. BYOKAI conversations are portable as a versioned JSON
document. The UI exposes it in Settings → Data ("Export conversations"
/ "Import conversations") via the Storage Access Framework
(`CreateDocument` / `OpenDocument`) — the app never touches raw
filesystem paths.

## Envelope

```json
{
  "format": "byokai-export",
  "version": 1,
  "exportedAt": 1761087600000,
  "appVersion": "0.4.0",
  "conversations": [
    {
      "title": "Weekend trip ideas",
      "systemPrompt": "You are a concise assistant.",
      "createdAt": 1761087500000,
      "updatedAt": 1761087600000,
      "messages": [
        {
          "role": "USER",
          "content": "Any ideas for a weekend trip?",
          "createdAt": 1761087510000,
          "isPartial": false
        },
        {
          "role": "ASSISTANT",
          "content": "…",
          "createdAt": 1761087590000,
          "isPartial": false
        }
      ]
    }
  ]
}
```

- `format` must be `"byokai-export"`, otherwise the file is rejected.
- `version` is the format version. Importers accept versions `<=`
  the version they implement and reject anything newer with a
  user-friendly error. Current version: **1**.
- `role` is one of `"USER"`, `"ASSISTANT"`, `"SYSTEM"` (the
  `ChatRole` names). Unknown roles fall back to `"USER"` on import.
- `systemPrompt` is omitted when the conversation has none
  (`explicitNulls = false`).

## Validation (import)

1. **Size cap**: inputs over **50 MB** are rejected before parsing.
2. **Structure**: malformed JSON or type mismatches → "Not a valid
   BYOKAI export file".
3. **Format id**: must be `"byokai-export"`.
4. **Version**: must be `<= 1` (the reader's version).

Any failure aborts the whole import — nothing is written.

## Import semantics

- Every imported conversation is re-created with a **fresh UUID**;
  exported ids are discarded, so imports never collide with existing
  rows.
- Messages are re-appended in export order; `createdAt` timestamps
  are preserved, `isPartial` flags are preserved.
- A conversation whose messages fail to append is rolled back
  (deleted) and listed as skipped; the rest of the file still
  imports.
- Importing the same file twice **duplicates** conversations. The UI
  shows a confirmation dialog before importing when conversations
  already exist.

## What is NOT in the export

Conversation text and metadata only. The export contains **no** API
keys, provider credentials, keystore material, or provider
configurations — the export path (`ConversationExportManager` →
`ConversationRepository`) has no access to the credential store, and
`ByokaiExportTest` asserts the output carries no key-like material.

## Security note

Export files are **plaintext JSON** (current 0.6.0). Anyone holding the
file can read the conversation text and prompt templates it contains.
Securing the exported file — where it is stored, how it is shared, when
it is deleted — is the user's responsibility; the app cannot protect a
file once it leaves the device. See [SECURITY.md](SECURITY.md) for the
full security design and caveats.

## Audit notes

- Export/import file content is never written to logs. Import
  failures surface only the validation reason (e.g. "Not a valid
  BYOKAI export file"), never file content.
- `ignoreUnknownKeys = true` lets older readers skip fields added by
  future format versions (the version gate remains authoritative).
