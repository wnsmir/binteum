package com.binteum.app

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.widget.ScrollView
import com.binteum.app.nearby.NearbyCoverage
import com.binteum.app.nearby.NearbyFit
import com.binteum.app.nearby.NearbyKind
import com.binteum.app.nearby.NearbyLookupResult
import com.binteum.app.nearby.NearbyPlace
import com.binteum.app.nearby.NearbyProvider
import com.binteum.app.nearby.VehicleLocationFix
import com.binteum.app.ui.ThemeMode
import com.binteum.app.vehicle.ComfortController
import com.binteum.core.BackgroundMode
import com.binteum.core.SessionStatus
import com.binteum.core.VehicleDataSource
import com.binteum.core.VehicleSnapshot
import com.binteum.core.WaitingPlan
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Actual app layout with explicit in-memory vehicle and sample-place fixtures.
 * No preference-setting actions, location requests, external launches or vehicle commands.
 * Sample cards are labeled as examples; these images are layout evidence, not search evidence.
 */
class DockLayoutCaptureProbe : Instrumentation() {
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
            fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name)
                .apply { isAccessible = true }.invoke(app)
            fun render() = MainActivity::class.java.getDeclaredMethod("render", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(app, false)

            fun settleFrames() {
                waitForIdleSync()
                val drawn = CountDownLatch(1)
                runOnMainSync {
                    var frames = 0
                    val callback = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            if (++frames >= 3) drawn.countDown()
                            else Choreographer.getInstance().postFrameCallback(this)
                        }
                    }
                    Choreographer.getInstance().postFrameCallback(callback)
                }
                check(drawn.await(5, TimeUnit.SECONDS)) { "Layout frames timed out" }
                waitForIdleSync()
            }

            fun fixture() {
                val now = System.currentTimeMillis()
                set("sourceStartedAt", now - 1)
                set("snapshot", VehicleSnapshot(65, true, false, true, now, 1800, 80, now))
                set("waitingPlan", WaitingPlan(SystemClock.elapsedRealtime(), 1200, 65))
                set("nearbyKind", NearbyKind.CAFE)
                set("nearbyFix", VehicleLocationFix(37.4979, 127.0276, now, 1f))
                set("nearbyLoading", false)
                set("nearbyNote", "화면 배치 검증용 예시 장소입니다. 실제 검색 결과가 아니에요.")
                val places = listOf("A", "B", "C").mapIndexed { index, suffix ->
                    val outward = 180 + index * 60
                    val inward = 180 + index * 60
                    NearbyPlace(
                        id = "layout-fixture-$suffix", name = "예시 카페 $suffix", address = "",
                        url = Uri.parse("https://example.invalid/layout-fixture/$suffix"),
                        latitude = 37.4979, longitude = 127.0276,
                        outboundSeconds = outward, returnSeconds = inward, staySeconds = 600,
                        totalSeconds = outward + inward + 600 + 300, fit = NearbyFit.TOO_LONG,
                        outboundUrl = Uri.parse("https://example.invalid/layout-fixture/$suffix/outward"),
                        returnUrl = Uri.parse("https://example.invalid/layout-fixture/$suffix/return"),
                        returnReserveSeconds = 300,
                    )
                }
                set("nearbyResult", NearbyLookupResult(
                    places = places, generatedAtMillis = now, originTimestampMillis = now,
                    provider = NearbyProvider.OPENSTREETMAP, coverage = NearbyCoverage.WITHIN_COVERAGE,
                    attribution = "화면 검증용 예시 데이터 · 실제 장소·도보 경로 아님",
                ))
            }

            val output = File(targetContext.filesDir, "dock-layout-evidence").apply { mkdirs() }
            fun capture(name: String) {
                settleFrames()
                val completed = CountDownLatch(1)
                var error: String? = null
                runOnMainSync {
                    val decor = app.window.decorView
                    check(decor.width > 0 && decor.height > 0) { "Window has no capture size" }
                    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(app.window, bitmap, { result ->
                        try {
                            if (result != PixelCopy.SUCCESS) error = "PixelCopy $result"
                            else File(output, "$name.png").outputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG write failed" }
                            }
                        } catch (failure: Throwable) {
                            error = "${failure.javaClass.simpleName}: ${failure.message}"
                        } finally {
                            bitmap.recycle()
                            completed.countDown()
                        }
                    }, Handler(Looper.getMainLooper()))
                }
                check(completed.await(10, TimeUnit.SECONDS)) { "Capture timed out" }
                check(error == null) { error.orEmpty() }
            }

            runOnMainSync {
                val handler = get("handler") as Handler
                handler.removeCallbacks(get("ticker") as Runnable)
                handler.removeCallbacks(get("connectionRefresh") as Runnable)
                (get("source") as VehicleDataSource).stop()
                (get("comfort") as ComfortController).stop()
                (get("transientDialog") as? Dialog)?.dismiss()
                call("cancelNearbyWork")
                set("started", true); set("resumed", true); set("demoMode", true)
                set("status", SessionStatus.CHARGING); set("tab", 0)
                set("active", null); set("recovery", null); set("completed", null); set("nap", null)
                set("targetMode", false); set("waitMinutes", 20); set("target", 80)
                set("followVehicleTarget", false); set("departureAtMillis", null)
                set("backgroundMotion", false); set("panelOnRight", false)
                set("nearbyResumeKind", null)
            }
            for ((name, background, right) in listOf(
                Triple("light-right", BackgroundMode.WHITE, true),
                Triple("dark-right", BackgroundMode.BLACK, true),
                Triple("dark-left", BackgroundMode.BLACK, false),
            )) {
                runOnMainSync {
                    set("backgroundMode", background)
                    set("themeMode", if (background == BackgroundMode.WHITE) ThemeMode.LIGHT else ThemeMode.DARK)
                    set("tocOnRight", right)
                    fixture(); render()
                }
                settleFrames()
                runOnMainSync {
                    val scroll = get("contentScroll") as ScrollView
                    val anchor = (get("sectionAnchors") as Map<*, *>)[4] as View
                    val rect = Rect(0, 0, anchor.width, anchor.height)
                    scroll.offsetDescendantRectToMyCoords(anchor, rect)
                    val inset = (48 * app.resources.displayMetrics.density).toInt()
                    scroll.scrollTo(0, (rect.top - inset).coerceAtLeast(0))
                    call("updateSectionDock")
                }
                capture(name)
            }
            report.putString("screens", "light-right,dark-right,dark-left")
            report.putString("path", output.absolutePath)
            report.putFloat("fontScale", app.resources.configuration.fontScale)
            report.putString("data", "SIMULATED_PARKED_CHARGING_65_PERCENT_20_MIN; SAMPLE_CAFE_CARDS; actual app Views and PixelCopy")
            report.putString("scope", "LAYOUT_ONLY; no preference-setting actions/location requests/vehicle commands/external service launches")
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            report.putString("failure", "${cause.javaClass.simpleName}: ${cause.message}")
        } finally {
            runOnMainSync { activity?.finish() }
        }
        finish(code, report)
    }
}
