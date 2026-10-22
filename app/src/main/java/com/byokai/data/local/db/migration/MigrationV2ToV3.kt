package com.byokai.data.local.db.migration

/**
 * Schema migration v2 -> v3 (Phase 9B: image attachments).
 *
 * Adds the `attachments` table for [com.byokai.domain.model.ImageAttachment]
 * metadata:
 * `attachments(id TEXT PRIMARY KEY, message_id TEXT REFERENCES
 * messages(id) ON DELETE CASCADE, file_name TEXT, mime_type TEXT,
 * width INTEGER, height INTEGER)`.
 *
 * The raw bytes are NOT in the database — they live in
 * [com.byokai.domain.repository.AttachmentStorage] (app-private files).
 * `ON DELETE CASCADE` keeps attachment rows from outliving their
 * message; the repository deletes the files themselves best-effort
 * (files are not transactional).
 *
 * Additive only: no existing table, column, index, or row is touched.
 * Starts empty — existing conversations simply have no attachments.
 */
object MigrationV2ToV3 : DatabaseMigration {
    override val fromVersion: Int = 2
    override val toVersion: Int = 3

    override fun migrate(db: DbHandle) {
        db.execSQL(
            """
            CREATE TABLE attachments (
                id TEXT PRIMARY KEY NOT NULL,
                message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                file_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX idx_attachments_message ON attachments(message_id)",
        )
    }
}
