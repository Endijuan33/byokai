package com.byokai.data.local.db.migration

/**
 * Schema migration v1 -> v2 (Milestone 4A).
 *
 * Changes:
 * 1. `conversations` gains a nullable `system_prompt TEXT` column for
 *    per-conversation system instructions (Milestone 4C). Existing rows
 *    keep NULL — no data rewrite, so the ALTER is instantaneous even on
 *    large histories.
 * 2. New `prompts` table for reusable prompt templates:
 *    `prompts(id TEXT PRIMARY KEY, title TEXT, content TEXT,
 *    is_builtin INTEGER DEFAULT 0, created_at INTEGER, updated_at
 *    INTEGER)`. Starts empty; built-in templates are seeded by the
 *    repository layer on first use (Milestone 4C), not by the migration
 *    — migrations must stay free of product content.
 *
 * Both statements are additive; no existing table, column, index, or
 * row is modified or dropped.
 */
object MigrationV1ToV2 : DatabaseMigration {
    override val fromVersion: Int = 1
    override val toVersion: Int = 2

    override fun migrate(db: DbHandle) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN system_prompt TEXT")
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
    }
}
