package com.byokai.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.byokai.core.common.Result
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.Conversation
import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.model.Message
import com.byokai.domain.repository.AttachmentStorage
import com.byokai.domain.repository.ConversationRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * [ConversationRepository] backed by hand-rolled SQLite (no Room — KSP
 * has no 2.4.x release for Kotlin 2.4.21).
 *
 * Refresh strategy: a [MutableSharedFlow] "refresh signal" replays one
 * buffered emission to new collectors via [onStart], and every
 * successful write `tryEmit`s on it; [flatMapLatest] re-runs the query
 * per signal. This is process-local reactivity — cross-process changes
 * (another process writing to the same DB file) are NOT observed, which
 * is fine: the app is the only writer.
 *
 * All database work runs on [Dispatchers.IO]; callers may collect from
 * any dispatcher. [appendMessage] inserts the row and bumps the
 * conversation's `updatedAt` inside one SQLite transaction.
 *
 * Degradation policy (mirroring [DataStoreProviderConfigRepository]): a
 * message row whose `role` no longer maps to [ChatRole] is skipped
 * rather than crashing the query.
 *
 * Phase 9B: attachment metadata rows ride along in the same
 * transaction ([appendMessage]). Attachment FILES are deleted
 * best-effort (never transactionally) when their rows go away —
 * via [delete] and [deleteMessagesFrom] — using [attachmentStorage].
 * A leftover file is harmless: private dir, UUID names, never served
 * without its metadata row.
 *
 * This class is Android-only: [SupportSQLiteOpenHelper] cannot be
 * instantiated on the JVM, so there are no JVM unit tests for this
 * file — ViewModel tests use an in-memory fake of
 * [ConversationRepository] instead. This is stated honestly, not as a
 * gap to quietly ignore.
 */
