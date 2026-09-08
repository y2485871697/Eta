package io.github.mangi.eta.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** 仅在用户授予通知访问后保存有限期通知，内容不进入 Agent 会话持久记录。 */
internal class NotificationHistoryRepository(context: Context) {
    private val database = Database(context.applicationContext)

    fun record(
        key: String,
        packageName: String,
        title: String?,
        text: String?,
        subText: String?,
        postedAt: Long,
    ) {
        if (title.isNullOrBlank() && text.isNullOrBlank() && subText.isNullOrBlank()) return
        val values = ContentValues().apply {
            put("notification_key", key.take(MAX_KEY_CHARS))
            put("package_name", packageName.take(MAX_PACKAGE_CHARS))
            put("title", title.bounded())
            put("text", text.bounded())
            put("sub_text", subText.bounded())
            put("posted_at", postedAt)
        }
        database.writableDatabase.transaction {
            insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            delete(TABLE, "posted_at<?", arrayOf((System.currentTimeMillis() - RETENTION_MS).toString()))
            execSQL(
                "DELETE FROM $TABLE WHERE notification_key NOT IN " +
                    "(SELECT notification_key FROM $TABLE ORDER BY posted_at DESC LIMIT $MAX_RECORDS)",
            )
        }
    }

    fun search(query: String, packageName: String, maxAgeHours: Int, limit: Int): String {
        val clauses = mutableListOf("posted_at>=?")
        val args = mutableListOf((System.currentTimeMillis() - maxAgeHours * HOUR_MS).toString())
        if (packageName.isNotBlank()) {
            clauses += "package_name=?"
            args += packageName
        }
        if (query.isNotBlank()) {
            clauses += "(title LIKE ? ESCAPE '\\' OR text LIKE ? ESCAPE '\\' OR sub_text LIKE ? ESCAPE '\\')"
            val pattern = "%${query.escapeLike()}%"
            repeat(3) { args += pattern }
        }
        val items = JSONArray()
        database.readableDatabase.query(
            TABLE,
            arrayOf("package_name", "title", "text", "sub_text", "posted_at"),
            clauses.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "posted_at DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items.put(
                    JSONObject()
                        .put("package_name", cursor.getString(0))
                        .put("title", cursor.getString(1))
                        .put("text", cursor.getString(2))
                        .put("sub_text", cursor.getString(3))
                        .put("posted_at", cursor.getLong(4)),
                )
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_notification_history")
            .put("items", items)
            .put("count", items.length())
            .put("retention_days", RETENTION_DAYS)
            .put("truncated", items.length() == limit)
            .toString()
    }

    private fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun String?.bounded(): String? = this?.take(MAX_FIELD_CHARS)

    private fun String.escapeLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private class Database(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "notification_key TEXT PRIMARY KEY NOT NULL," +
                    "package_name TEXT NOT NULL," +
                    "title TEXT,text TEXT,sub_text TEXT,posted_at INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX notification_history_posted_at ON $TABLE(posted_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val DATABASE_NAME = "eta_notification_history.db"
        const val TABLE = "notification_history"
        const val MAX_RECORDS = 1_000
        const val RETENTION_DAYS = 7
        const val HOUR_MS = 60L * 60 * 1_000
        const val RETENTION_MS = RETENTION_DAYS * 24L * HOUR_MS
        const val MAX_FIELD_CHARS = 4_000
        const val MAX_KEY_CHARS = 1_000
        const val MAX_PACKAGE_CHARS = 255
    }
}
