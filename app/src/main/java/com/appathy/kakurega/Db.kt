package com.appathy.kakurega

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class FileRow(
    val id: Long,
    val slotId: String,
    val origName: String,
    val mime: String,
    val size: Long,
    val addedTs: Long,
    val storedName: String
)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "kakurega.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE files(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "slot_id TEXT NOT NULL," +
                "orig_name TEXT NOT NULL," +
                "mime TEXT NOT NULL," +
                "size INTEGER NOT NULL," +
                "added_ts INTEGER NOT NULL," +
                "stored_name TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // 台帳なのでDROP再作成は禁止。版ごとにALTERを積む
    }

    fun addFile(slot: String, name: String, mime: String, size: Long, stored: String): Long {
        val v = ContentValues()
        v.put("slot_id", slot)
        v.put("orig_name", name)
        v.put("mime", mime)
        v.put("size", size)
        v.put("added_ts", System.currentTimeMillis())
        v.put("stored_name", stored)
        return writableDatabase.insert("files", null, v)
    }

    fun moveFile(id: Long, slot: String) {
        val v = ContentValues()
        v.put("slot_id", slot)
        writableDatabase.update("files", v, "id=?", arrayOf(id.toString()))
    }

    fun deleteFile(id: Long) {
        writableDatabase.delete("files", "id=?", arrayOf(id.toString()))
    }

    fun getFile(id: Long): FileRow? {
        readableDatabase.rawQuery("SELECT * FROM files WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) return rowOf(c)
        }
        return null
    }

    fun filesIn(slot: String): List<FileRow> {
        val out = mutableListOf<FileRow>()
        readableDatabase.rawQuery(
            "SELECT * FROM files WHERE slot_id=? ORDER BY added_ts DESC",
            arrayOf(slot)
        ).use { c ->
            while (c.moveToNext()) out.add(rowOf(c))
        }
        return out
    }

    fun counts(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            "SELECT slot_id, COUNT(*) FROM files GROUP BY slot_id", null
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        return out
    }

    private fun rowOf(c: Cursor): FileRow = FileRow(
        c.getLong(c.getColumnIndexOrThrow("id")),
        c.getString(c.getColumnIndexOrThrow("slot_id")),
        c.getString(c.getColumnIndexOrThrow("orig_name")),
        c.getString(c.getColumnIndexOrThrow("mime")),
        c.getLong(c.getColumnIndexOrThrow("size")),
        c.getLong(c.getColumnIndexOrThrow("added_ts")),
        c.getString(c.getColumnIndexOrThrow("stored_name"))
    )
}