class SqliteConversationRepository(
    private val helper: SupportSQLiteOpenHelper,
    private val attachmentStorage: AttachmentStorage,
) : ConversationRepository {

    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun conversations(): Flow<List<Conversation>> =
        refresh.onStart { emit(Unit) }.flatMapLatest {
            flow { emit(withContext(Dispatchers.IO) { queryConversations() }) }
        }

    override suspend fun get(id: String): Conversation? =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.query(
                "SELECT id, title, created_at, updated_at, system_prompt FROM conversations WHERE id = ?",
                arrayOf<Any?>(id),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                Conversation(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                    systemPrompt = readNullableString(cursor, "system_prompt"),
                )
            }
        }

    override suspend fun create(title: String): Result<Conversation> = ioWrite {
        val now = System.currentTimeMillis()
        val conversation = Conversation(UUID.randomUUID().toString(), title, now, now)
        helper.writableDatabase.execSQL(
            "INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(conversation.id, title, now, now),
        )
        conversation
    }

    override suspend fun delete(id: String): Result<Unit> {
        // Attachment rows go with the messages via ON DELETE CASCADE;
        // their FILES are deleted best-effort afterwards (files are not
        // transactional). Collected before the delete, inside ioWrite so
        // the read sees a consistent snapshot.
        val doomed = ioWrite {
            val fileNames = helper.readableDatabase.query(
                "SELECT a.file_name FROM attachments a " +
                    "JOIN messages m ON m.id = a.message_id " +
                    "WHERE m.conversation_id = ?",
                arrayOf<Any?>(id),
            ).use { cursor ->
                val idx = cursor.getColumnIndexOrThrow("file_name")
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(idx))
                }
            }
            // Messages go with it via ON DELETE CASCADE (foreign keys are
            // enabled in ByokaiDatabase.onConfigure).
            helper.writableDatabase.execSQL(
                "DELETE FROM conversations WHERE id = ?",
                arrayOf<Any?>(id),
            )
            fileNames
        }
        return when (doomed) {
            is Result.Success -> {
                doomed.data.forEach {
                    runCatching { attachmentStorage.delete(it) }
                }
                Result.Success(Unit)
            }
            is Result.Error -> doomed
            is Result.Loading -> doomed
        }
    }

    override suspend fun rename(id: String, title: String): Result<Unit> = ioWrite {
        helper.writableDatabase.execSQL(
            "UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(title, System.currentTimeMillis(), id),
        )
    }

    override suspend fun setSystemPrompt(id: String, systemPrompt: String?): Result<Unit> = ioWrite {
        helper.writableDatabase.execSQL(
            "UPDATE conversations SET system_prompt = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(systemPrompt, System.currentTimeMillis(), id),
        )
    }

    override fun messages(conversationId: String): Flow<List<Message>> =
        refresh.onStart { emit(Unit) }.flatMapLatest {
            flow { emit(withContext(Dispatchers.IO) { queryMessages(conversationId) }) }
        }

    override fun searchConversations(query: String): Flow<List<Conversation>> {
        if (query.isBlank()) return conversations()
        return refresh.onStart { emit(Unit) }.flatMapLatest {
            flow { emit(withContext(Dispatchers.IO) { querySearchConversations(query) }) }
        }
    }

    override suspend fun deleteMessagesFrom(
        conversationId: String,
        messageId: String,
    ): Result<Unit> {
        // Attachment rows in the doomed range go away via ON DELETE
        // CASCADE; their FILES are collected first and deleted
        // best-effort afterwards (files are not transactional).
        val doomed = ioWrite {
            val db = helper.writableDatabase
            val fileNames = attachmentFileNamesInRange(db, conversationId, messageId)
            db.beginTransaction()
            try {
                // rowid >= the target's rowid deletes the target row and
                // every row inserted after it (insertion order is the turn
                // order). rowid is monotonic per insert, so unlike
                // created_at it cannot collide when two appends land in the
                // same millisecond. When messageId does not exist the
                // subquery yields NULL, `>= NULL` is never true, and nothing
                // is deleted.
                db.execSQL(
                    "DELETE FROM messages WHERE conversation_id = ? AND rowid >= " +
                        "(SELECT rowid FROM messages WHERE id = ? AND conversation_id = ?)",
                    arrayOf<Any?>(conversationId, messageId, conversationId),
                )
                db.execSQL(
                    "UPDATE conversations SET updated_at = ? WHERE id = ?",
                    arrayOf<Any?>(System.currentTimeMillis(), conversationId),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            fileNames
        }
        return when (doomed) {
            is Result.Success -> {
                doomed.data.forEach {
                    runCatching { attachmentStorage.delete(it) }
                }
                Result.Success(Unit)
            }
            is Result.Error -> doomed
            is Result.Loading -> doomed
        }
    }

    override suspend fun appendMessage(
        conversationId: String,
        role: ChatRole,
        content: String,
        isPartial: Boolean,
        attachments: List<ImageAttachment>,
    ): Result<Message> = ioWrite {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = now,
            isPartial = isPartial,
            attachments = attachments,
        )
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO messages (id, conversation_id, role, content, created_at, is_partial) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    message.id,
                    conversationId,
                    role.name,
                    content,
                    now,
                    if (isPartial) 1 else 0,
                ),
            )
            // Attachment files already exist in AttachmentStorage
            // (written at pick time); only metadata rows are inserted,
            // atomically with the message.
            for (attachment in attachments) {
                db.execSQL(
                    "INSERT INTO attachments (id, message_id, file_name, mime_type, width, height) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        attachment.id,
                        message.id,
                        attachment.fileName,
                        attachment.mimeType,
                        attachment.width,
                        attachment.height,
                    ),
                )
            }
            db.execSQL(
                "UPDATE conversations SET updated_at = ? WHERE id = ?",
                arrayOf<Any?>(now, conversationId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        message
    }

    override suspend fun markComplete(messageId: String): Result<Unit> = ioWrite {
        helper.writableDatabase.execSQL(
            "UPDATE messages SET is_partial = 0 WHERE id = ?",
            arrayOf<Any?>(messageId),
        )
    }

    /**
     * Runs [block] on [Dispatchers.IO], emits the refresh signal on
     * success, and wraps any failure in [Result.Error]. The signal is
     * emitted only for successful writes so a failed write never
     * triggers a pointless re-query.
     */
    private suspend fun <T> ioWrite(block: () -> T): Result<T> {
        return try {
            val value = withContext(Dispatchers.IO) { block() }
            refresh.tryEmit(Unit)
            Result.Success(value)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private fun queryConversations(): List<Conversation> =
        helper.readableDatabase.query(
            "SELECT id, title, created_at, updated_at, system_prompt FROM conversations ORDER BY updated_at DESC",
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val titleIdx = cursor.getColumnIndexOrThrow("title")
            val createdIdx = cursor.getColumnIndexOrThrow("created_at")
            val updatedIdx = cursor.getColumnIndexOrThrow("updated_at")
            val promptIdx = cursor.getColumnIndexOrThrow("system_prompt")
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Conversation(
                            id = cursor.getString(idIdx),
                            title = cursor.getString(titleIdx),
                            createdAt = cursor.getLong(createdIdx),
                            updatedAt = cursor.getLong(updatedIdx),
                            systemPrompt = if (cursor.isNull(promptIdx)) null
                            else cursor.getString(promptIdx),
                        ),
                    )
                }
            }
        }

    /**
     * Title-or-content substring search (case-insensitive LIKE).
     * The LIKE pattern is passed as a bound argument — never
     * interpolated — so query text cannot inject SQL. LIKE wildcards
     * (`%`, `_`) inside the query are escaped.
     */
    private fun querySearchConversations(query: String): List<Conversation> {
        val pattern = "%" + query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"
        return helper.readableDatabase.query(
            "SELECT DISTINCT c.id, c.title, c.created_at, c.updated_at, c.system_prompt " +
                "FROM conversations c LEFT JOIN messages m ON m.conversation_id = c.id " +
                "WHERE c.title LIKE ? ESCAPE '\\' OR m.content LIKE ? ESCAPE '\\' " +
                "ORDER BY c.updated_at DESC",
            arrayOf<Any?>(pattern, pattern),
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val titleIdx = cursor.getColumnIndexOrThrow("title")
            val createdIdx = cursor.getColumnIndexOrThrow("created_at")
            val updatedIdx = cursor.getColumnIndexOrThrow("updated_at")
            val promptIdx = cursor.getColumnIndexOrThrow("system_prompt")
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Conversation(
                            id = cursor.getString(idIdx),
                            title = cursor.getString(titleIdx),
                            createdAt = cursor.getLong(createdIdx),
                            updatedAt = cursor.getLong(updatedIdx),
                            systemPrompt = if (cursor.isNull(promptIdx)) null
                            else cursor.getString(promptIdx),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Nullable TEXT read that tolerates the column holding SQL NULL
     * (pre-migration rows always do).
     */
    private fun readNullableString(
        cursor: android.database.Cursor,
        column: String,
    ): String? {
        val idx = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(idx)) null else cursor.getString(idx)
    }

    private fun queryMessages(conversationId: String): List<Message> {
        val db = helper.readableDatabase
        val messages = db.query(
            "SELECT id, conversation_id, role, content, created_at, is_partial " +
                "FROM messages WHERE conversation_id = ? ORDER BY created_at ASC",
            arrayOf<Any?>(conversationId),
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val convIdx = cursor.getColumnIndexOrThrow("conversation_id")
            val roleIdx = cursor.getColumnIndexOrThrow("role")
            val contentIdx = cursor.getColumnIndexOrThrow("content")
            val createdIdx = cursor.getColumnIndexOrThrow("created_at")
            val partialIdx = cursor.getColumnIndexOrThrow("is_partial")
            buildList {
                while (cursor.moveToNext()) {
                    val role = runCatching {
                        ChatRole.valueOf(cursor.getString(roleIdx))
                    }.getOrNull() ?: continue
                    add(
                        Message(
                            id = cursor.getString(idIdx),
                            conversationId = cursor.getString(convIdx),
                            role = role,
                            content = cursor.getString(contentIdx),
                            createdAt = cursor.getLong(createdIdx),
                            isPartial = cursor.getInt(partialIdx) != 0,
                        ),
                    )
                }
            }
        }
        if (messages.isEmpty()) return messages
        val attachmentsByMessage = queryAttachments(db, messages.map { it.id })
        return messages.map { message ->
            message.copy(attachments = attachmentsByMessage[message.id].orEmpty())
        }
    }

    /**
     * Batch-loads attachment metadata for [messageIds] in one query per
     * 500-id chunk (SQLite bind-variable cap), keyed by message id.
     */
    private fun queryAttachments(
        db: SupportSQLiteDatabase,
        messageIds: List<String>,
    ): Map<String, List<ImageAttachment>> {
        val out = mutableMapOf<String, MutableList<ImageAttachment>>()
        for (chunk in messageIds.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.query(
                "SELECT id, message_id, file_name, mime_type, width, height " +
                    "FROM attachments WHERE message_id IN ($placeholders)",
                chunk.toTypedArray(),
            ).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("id")
                val msgIdx = cursor.getColumnIndexOrThrow("message_id")
                val fileIdx = cursor.getColumnIndexOrThrow("file_name")
                val mimeIdx = cursor.getColumnIndexOrThrow("mime_type")
                val wIdx = cursor.getColumnIndexOrThrow("width")
                val hIdx = cursor.getColumnIndexOrThrow("height")
                while (cursor.moveToNext()) {
                    val attachment = ImageAttachment(
                        id = cursor.getString(idIdx),
                        fileName = cursor.getString(fileIdx),
                        mimeType = cursor.getString(mimeIdx),
                        width = cursor.getInt(wIdx),
                        height = cursor.getInt(hIdx),
                    )
                    out.getOrPut(cursor.getString(msgIdx)) { mutableListOf() }
                        .add(attachment)
                }
            }
        }
        return out
    }

    /**
     * File names of attachments whose messages fall in the
     * [deleteMessagesFrom] doomed range. Read BEFORE the delete so the
     * files can be cleaned up best-effort afterwards.
     */
    private fun attachmentFileNamesInRange(
        db: SupportSQLiteDatabase,
        conversationId: String,
        messageId: String,
    ): List<String> =
        db.query(
            "SELECT a.file_name FROM attachments a " +
                "JOIN messages m ON m.id = a.message_id " +
                "WHERE m.conversation_id = ? AND m.rowid >= " +
                "(SELECT rowid FROM messages WHERE id = ? AND conversation_id = ?)",
            arrayOf<Any?>(conversationId, messageId, conversationId),
        ).use { cursor ->
            val idx = cursor.getColumnIndexOrThrow("file_name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(idx))
            }
        }
}
