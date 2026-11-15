package com.binteum.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.binteum.core.*

/** Local category choices only; excluded from cloud/device backup by the app's existing rules. */
class UsageStore(context: Context, name: String = "charging_choices.db") : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE choices (
            id TEXT PRIMARY KEY, at_ms INTEGER NOT NULL, category TEXT NOT NULL,
            entry_point TEXT NOT NULL, remaining_seconds INTEGER, source TEXT NOT NULL,
            soc INTEGER, target INTEGER, simulation INTEGER NOT NULL,
            duration_seconds INTEGER, completed INTEGER, rating TEXT, available_seconds INTEGER)""")
        db.execSQL("CREATE INDEX choices_time ON choices(at_ms)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE choices ADD COLUMN available_seconds INTEGER")
            // Preserve legacy context; past manual limits were not recorded and cannot be reconstructed.
            db.execSQL("UPDATE choices SET available_seconds = remaining_seconds")
        }
    }

    fun save(event: UsageEvent) {
        val safe = UsagePreferences.record(emptyList(), event).singleOrNull() ?: return
        val values = ContentValues().apply {
            put("id", safe.id); put("at_ms", safe.atMillis); put("category", safe.category.name)
            put("entry_point", safe.entryPoint.name); put("remaining_seconds", safe.remainingSeconds)
            put("source", safe.source.name); put("soc", safe.stateOfCharge); put("target", safe.targetPercent)
            put("simulation", if (safe.simulation) 1 else 0); put("duration_seconds", safe.durationSeconds)
            put("completed", safe.completed?.let { if (it) 1 else 0 }); put("rating", safe.rating?.name)
            put("available_seconds", safe.availableSeconds)
        }
        writableDatabase.insertWithOnConflict("choices", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Bounded ranking cache; older history stays in the database until the user clears it. */
    fun recent(limit: Int = 4000): List<UsageEvent> = readableDatabase.query(
        "choices", null, null, null, null, null, "at_ms DESC", limit.coerceIn(1, 10000).toString()
    ).use { cursor ->
        val result = mutableListOf<UsageEvent>()
        fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun integer(name: String): Int? = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getInt(it) }
        while (cursor.moveToNext()) runCatching {
            UsageEvent(string("id"), cursor.getLong(cursor.getColumnIndexOrThrow("at_ms")),
                UsageCategory.valueOf(string("category")), UsageEntryPoint.valueOf(string("entry_point")),
                integer("remaining_seconds"), ChargeTimeSource.valueOf(string("source")), integer("soc"),
                integer("target"), integer("simulation") == 1, integer("duration_seconds"), integer("completed")?.let { it == 1 }, cursor.getColumnIndexOrThrow("rating").let { index ->
                    if (cursor.isNull(index)) null else ActivityRating.valueOf(cursor.getString(index)) }, integer("available_seconds"))
        }.getOrNull()?.let(result::add)
        result.reversed()
    }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM choices", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0
    }
    fun clear() { writableDatabase.delete("choices", null, null) }
}
