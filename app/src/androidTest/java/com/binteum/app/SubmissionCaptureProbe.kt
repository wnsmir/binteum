package com.binteum.app

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.binteum.app.ui.ThemeMode
import com.binteum.app.vehicle.ComfortController
import com.binteum.core.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Submission evidence: real app rendering, explicitly simulated vehicle/time, no persistent edits. */
class SubmissionCaptureProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = Bundle()
        var activity: MainActivity? = null
        var code = Activity.RESULT_CANCELED
        try {
            val app = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = app
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            fun get(name: String) = field(name).get(app)
            fun set(name: String, value: Any?) = field(name).set(app, value)
            fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(app)
            fun render() = MainActivity::class.java.getDeclaredMethod("render", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(app, false)
            fun resetSnapshot() {
                val now = System.currentTimeMillis()
                set("snapshot", VehicleSnapshot(65, true, false, true, now, 1800, 80, now))
                set("sourceStartedAt", now - 1)
            }
            fun settle(ms: Long = 500) { waitForIdleSync(); Thread.sleep(ms); waitForIdleSync() }
            val output = File(targetContext.filesDir, "submission-evidence").apply { mkdirs() }
            fun capture(name: String, dialog: Boolean = false) {
                settle()
                val finished = CountDownLatch(1)
                var error: String? = null
                runOnMainSync {
                    resetSnapshot()
                    val window = if (dialog) (get("transientDialog") as Dialog).window!! else app.window
                    val decor = window.decorView
                    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(window, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS) File(output, "$name.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        } else error = "PixelCopy $result"
                        bitmap.recycle(); finished.countDown()
                    }, Handler(Looper.getMainLooper()))
                }
                check(finished.await(10, TimeUnit.SECONDS)) { "Screenshot timeout" }
                check(error == null) { error.orEmpty() }
            }
            fun section(id: Int) {
                runOnMainSync {
                    resetSnapshot()
                    val anchors = get("sectionAnchors") as Map<*, *>
                    val anchor = anchors[id] as View
                    val scroll = get("contentScroll") as ScrollView
                    val rect = android.graphics.Rect(0, 0, anchor.width, anchor.height)
                    scroll.offsetDescendantRectToMyCoords(anchor, rect)
                    scroll.scrollTo(0, rect.top)
                    call("updateSectionDock")
                }
            }
            settle()
            runOnMainSync {
                (get("handler") as Handler).removeCallbacks(get("ticker") as Runnable)
                (get("source") as VehicleDataSource).stop()
                (get("comfort") as ComfortController).stop()
                (get("transientDialog") as? Dialog)?.dismiss()
                set("started", true); set("resumed", true); set("demoMode", true)
                set("status", SessionStatus.CHARGING); set("tab", 0); set("active", null)
                set("recovery", null); set("completed", null); set("waitingPlan", null)
                set("targetMode", false); set("waitMinutes", 20); set("target", 80); set("followVehicleTarget", false)
                set("backgroundMode", BackgroundMode.BLACK); set("themeMode", ThemeMode.DARK)
                set("backgroundMotion", false); set("tocOnRight", true)
                set("nearbyKind", null); set("nearbyNote", "")
                resetSnapshot(); render()
            }
            settle(1800)
            capture("01-home")
            runOnMainSync { resetSnapshot(); call("showWaitSettings") }
            capture("02-timer", dialog = true)
            runOnMainSync {
                (get("transientDialog") as Dialog).dismiss()
                set("waitingPlan", WaitingPlan(SystemClock.elapsedRealtime(), 1200, 65))
                resetSnapshot(); render()
            }
            settle(1500)
            capture("03-waiting")
            section(6)
            capture("04-music")
            runOnMainSync { call("chooseMusicService") }
            capture("05-music-choice", dialog = true)
            runOnMainSync { (get("transientDialog") as Dialog).dismiss() }
            section(4)
            capture("06-nearby")
            section(5)
            capture("07-sleep")
            runOnMainSync {
                set("nap", NapSession.forRemainingTime(1200, SystemClock.elapsedRealtime()))
                set("napRemainingSeconds", 1200)
                set("beforeNapBrightness", app.window.attributes.screenBrightness)
                render()
            }
            capture("09-sleep-mode")
            runOnMainSync { set("nap", null); set("napLabel", null) }
            runOnMainSync { set("tab", 2); resetSnapshot(); render() }
            capture("08-settings")
            report.putString("screens", "9")
            report.putString("path", output.absolutePath)
            report.putString("data", "SIMULATED_VEHICLE_AND_TIMER; actual app Views and PixelCopy; no playback/location requests/motor commands")
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            report.putString("failure", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runOnMainSync { activity?.finish() }
        }
        finish(code, report)
    }
}
