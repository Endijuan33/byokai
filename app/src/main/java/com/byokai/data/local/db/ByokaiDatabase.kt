package com.byokai.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Process-wide access to the BYOKAI SQLite database (`byokai.db`).
 *
 * Mirrors the DataStore holder pattern (see the holders under
 * `data/local/datastore/`):
 * a single [SupportSQLiteOpenHelper] per process, created through
 * [FrameworkSQLiteOpenHelperFactory]. No Room — KSP has no 2.4.x release
 * for Kotlin 2.4.21, so all SQL here is hand-rolled.
 *
 * Schema (version 1):
 * - `conversations(id TEXT PRIMARY KEY, title TEXT, created_at INTEGER,
 *   updated_at INTEGER)`
 * - `messages(id TEXT PRIMARY KEY, conversation_id TEXT REFERENCES
 *   conversations(id) ON DELETE CASCADE, role TEXT, content TEXT,
 *   created_at INTEGER, is_partial INTEGER DEFAULT 0)`
 * - index `idx_messages_conversation` on `messages(conversation_id,
 *   created_at)`.
 *
 * Foreign-key constraints are enabled in [onConfigure], so deleting a
 * conversation cascades to its messages.
 *
 * Version 1 has NO migration yet ([onUpgrade] is a documented no-op);
 * the first schema change must add a real migration path.
 *
 * Security boundary: API keys are NEVER stored in this database. The
 * tables hold conversation text and metadata only; provider config ids
 * may appear in memory at the repository layer but are never persisted
 * here. Key material lives exclusively in the Tink-backed
 * [com.byokai.domain.security.CredentialStore].
 */
object ByokaiDatabase {

    const val DATABASE_NAME = "byokai.db"
    const val DATABASE_VERSION = 1

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

        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE conversations (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
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
                "CREATE INDEX idx_messages_conversation " +
                    "ON messages(conversation_id, created_at)",
            )
        }

        /**
         * No migration yet: version 1 is the first schema. The first
         * schema change MUST implement a real migration here instead of
         * dropping tables — conversation history is user data.
         */
        override fun onUpgrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            // Intentional no-op for v1.
        }
    }
}
