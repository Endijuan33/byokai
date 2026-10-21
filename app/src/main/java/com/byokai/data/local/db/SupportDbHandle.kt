package com.byokai.data.local.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.byokai.data.local.db.migration.DbHandle

/**
 * [DbHandle] backed by a [SupportSQLiteDatabase] (production path).
 *
 * Android-only: this file is never loaded on the JVM. Transaction calls
 * delegate directly; when used from
 * `SupportSQLiteOpenHelper.Callback.onUpgrade` they nest inside the
 * framework's own transaction, which Android supports (only the
 * outermost `endTransaction` commits).
 */
internal class SupportDbHandle(private val db: SupportSQLiteDatabase) : DbHandle {

    override fun execSQL(sql: String, args: Array<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
    }

    override fun query(sql: String, args: Array<Any?>): List<Map<String, Any?>> =
        db.query(sql, args).use { cursor ->
            val names = Array(cursor.columnCount, cursor::getColumnName)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        names.associateWith { name ->
                            val idx = cursor.getColumnIndexOrThrow(name)
                            readColumn(cursor, idx)
                        },
                    )
                }
            }
        }

    override fun beginTransaction() = db.beginTransaction()

    override fun setTransactionSuccessful() = db.setTransactionSuccessful()

    override fun endTransaction() = db.endTransaction()

    private fun readColumn(cursor: Cursor, idx: Int): Any? = when (cursor.getType(idx)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(idx)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(idx)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(idx)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(idx)
        else -> null
    }
}
