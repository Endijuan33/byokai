package com.byokai.data.local.db.migration

/**
 * Minimal database handle abstraction for schema migrations.
 *
 * Migrations are written against this interface — never against
 * `SupportSQLiteDatabase` directly — so the exact same migration code
 * that runs on Android can be executed on the plain JVM in unit tests
 * (backed by a real SQLite engine via `org.xerial:sqlite-jdbc`,
 * test-scoped). Production wraps `SupportSQLiteDatabase`
 * ([com.byokai.data.local.db.SupportDbHandle]); tests wrap a JDBC
 * connection.
 *
 * Values are bound positionally (`?` placeholders). Column values read
 * back through [query] are mapped to Kotlin types: NULL → null, INTEGER
 * → Long, FLOAT → Double, TEXT → String, BLOB → ByteArray.
 */
interface DbHandle {
    fun execSQL(sql: String, args: Array<Any?> = emptyArray())
    fun query(sql: String, args: Array<Any?> = emptyArray()): List<Map<String, Any?>>
    fun beginTransaction()
    fun setTransactionSuccessful()
    fun endTransaction()
}

/**
 * One version-to-version schema migration.
 *
 * Migrations must be additive and idempotent-friendly: they run exactly
 * once per database (guarded by the version walk in [MigrationRunner]),
 * in a single transaction. Never drop user data — destructive
 * migrations are forbidden without a documented recovery path.
 */
interface DatabaseMigration {
    /** Schema version this migration upgrades FROM. */
    val fromVersion: Int

    /** Schema version this migration upgrades TO (must be [fromVersion] + 1). */
    val toVersion: Int

    /** Applies the schema change. Called inside a transaction. */
    fun migrate(db: DbHandle)
}

/**
 * Applies the [migrations] needed to walk a database from [oldVersion]
 * to [newVersion], in one transaction.
 *
 * The chain must be gapless: every integer version in
 * `(oldVersion, newVersion]` needs exactly one migration whose
 * [DatabaseMigration.fromVersion] matches the current step. A missing
 * or mis-sequenced migration fails fast with [IllegalStateException]
 * and rolls everything back — a half-migrated database is never left
 * behind.
 */
object MigrationRunner {

    fun migrate(
        db: DbHandle,
        migrations: List<DatabaseMigration>,
        oldVersion: Int,
        newVersion: Int,
    ) {
        require(oldVersion < newVersion) {
            "Migration requires oldVersion < newVersion, got $oldVersion -> $newVersion"
        }
        val byFromVersion = migrations.associateBy { it.fromVersion }
        db.beginTransaction()
        try {
            var current = oldVersion
            while (current < newVersion) {
                val migration = byFromVersion[current]
                    ?: throw IllegalStateException(
                        "No migration registered from schema version $current " +
                            "(target $newVersion). Refusing to leave a half-migrated database.",
                    )
                require(migration.toVersion == current + 1) {
                    "Migration ${migration.fromVersion}->${migration.toVersion} breaks " +
                        "the version chain at $current"
                }
                migration.migrate(db)
                current = migration.toVersion
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
