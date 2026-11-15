package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.binteum.core.*

/** Only a named test database; never edits user preferences, vehicle settings or UI. */
class ChargeExperienceProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val name = "charge_experience_probe.db"
        val migrationName = "charge_experience_migration_probe.db"
        var checks = 0
        var store: UsageStore? = null
        fun verify(value: Boolean, description: String = "SQLite persistence") { check(value) { description }; checks++ }
        try {
            targetContext.deleteDatabase(name)
            store = UsageStore(targetContext, name)
            val choice = UsageEvent("own", 100, UsageCategory.REST, UsageEntryPoint.RECOMMENDED,
                1200, ChargeTimeSource.VEHICLE, 65, 80, false)
            store.save(choice)
            store.save(choice.copy(durationSeconds = 180, completed = true, rating = ActivityRating.LIKE))
            verify(store.count() == 1L)
            store.close()
            store = UsageStore(targetContext, name)
            verify(store.recent().single().rating == ActivityRating.LIKE)
            verify(store.recent().single().remainingSeconds == 1200)
            store.save(choice.copy(id = "unknown", atMillis = 200, remainingSeconds = null, source = ChargeTimeSource.UNKNOWN,
                availableSeconds = null))
            verify(store.recent().last().remainingSeconds == null)
            store.save(choice.copy(id = "video", atMillis = 300, category = UsageCategory.VIDEO,
                durationSeconds = 900, completed = true, rating = ActivityRating.LIKE))
            val video = store.recent().last()
            verify(video.durationSeconds == null && video.completed == null && video.rating == null)
            store.save(choice.copy(id = "demo", atMillis = 400, simulation = true))
            verify(store.recent().last().simulation)
            verify(store.recent(2).size == 2 && store.count() == 4L)
            store.clear()
            verify(store.count() == 0L)
            store.close()
            store = null

            // Recreate the actual v1 schema before opening the current helper. This exercises
            // SQLiteOpenHelper's version upgrade, not an onUpgrade call on a fresh v2 table.
            targetContext.deleteDatabase(migrationName)
            targetContext.openOrCreateDatabase(migrationName, android.content.Context.MODE_PRIVATE, null).use { legacy ->
                legacy.execSQL("""CREATE TABLE choices (
                    id TEXT PRIMARY KEY, at_ms INTEGER NOT NULL, category TEXT NOT NULL,
                    entry_point TEXT NOT NULL, remaining_seconds INTEGER, source TEXT NOT NULL,
                    soc INTEGER, target INTEGER, simulation INTEGER NOT NULL,
                    duration_seconds INTEGER, completed INTEGER, rating TEXT)""")
                legacy.execSQL("CREATE INDEX choices_time ON choices(at_ms)")
                legacy.execSQL("INSERT INTO choices VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>("legacy-completed", 100L, "REST", "RECOMMENDED", 1200, "VEHICLE", 65, 80, 0, 180, 1, "LIKE"))
                legacy.execSQL("INSERT INTO choices VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>("legacy-unknown", 200L, "MUSIC", "MANUAL", null, "UNKNOWN", 70, 90, 1, null, null, null))
                legacy.version = 1
            }
            store = UsageStore(targetContext, migrationName)
            val migrated = store.recent()
            verify(store.readableDatabase.version == 2, "v1 database upgrades to v2")
            verify(store.count() == 2L && migrated.size == 2, "migration retains all legacy records")
            val oldCompleted = migrated.first { it.id == "legacy-completed" }
            verify(oldCompleted.remainingSeconds == 1200 && oldCompleted.availableSeconds == 1200,
                "legacy known charge time becomes its effective budget")
            verify(oldCompleted.source == ChargeTimeSource.VEHICLE && oldCompleted.entryPoint == UsageEntryPoint.RECOMMENDED,
                "migration retains original source and entry point")
            verify(oldCompleted.durationSeconds == 180 && oldCompleted.completed == true && oldCompleted.rating == ActivityRating.LIKE,
                "migration retains completion and feedback")
            val oldUnknown = migrated.first { it.id == "legacy-unknown" }
            verify(oldUnknown.remainingSeconds == null && oldUnknown.availableSeconds == null && oldUnknown.source == ChargeTimeSource.UNKNOWN,
                "migration does not invent a budget for an unknown legacy estimate")
            verify(oldUnknown.stateOfCharge == 70 && oldUnknown.targetPercent == 90 && oldUnknown.simulation,
                "migration retains charging context and simulation flag")

            val timerChoice = UsageEvent("timer-without-eta", 300, UsageCategory.MUSIC, UsageEntryPoint.MANUAL,
                null, ChargeTimeSource.UNKNOWN, 65, 80, false, availableSeconds = 1200)
            val limitedNap = UsageEvent("shorter-wait", 400, UsageCategory.NAP, UsageEntryPoint.MANUAL,
                1800, ChargeTimeSource.VEHICLE, 65, 80, false,
                durationSeconds = 240, completed = true, availableSeconds = 300)
            store.save(timerChoice)
            store.save(limitedNap)
            store.close()
            store = UsageStore(targetContext, migrationName)
            val reloaded = store.recent().associateBy { it.id }
            verify(store.count() == 4L, "reopening preserves migrated and newly saved records")
            verify(reloaded[oldCompleted.id] == oldCompleted && reloaded[oldUnknown.id] == oldUnknown,
                "legacy records remain unchanged after v2 writes and reopen")
            verify(reloaded[timerChoice.id] == timerChoice,
                "manual time budget roundtrips without fabricating a raw charging ETA")
            verify(reloaded[limitedNap.id] == limitedNap,
                "shorter available time roundtrips separately from vehicle ETA and completion")
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "PASS: $checks SQLite persistence/privacy checks") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("result", "FAIL after $checks checks: ${error.javaClass.simpleName}") })
        } finally {
            store?.close()
            targetContext.deleteDatabase(name)
            targetContext.deleteDatabase(migrationName)
        }
    }
}
