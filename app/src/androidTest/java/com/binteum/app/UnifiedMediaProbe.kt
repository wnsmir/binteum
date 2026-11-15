package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import com.binteum.app.media.WebMediaPanel
import com.binteum.core.SessionStatus
import com.binteum.core.VehicleSnapshot
import com.binteum.core.VideoCatalog
import com.binteum.core.WaitingPlan

/**
 * Exercises the unified host's real media gate without launching an Activity lifecycle.
 * Local thumbnails and a vetoing open callback prevent network/player creation. No preference,
 * usage-history, location, vehicle or account writes are performed.
 */
class UnifiedMediaProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var checks = 0
        var failure: Throwable? = null
        runOnMainSync {
            var panel: WebMediaPanel? = null
            val placeholder = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                val host = MainActivity()
                MainActivity::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(host, targetContext)
                fun set(name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.set(host, value)
                fun refresh() = MainActivity::class.java.getDeclaredMethod("refreshMediaGate")
                    .apply { isAccessible = true }.invoke(host)
                fun verify(value: Boolean, message: String) { check(value) { message }; checks++ }
                val media = WebMediaPanel(targetContext).also { panel = it }
                fun mediaField(name: String) = WebMediaPanel::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(media)
                @Suppress("UNCHECKED_CAST")
                val thumbnails = mediaField("thumbnails") as MutableMap<String, Bitmap>
                VideoCatalog.all.forEach { thumbnails[it.id] = placeholder }
                var openAttempts = 0
                media.onContentOpen = { openAttempts++; false }
                set("media", media)
                set("started", true); set("resumed", true); set("tab", 0)
                set("sourceStartedAt", 0L); set("target", 80)
                set("status", SessionStatus.CHARGING)
                val snapshot = VehicleSnapshot(65, true, false, true,
                    System.currentTimeMillis(), 1800, 80, System.currentTimeMillis())
                set("snapshot", snapshot)

                fun checkDirectChoices(plan: WaitingPlan?, label: String) {
                    set("waitingPlan", plan)
                    refresh()
                    verify(mediaField("browseMode") == true, "$label keeps direct browsing")
                    verify((mediaField("videoChoices") as ViewGroup).childCount == VideoCatalog.all.size,
                        "$label keeps all bundled video choices")
                    for (sample in VideoCatalog.all) {
                        val before = openAttempts
                        verify(!media.selectVideo(sample.id), "$label respects host's deliberate veto")
                        verify(openAttempts == before + 1, "$label allows explicit ${sample.title} through duration filtering")
                    }
                    verify(!media.hasOpenContent, "$label never creates a streaming surface")
                }

                checkDirectChoices(null, "before a waiting plan")
                checkDirectChoices(WaitingPlan(SystemClock.elapsedRealtime(), 60, 65), "one-minute window")
                verify(mediaField("remainingBudgetSeconds") == 60, "short duration remains available for fit advice")
                checkDirectChoices(WaitingPlan(SystemClock.elapsedRealtime() - 2_000, 1, 65), "expired window")
                verify(mediaField("remainingBudgetSeconds") == 0, "zero duration remains available for fit advice")
                checkDirectChoices(WaitingPlan(SystemClock.elapsedRealtime(), null, 65), "live charging estimate")

                for ((label, blocked) in listOf(
                    "driving restriction" to snapshot.copy(uxRestricted = true),
                    "charging stopped" to snapshot.copy(charging = false),
                    "stale vehicle state" to snapshot.copy(observedAtMillis = System.currentTimeMillis() - 60_000),
                )) {
                    set("snapshot", blocked); refresh()
                    val before = openAttempts
                    verify(!media.selectVideo(VideoCatalog.all.first().id), "$label blocks explicit selection")
                    verify(openAttempts == before, "$label blocks before content opens")
                }
            } catch (error: Throwable) {
                failure = error.cause ?: error
            } finally {
                panel?.release()
                placeholder.recycle()
            }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("result", if (failure == null) "PASS: $checks unified media checks (no playback or vehicle operations)"
                else "FAIL after $checks: ${failure?.message}")
        })
    }
}
