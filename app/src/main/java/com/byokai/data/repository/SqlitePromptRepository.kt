package com.byokai.data.repository

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.byokai.core.common.Result
import com.byokai.domain.model.BuiltinPrompts
import com.byokai.domain.model.PromptTemplate
import com.byokai.domain.repository.PromptRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * [PromptRepository] over the `prompts` table (schema v2, Milestone 4C).
 *
 * Same hand-rolled SQLite approach as [SqliteConversationRepository]:
 * a refresh signal re-queries on every successful write, all IO on
 * [Dispatchers.IO]. Like its sibling, this class touches
 * `SupportSQLiteDatabase` and therefore cannot be instantiated on the
 * JVM — no JVM unit tests; the pure parts ([BuiltinPrompts] seeds,
 * validation) are tested instead.
 */
class SqlitePromptRepository(
    private val helper: SupportSQLiteOpenHelper,
) : PromptRepository {

    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun prompts(): Flow<List<PromptTemplate>> =
        refresh.onStart { emit(Unit) }.flatMapLatest {
            flow { emit(withContext(Dispatchers.IO) { queryPrompts() }) }
        }

    override suspend fun get(id: String): PromptTemplate? =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.query(
                "SELECT id, title, content, is_builtin, created_at, updated_at " +
                    "FROM prompts WHERE id = ?",
                arrayOf<Any?>(id),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                readPrompt(cursor)
            }
        }

    override suspend fun ensureSeeded(): Result<Unit> = ioWrite {
        val db = helper.writableDatabase
        val count = db.query("SELECT COUNT(*) AS n FROM prompts").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(cursor.getColumnIndexOrThrow("n"))
        }
        if (count > 0) return@ioWrite
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (seed in BuiltinPrompts.all) {
                db.execSQL(
                    "INSERT INTO prompts (id, title, content, is_builtin, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        UUID.randomUUID().toString(),
                        seed.title,
                        seed.content,
                        1,
                        now,
                        now,
                    ),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun create(title: String, content: String): Result<PromptTemplate> = ioWrite {
        validate(title, content)
        val now = System.currentTimeMillis()
        val prompt = PromptTemplate(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            content = content.trim(),
            isBuiltin = false,
            createdAt = now,
            updatedAt = now,
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO prompts (id, title, content, is_builtin, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                prompt.id,
                prompt.title,
                prompt.content,
                0,
                prompt.createdAt,
                prompt.updatedAt,
            ),
        )
        prompt
    }

    override suspend fun update(id: String, title: String, content: String): Result<Unit> = ioWrite {
        validate(title, content)
        helper.writableDatabase.execSQL(
            "UPDATE prompts SET title = ?, content = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(title.trim(), content.trim(), System.currentTimeMillis(), id),
        )
    }

    override suspend fun delete(id: String): Result<Unit> = ioWrite {
        helper.writableDatabase.execSQL(
            "DELETE FROM prompts WHERE id = ?",
            arrayOf<Any?>(id),
        )
    }

    private suspend fun <T> ioWrite(block: () -> T): Result<T> {
        return try {
            val value = withContext(Dispatchers.IO) { block() }
            refresh.tryEmit(Unit)
            Result.Success(value)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private fun queryPrompts(): List<PromptTemplate> =
        helper.readableDatabase.query(
            "SELECT id, title, content, is_builtin, created_at, updated_at FROM prompts " +
                "ORDER BY is_builtin DESC, updated_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(readPrompt(cursor))
                }
            }
        }

    private fun readPrompt(cursor: android.database.Cursor): PromptTemplate {
        val builtinIdx = cursor.getColumnIndexOrThrow("is_builtin")
        return PromptTemplate(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
            isBuiltin = cursor.getLong(builtinIdx) != 0L,
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
        )
    }

    private fun validate(title: String, content: String) {
        require(title.isNotBlank()) { "Prompt title must not be blank" }
        require(content.isNotBlank()) { "Prompt content must not be blank" }
    }
}
