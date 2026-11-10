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
        var checks = 0
        var store: UsageStore? = null
        fun verify(value: Boolean) { check(value); checks++ }
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
            store.save(choice.copy(id = "unknown", atMillis = 200, remainingSeconds = null, source = ChargeTimeSource.UNKNOWN))
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
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "PASS: $checks SQLite persistence/privacy checks") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("result", "FAIL after $checks checks: ${error.javaClass.simpleName}") })
        } finally {
            store?.close()
            targetContext.deleteDatabase(name)
        }
    }
}
