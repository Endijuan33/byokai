package com.byokai.data.local.db

import com.byokai.data.local.db.migration.DatabaseMigration
import com.byokai.data.local.db.migration.DbHandle
import com.byokai.data.local.db.migration.MigrationRunner
import com.byokai.data.local.db.migration.MigrationV1ToV2
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Migration tests running against a REAL SQLite engine on the JVM
 * (`org.xerial:sqlite-jdbc`, test-scoped).
 *
 * The production [SupportDbHandle] cannot be instantiated without
 * Android, so these tests exercise the exact same [DatabaseMigration]
 * objects the app runs, through a JDBC-backed [DbHandle]. The v1
 * schema below is copied verbatim from the v1 `ByokaiDatabase.onCreate`
 * — if production v1 ever differs, this test is wrong, which is why
 * the copy is called out explicitly here.
 */
class MigrationTest {

    private lateinit var connection: Connection
    private lateinit var db: DbHandle

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        db = JdbcDbHandle(connection)
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `v1 to v2 preserves conversations and messages`() {
        createV1Schema()
        insertConversation("c1", "Hello", createdAt = 1000L)
        insertMessage("m1", "c1", "USER", "hi", isPartial = 0)
        insertMessage("m2", "c1", "ASSISTANT", "hello back", isPartial = 1)

        MigrationRunner.migrate(db, listOf(MigrationV1ToV2), oldVersion = 1, newVersion = 2)

        val conversations = db.query("SELECT id, title, system_prompt FROM conversations")
        assertEquals(1, conversations.size)
        assertEquals("c1", conversations[0]["id"])
        assertEquals("Hello", conversations[0]["title"])
        assertNull(conversations[0]["system_prompt"])

        val messages = db.query(
            "SELECT id, role, content, is_partial FROM messages ORDER BY created_at",
        )
        assertEquals(2, messages.size)
        assertEquals("m1", messages[0]["id"])
        assertEquals("USER", messages[0]["role"])
        assertEquals("hello back", messages[1]["content"])
        assertEquals(1L, (messages[1]["is_partial"] as Number).toLong())
    }

    @Test
    fun `v1 to v2 creates empty prompts table and writable system_prompt`() {
        createV1Schema()
        insertConversation("c1", "Hello", createdAt = 1000L)

        MigrationRunner.migrate(db, listOf(MigrationV1ToV2), oldVersion = 1, newVersion = 2)

        // prompts table exists and starts empty.
        val count = db.query("SELECT COUNT(*) AS n FROM prompts")
        assertEquals(0L, (count[0]["n"] as Number).toLong())

        // Round-trip a prompt row through the new table.
        db.execSQL(
            "INSERT INTO prompts (id, title, content, is_builtin, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("p1", "Summarize", "Summarize this", 1, 1000L, 1000L),
        )
        val prompts = db.query("SELECT id, title, is_builtin FROM prompts")
        assertEquals(1, prompts.size)
        assertEquals("Summarize", prompts[0]["title"])
        assertEquals(1L, (prompts[0]["is_builtin"] as Number).toLong())

        // system_prompt is writable and nullable.
        db.execSQL("UPDATE conversations SET system_prompt = ? WHERE id = ?", arrayOf("be brief", "c1"))
        assertEquals(
            "be brief",
            db.query("SELECT system_prompt FROM conversations WHERE id = 'c1'")[0]["system_prompt"],
        )
        db.execSQL("UPDATE conversations SET system_prompt = NULL WHERE id = ?", arrayOf("c1"))
        assertNull(db.query("SELECT system_prompt FROM conversations WHERE id = 'c1'")[0]["system_prompt"])
    }

    @Test
    fun `missing migration in chain fails fast`() {
        createV1Schema()
        try {
            MigrationRunner.migrate(db, emptyList(), oldVersion = 1, newVersion = 2)
            fail("expected IllegalStateException for missing migration")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("No migration registered"))
        }
    }

    @Test
    fun `failing migration rolls back the whole chain`() {
        createV1Schema()
        insertConversation("c1", "Hello", createdAt = 1000L)
        val bad = object : DatabaseMigration {
            override val fromVersion = 1
            override val toVersion = 2
            override fun migrate(db: DbHandle) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN tmp TEXT")
                throw RuntimeException("simulated migration failure")
            }
        }
        try {
            MigrationRunner.migrate(db, listOf(bad), oldVersion = 1, newVersion = 2)
            fail("expected RuntimeException from bad migration")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("simulated migration failure"))
        }
        // The partial ALTER TABLE was rolled back: no tmp column.
        val columns = db.query("PRAGMA table_info(conversations)").map { it["name"] }
        assertTrue(columns.none { it == "tmp" })
        // Original data untouched.
        assertEquals("Hello", db.query("SELECT title FROM conversations")[0]["title"])
    }

    @Test
    fun `runner rejects non-forward version walk`() {
        try {
            MigrationRunner.migrate(db, listOf(MigrationV1ToV2), oldVersion = 2, newVersion = 2)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ---- v1 schema, verbatim copy of the production v1 onCreate ----

    private fun createV1Schema() {
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
            "CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at)",
        )
    }

    private fun insertConversation(id: String, title: String, createdAt: Long) {
        db.execSQL(
            "INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(id, title, createdAt, createdAt),
        )
    }

    private fun insertMessage(
        id: String,
        conversationId: String,
        role: String,
        content: String,
        isPartial: Int,
    ) {
        db.execSQL(
            "INSERT INTO messages (id, conversation_id, role, content, created_at, is_partial) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, conversationId, role, content, 1000L, isPartial),
        )
    }

    /**
     * [DbHandle] over a JDBC connection (test-only). Transaction
     * semantics mirror Android's: [beginTransaction] suspends
     * auto-commit; [endTransaction] commits only when
     * [setTransactionSuccessful] was called, else rolls back.
     */
    private class JdbcDbHandle(private val connection: Connection) : DbHandle {
        private var successful = false

        override fun execSQL(sql: String, args: Array<Any?>) {
            connection.prepareStatement(sql).use { stmt ->
                args.forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
                stmt.execute()
            }
        }

        override fun query(sql: String, args: Array<Any?>): List<Map<String, Any?>> =
            connection.prepareStatement(sql).use { stmt ->
                args.forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
                stmt.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val names = (1..meta.columnCount).map { meta.getColumnName(it) }
                    buildList {
                        while (rs.next()) {
                            add(names.associateWith { name -> rs.getObject(name) })
                        }
                    }
                }
            }

        override fun beginTransaction() {
            successful = false
            connection.autoCommit = false
        }

        override fun setTransactionSuccessful() {
            successful = true
        }

        override fun endTransaction() {
            try {
                if (successful) connection.commit() else connection.rollback()
            } finally {
                successful = false
                connection.autoCommit = true
            }
        }
    }
}
