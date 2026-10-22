# Conversation Export / Import Format

Milestone 4E, extended in Phase 9B (0.10.0). BYOKAI conversations are
portable as a versioned document. The UI exposes it in Settings → Data
("Export conversations" / "Import conversations") via the Storage
Access Framework (`CreateDocument` / `OpenDocument`) — the app never
touches raw filesystem paths.

Two serializations share one envelope:

- **v1 JSON** — the bare envelope as a `.json` document.
- **v2 ZIP** (Phase 9B) — a `.zip` archive containing `export.json`
  plus one `attachments/<fileName>` entry per image with the raw
  bytes.

## Why a ZIP for v2

Image attachments cannot live in the JSON without either base64
encoding (roughly +33% size bloat on every image, and the whole
document must still parse as one JSON value) or being silently
dropped. The ZIP keeps `export.json` small and human-inspectable
while the raw bytes travel alongside it — no silent bloat of the
JSON, no silent data loss: export fails loudly if an attachment's
bytes cannot be read, and import never fails the whole file for one
bad attachment entry.

## v2 ZIP layout

```
byokai-export-<stamp>.zip
├── export.json            # the envelope, "version": 2
└── attachments/
    ├── <fileName>.jpg     # raw image bytes, one entry per attachment
    └── ...
```

`export.json` has the same envelope shape as v1, with `"version": 2`;
messages now carry an `attachments` array:

```json
{
  "format": "byokai-export",
  "version": 2,
  "exportedAt": 1761087600000,
  "appVersion": "0.10.0",
  "conversations": [
    {
      "title": "Weekend trip ideas",
      "systemPrompt": "You are a concise assistant.",
      "createdAt": 1761087500000,
      "updatedAt": 1761087600000,
      "messages": [
        {
          "role": "USER",
          "content": "What is this?",
          "createdAt": 1761087510000,
          "isPartial": false,
          "attachments": [
            {
              "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "fileName": "3fa85f64-5717-4562-b3fc-2c963f66afa6.jpg",
              "mimeType": "image/jpeg",
              "width": 1024,
              "height": 768
            }
          ]
        }
      ]
    }
  ]
}
```

- `format` must be `"byokai-export"`, otherwise the file is rejected.
- `version` is the format version. Importers accept versions `<=`
  the version they implement and reject anything newer with a
  user-friendly error. Current version: **2**.
- `role` is one of `"USER"`, `"ASSISTANT"`, `"SYSTEM"` (the
  `ChatRole` names). Unknown roles fall back to `"USER"` on import.
- `systemPrompt` is omitted when the conversation has none
  (`explicitNulls = false`).
- `attachments` is omitted (defaults to empty) for v1 exports and
  text-only messages.

## Version compatibility

| Export | Imported by 0.10.0+ | Imported by ≤ 0.9.x (v1 reader) |
|---|---|---|
| v1 JSON (`"version": 1`) | ✅ — messages import without attachments (unchanged behavior) | ✅ |
| v2 JSON (`"version": 2`, from Export → JSON) | ✅ — attachment *metadata* is read; the bytes are not in the file, so messages import without attachments | ❌ rejected by the version gate (`2 > 1`) |
| v2 ZIP (`"version": 2` + `attachments/`) | ✅ — full fidelity, see below | ❌ rejected (not JSON at all) |

The JSON export path deliberately carries attachment metadata but no
bytes: if you need the images, use the ZIP export. Importing a JSON
file never fabricates attachment bytes.

## Validation (import)

1. **Size cap**: inputs over **50 MB** are rejected before parsing.
   For ZIP files the cap applies to the archive size pre-parse, and
   the total *uncompressed* size is additionally capped at 50 MB
   while reading, so a highly compressible archive cannot OOM the
   importer.
2. **Dispatch**: input starting with the ZIP magic `PK\x03\x04` is
   treated as a v2 archive; anything else as JSON.
3. **ZIP structure**: only `export.json` and `attachments/*` entries
   with safe names are read. Entry names containing `..`, empty
   segments, absolute paths, or backslashes are ignored (no path
   traversal); a missing `export.json` rejects the file.
4. **Envelope**: malformed JSON or type mismatches → "Not a valid
   BYOKAI export file".
5. **Format id**: must be `"byokai-export"`.
6. **Version**: must be `<= 2` (the reader's version).

Any validation failure aborts the whole import — nothing is written.

## Import semantics

- Every imported conversation is re-created with a **fresh UUID**;
  exported ids are discarded, so imports never collide with existing
  rows.
- Messages are re-appended in export order; `createdAt` timestamps
  are preserved, `isPartial` flags are preserved.
- **Attachments (v2 ZIP)**: each attachment is copied into the
  app's attachment storage under a **fresh file name**
  (`<new-uuid>.jpg`) and the re-created message references the fresh
  `id`/`fileName` (mime type, width, height preserved). Exported ids
  and file names are never reused.
- A missing or corrupt attachment entry (no such ZIP entry, or zero
  bytes) is **skipped and counted** in
  `ImportSummary.skippedAttachments`; its message still imports
  without it. One bad file never fails the whole import.
- A conversation whose messages fail to append is rolled back
  (deleted, including any attachment files already copied for it)
  and listed as skipped; the rest of the file still imports.
- Importing the same file twice **duplicates** conversations. The UI
  shows a confirmation dialog before importing when conversations
  already exist.

## What is NOT in the export

Conversation text, metadata, and (v2) image attachments only. The
export contains **no** API keys, provider credentials, keystore
material, or provider configurations — the export path
(`ConversationExportManager` → `ConversationRepository` /
`AttachmentStorage`) has no access to the credential store, and
`ByokaiExportTest` asserts the output carries no key-like material.

## Security note

Export files are **plaintext** — JSON documents and ZIP archives
with no encryption (current 0.10.0). Anyone holding the file can
read the conversation text and view the images it contains. Securing
the exported file — where it is stored, how it is shared, when it is
deleted — is the user's responsibility; the app cannot protect a
file once it leaves the device. See [SECURITY.md](SECURITY.md) for
the full security design and caveats.

## Audit notes

- Export/import file content is never written to logs. Import
  failures surface only the validation reason (e.g. "Not a valid
  BYOKAI export file"), never file content.
- `ignoreUnknownKeys = true` lets older readers skip fields added by
  future format versions (the version gate remains authoritative).
