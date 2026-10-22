package com.byokai.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.byokai.data.local.db.migration.MigrationRunner
import com.byokai.data.local.db.migration.MigrationV1ToV2
import com.byokai.data.local.db.migration.MigrationV2ToV3

/**
 * Process-wide access to the BYOKAI SQLite database (`byokai.db`).
 *
 * Mirrors the DataStore holder pattern (see the holders under
 * `data/local/datastore/`):
 * a single [SupportSQLiteOpenHelper] per process, created through
 * [FrameworkSQLiteOpenHelperFactory]. No Room — KSP has no 2.4.x release
 * for Kotlin 2.4.21, so all SQL here is hand-rolled.
 *
 * Schema (version 3):
 * - `conversations(id TEXT PRIMARY KEY, title TEXT, created_at INTEGER,
 *   updated_at INTEGER, system_prompt TEXT)` — `system_prompt` added in
 *   v2 (Milestone 4A) for per-conversation system instructions; NULL
 *   for pre-existing rows.
 * - `messages(id TEXT PRIMARY KEY, conversation_id TEXT REFERENCES
 *   conversations(id) ON DELETE CASCADE, role TEXT, content TEXT,
 *   created_at INTEGER, is_partial INTEGER DEFAULT 0)`
 * - `prompts(id TEXT PRIMARY KEY, title TEXT, content TEXT, is_builtin
 *   INTEGER DEFAULT 0, created_at INTEGER, updated_at INTEGER)` — added
 *   in v2 (Milestone 4A) for reusable prompt templates; starts empty,
 *   seeded by the repository layer, not by the migration.
 * - `attachments(id TEXT PRIMARY KEY, message_id TEXT REFERENCES
 *   messages(id) ON DELETE CASCADE, file_name TEXT, mime_type TEXT,
 *   width INTEGER, height INTEGER)` — added in v3 (Phase 9B) for
 *   image-attachment metadata. The raw bytes live in AttachmentStorage
 *   (app-private files), never in this database.
 * - index `idx_messages_conversation` on `messages(conversation_id,
 *   created_at)`.
 * - index `idx_attachments_message` on `attachments(message_id)`.
 *
 * Foreign-key constraints are enabled in [onConfigure], so deleting a
 * conversation cascades to its messages.
 *
 * Migrations are version-chained and transactional: [onUpgrade]
 * delegates to [MigrationRunner], which applies every registered
 * migration between the old and new versions inside a single
 * transaction. A missing migration fails fast and rolls back rather
 * than leaving a half-migrated database. Destructive migrations are
 * forbidden — conversation history is user data.
 *
 * Security boundary: API keys are NEVER stored in this database. The
 * tables hold conversation text and metadata only; provider config ids
 * may appear in memory at the repository layer but are never persisted
 * here. Key material lives exclusively in the Tink-backed
 * [com.byokai.domain.security.CredentialStore].
 */
object ByokaiDatabase {

    const val DATABASE_NAME = "byokai.db"
    const val DATABASE_VERSION = 3

    /**
     * Every schema migration ever shipped, in version order. The runner
     * walks the chain; keep this list append-only — never remove or
     * reorder entries, or upgrades from old installs break.
     */
    private val migrations = listOf(
        MigrationV1ToV2,
        MigrationV2ToV3,
    )

    @Volatile
    private var instance: SupportSQLiteOpenHelper? = null

    /**
     * Returns the process-wide [SupportSQLiteOpenHelper], creating it on
     * first call. Thread-safe via double-checked locking.
     */
    fun get(context: Context): SupportSQLiteOpenHelper =
        instance ?: synchronized(this) {
            instance ?: FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context.applicationContext)
                    .name(DATABASE_NAME)
                    .callback(Callback())
                    .build(),
            ).also { instance = it }
        }

    private class Callback : SupportSQLiteOpenHelper.Callback(DATABASE_VERSION) {

        override fun onConfigure(db: SupportSQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        /**
         * Creates the CURRENT schema (v3) directly. Fresh installs never
         * run migrations; [onUpgrade] handles existing v1/v2 databases.
         */
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE conversations (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    system_prompt TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE messages (
                    id TEXT PRIMARY KEY NOT NULL,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    is_partial INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE prompts (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    is_builtin INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX idx_messages_conversation " +
                    "ON messages(conversation_id, created_at)",
            )
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

        override fun onUpgrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            MigrationRunner.migrate(
                SupportDbHandle(db),
                migrations,
                oldVersion,
                newVersion,
            )
        }
    }
}
