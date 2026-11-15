package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.binteum.core.*
import com.binteum.app.ui.*

/** In-memory simulation only: no preference writes, vehicle commands, login or location requests. */
class WaitingFlowProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var checks = 0
        var failure: Throwable? = null
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun set(name: String, value: Any?) = field(name).set(activity, value)
        fun get(name: String): Any? = field(name).get(activity)
        fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        fun render() = MainActivity::class.java.getDeclaredMethod("render", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
        fun verify(value: Boolean, name: String) { check(value) { name }; checks++ }
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        runOnMainSync {
            try {
                (get("handler") as Handler).removeCallbacks(get("ticker") as Runnable)
                (get("source") as VehicleDataSource).stop()
                (get("comfort") as com.binteum.app.vehicle.ComfortController).stop()
                val now = System.currentTimeMillis()
                set("sourceStartedAt", now - 1)
                set("started", true); set("resumed", true); set("tab", 0)
                set("followVehicleTarget", false); set("target", 80)
                val snapshot = VehicleSnapshot(65, true, false, true, now, 1800, 80, now)
                set("snapshot", snapshot); set("status", SessionStatus.CHARGING)
                set("waitingPlan", null); render()
                verify((get("sectionAnchors") as Map<*, *>).size == 4, "content preview before timer")
                verify(get("waitDial") is ReminderDialView, "timer summary available")
                val dock = get("sectionDock") as SectionDockView
                verify(dock.parent === (get("contentScroll") as View).parent, "dock is on content side")
                verify(dock.background is DockBackdrop, "sampled blur backdrop")
                verify((0 until dock.childCount).all { dock.getChildAt(it).background == null }, "borderless dock rows")
                verify(descendants(get("leftScroll") as View).none { it is SectionDockView }, "no sidebar contents list")
                fun videoSection() = (get("sectionAnchors") as Map<*, *>)[1] as View
                fun videoControl(tag: String) = descendants(videoSection()).single { it.tag == tag }
                val videoHeader = videoControl("video-header") as android.widget.LinearLayout
                verify(videoHeader.orientation == android.widget.LinearLayout.HORIZONTAL &&
                    descendants(videoHeader).count { it.tag in listOf("video-open-YOUTUBE", "video-open-NETFLIX") } == 2,
                    "both service links share the video heading row")
                verify((videoControl("video-selectors") as android.widget.LinearLayout).orientation == android.widget.LinearLayout.HORIZONTAL,
                    "video providers are horizontal choices")
                val videoPanel = get("media") as com.binteum.app.media.WebMediaPanel
                verify(descendants(videoSection()).any { it === videoPanel }, "YouTube choices shown by default")
                videoControl("video-select-NETFLIX").performClick()
                verify(get("videoProvider") == com.binteum.app.media.VideoProvider.NETFLIX && videoPanel.parent == null,
                    "Netflix selection detaches inline YouTube")
                val videoGate = videoPanel.javaClass.getDeclaredField("playbackAllowed").apply { isAccessible = true }
                verify(!videoGate.getBoolean(videoPanel) && !videoPanel.hasOpenContent, "hidden YouTube cannot play")
                verify(videoControl("netflix-preview").isShown, "Netflix shows service handoff information")
                render()
                verify(get("videoProvider") == com.binteum.app.media.VideoProvider.NETFLIX && videoPanel.parent == null,
                    "rebuild retains selected video provider")
                videoControl("video-select-YOUTUBE").performClick()
                verify(videoPanel.parent != null && videoGate.getBoolean(videoPanel) && !videoPanel.hasOpenContent,
                    "returning to YouTube restores choices without autoplay")
                call("showWaitSettings")
                val timerDialog = get("transientDialog") as android.app.Dialog
                verify(timerDialog.isShowing && descendants(timerDialog.window!!.decorView).any { it is ReminderDialView }, "expanded timer modal")
                val start = descendants(timerDialog.window!!.decorView).filterIsInstance<android.widget.Button>().first { it.text == "시작" || it.text == "Start" }
                verify(start.currentTextColor == (get("palette") as AppPalette).background, "inverse start text")
                timerDialog.dismiss()
                verify(get("waitingPlan") == null, "cancel leaves timer unset")
                verify(call("plannedSleep") == null, "sleep cannot start without a timer")
                val plan = WaitingPlan(SystemClock.elapsedRealtime(), 1200, 65)
                set("waitingPlan", plan); render()
                val sleep = call("plannedSleep") as NapSession
                verify(sleep.plannedDurationSeconds in 1198..1200, "sleep uses full remaining timer without reserve")
                val sleepText = (get("napStartButton") as android.widget.Button).text.toString()
                verify(sleepText in listOf("남은 시간 동안 수면", "Sleep for the remaining time"), "simple remaining-time sleep action")
                val sleepViews = descendants(get("contentScroll") as View).filterIsInstance<android.widget.TextView>()
                verify(sleepViews.none { it.text.toString().contains("휴식 15분") || it.text.toString().contains("낮잠") }, "no independent duration selector or old sleep wording")
                val sleepSection = (get("sectionAnchors") as Map<*, *>)[5] as View
                verify(descendants(sleepSection).filterIsInstance<android.widget.Button>().size == 2, "sleep section has only sleep and seat actions")
                set("nap", NapSession.forRemainingTime(1200, SystemClock.elapsedRealtime()))
                set("napRemainingSeconds", 1200)
                set("beforeNapBrightness", activity.window.attributes.screenBrightness)
                render()
                val sleepRoot = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                verify((sleepRoot.background as android.graphics.drawable.ColorDrawable).color == android.graphics.Color.BLACK, "sleep background is true black")
                val sleepLabels = descendants(sleepRoot).filterIsInstance<android.widget.TextView>()
                verify(sleepLabels.size == 2 && sleepLabels.any { it.text == "20:00" }, "sleep shows only remaining time and end")
                verify(descendants(sleepRoot).none { it is SectionDockView }, "no nav or content in sleep mode")
                descendants(sleepRoot).filterIsInstance<android.widget.Button>().single().performClick()
                verify(get("nap") == null && (get("sectionAnchors") as Map<*, *>).size == 4, "end sleep returns to content")
                verify(get("waitingPlan") === plan, "ending sleep keeps main timer")
                val fingerprint = "Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys"
                verify(com.binteum.app.nearby.LocationPermissionPolicy.hasBrokenEmulatorDialog(fingerprint, null), "known ungrouped emulator dialog is guarded")
                verify(!com.binteum.app.nearby.LocationPermissionPolicy.hasBrokenEmulatorDialog(fingerprint, "android.permission-group.LOCATION"), "grouped permission uses normal flow")
                verify(!com.binteum.app.nearby.LocationPermissionPolicy.hasBrokenEmulatorDialog("Pleos/production/vehicle:14/build", null), "real vehicle permission flow is unchanged")
                val anchors = get("sectionAnchors") as Map<*, *>
                verify(anchors.keys.toList() == listOf(1, 6, 4, 5), "feed order")
                val content = (anchors[1] as View).parent as ViewGroup
                content.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                content.layout(0, 0, 1100, content.measuredHeight)
                val positions = anchors.values.map { (it as View).top }
                verify(positions.zipWithNext().all { it.first < it.second }, "vertical sections")
                val scroll = get("contentScroll") as android.widget.ScrollView
                scroll.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
                scroll.layout(0, 0, 1100, 700)
                scroll.scrollTo(0, (anchors[6] as View).top)
                call("updateSectionDock")
                verify((get("sectionDock") as SectionDockView).activeId == 6, "scroll highlights music section")
                set("tocOnRight", false); render()
                verify(((get("sectionDock") as View).layoutParams as android.widget.FrameLayout.LayoutParams).gravity and android.view.Gravity.LEFT == android.view.Gravity.LEFT, "left dock placement")
                set("tocOnRight", true); render()
                verify((call("recommendationSeconds") as Int) in 1198..1200, "duration bounds recommendations")
                set("waitingPlan", WaitingPlan(SystemClock.elapsedRealtime(), null, 65)); render()
                verify((call("recommendationSeconds") as Int) in 1790..1800, "OEM ETA connected")
                set("waitingPlan", plan)
                val media = get("media")
                for (mode in listOf(BackgroundMode.BLACK, BackgroundMode.WHITE, BackgroundMode.SYSTEM)) {
                    set("backgroundMode", mode); render()
                    verify(get("waitingPlan") === plan && get("media") === media, "theme preserves plan and media")
                    verify((get("palette") as AppPalette).flat, "flat theme")
                }
                set("tab", 2); render()
                val settings = (get("contentScroll") as ViewGroup).getChildAt(0) as ViewGroup
                val settingsCards = settings.getChildAt(1) as android.widget.LinearLayout
                verify(settingsCards.orientation == android.widget.LinearLayout.VERTICAL, "single column settings")
                val sideButtons = descendants(get("leftScroll") as View).filterIsInstance<android.widget.Button>()
                verify(sideButtons.count { it.text.toString() == activity.getString(R.string.settings_apply) } == 1, "one settings apply button")
                verify(sideButtons.none { it.text.toString() in listOf("설정", "Settings") }, "settings action is replaced")
                val side = (get("leftScroll") as android.widget.ScrollView).getChildAt(0) as ViewGroup
                side.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
                side.layout(0, 0, 420, 1600)
                verify(side.getChildAt(side.childCount - 1).bottom > 1500, "settings at panel bottom")
                call("chooseDeparture")
                val dialog = get("transientDialog") as android.app.Dialog
                verify(dialog.isShowing, "departure modal opens")
                val wheels = descendants(dialog.window!!.decorView).filterIsInstance<TimeWheelView>()
                verify(wheels.size == 2 && wheels[0].value in 0..23 && wheels[1].value in 0..59, "digital wheels")
                dialog.dismiss()
                set("tab", 0); render(); call("chooseMusicService")
                verify((get("transientDialog") as android.app.Dialog).isShowing, "music modal opens")
                val musicButtons = descendants((get("transientDialog") as android.app.Dialog).window!!.decorView).filterIsInstance<android.widget.Button>()
                verify(musicButtons.count { it.compoundDrawablesRelative[0] != null && it.text.toString() in listOf("YouTube Music", "Apple Music", "Spotify") } == 3, "all music service icons")
                (get("transientDialog") as android.app.Dialog).dismiss()
                set("snapshot", snapshot.copy(uxRestricted = true)); render()
                verify((get("sectionAnchors") as Map<*, *>).isEmpty(), "driving hides content")
                val gate = media!!::class.java.getDeclaredField("playbackAllowed").apply { isAccessible = true }
                verify(!gate.getBoolean(media), "driving blocks playback")
                set("snapshot", snapshot.copy(charging = false)); render()
                verify((get("sectionAnchors") as Map<*, *>).isEmpty(), "not charging hides content")
            } catch (error: Throwable) { failure = error.cause ?: error }

        }
        // The nearby screen is now a section deep in one feed. Clearing its selection must
        // reveal the nearby categories instead of sending the user back to the first video.
        // Injecting a category directly avoids location permissions, requests and providers.
        for (useBack in listOf(false, true)) {
            if (failure != null) break
            runOnMainSync {
                try {
                    val now = System.currentTimeMillis()
                    set("snapshot", VehicleSnapshot(65, true, false, true, now, 1800, 80, now))
                    set("status", SessionStatus.CHARGING); set("tab", 0)
                    set("nearbyKind", com.binteum.app.nearby.NearbyKind.CAFE)
                    set("nearbyResumeKind", null); set("nearbyResult", null); set("nearbyLoading", false)
                    set("nearbyNote", "Navigation fixture")
                    render()
                } catch (error: Throwable) { failure = error.cause ?: error }
            }
            waitForIdleSync()
            if (failure != null) break
            val settled = java.util.concurrent.CountDownLatch(1)
            var stopped = false
            var returnedToNearby = false
            runOnMainSync {
                try {
                    val before = get("contentScroll") as android.widget.ScrollView
                    val section = (get("sectionAnchors") as Map<*, *>)[4] as View
                    val rect = android.graphics.Rect(0, 0, section.width, section.height)
                    before.offsetDescendantRectToMyCoords(section, rect)
                    before.scrollTo(0, rect.top)
                    if (useBack) verify(call("goBack") == true, "back consumes nearby selection")
                    else descendants(section).filterIsInstance<android.widget.Button>()
                        .single { it.text.toString() in listOf("다른 종류", "Other categories") }.performClick()
                    verify(get("nearbyKind") == null, "${if (useBack) "back" else "other categories"} clears selection")
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    var stableFrames = 0
                    val callback = object : android.view.Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            if (stopped) return
                            try {
                                val scroll = get("contentScroll") as android.widget.ScrollView
                                val nearby = (get("sectionAnchors") as Map<*, *>)[4] as View
                                if (scroll.isLaidOut && nearby.isLaidOut && scroll.height > 0) {
                                    val targetRect = android.graphics.Rect(0, 0, nearby.width, nearby.height)
                                    scroll.offsetDescendantRectToMyCoords(nearby, targetRect)
                                    val maximum = (scroll.getChildAt(0).height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
                                    val expected = targetRect.top.coerceIn(0, maximum)
                                    val atNearby = expected > 0 && scroll.scrollY > 0 && kotlin.math.abs(scroll.scrollY - expected) <= 3
                                    stableFrames = if (atNearby) stableFrames + 1 else 0
                                    if (stableFrames >= 3) { returnedToNearby = true; stopped = true; settled.countDown(); return }
                                }
                                if (SystemClock.elapsedRealtime() >= deadline) { stopped = true; settled.countDown(); return }
                                android.view.Choreographer.getInstance().postFrameCallback(this)
                            } catch (error: Throwable) {
                                failure = error.cause ?: error; stopped = true; settled.countDown()
                            }
                        }
                    }
                    android.view.Choreographer.getInstance().postFrameCallback(callback)
                } catch (error: Throwable) { failure = error.cause ?: error; stopped = true; settled.countDown() }
            }
            val completed = settled.await(12, java.util.concurrent.TimeUnit.SECONDS)
            runOnMainSync {
                stopped = true
                if (failure == null) try {
                    verify(completed && returnedToNearby, "${if (useBack) "back" else "other categories"} returns to nearby after layout and scrolling")
                } catch (error: Throwable) { failure = error.cause ?: error }
            }
        }
        if (failure == null) {
            val rendered = java.util.concurrent.CountDownLatch(1)
            var sourceDraws = 0
            var softwareDraws = 0
            var sourceLayouts = 0
            var initialDockWidth = -1
            var unstableWidth = false
            runOnMainSync {
                val host = DockContentLayout(activity)
                val sourceView = object : View(activity) {
                    private val paint = android.graphics.Paint()
                    override fun onDraw(canvas: android.graphics.Canvas) {
                        sourceDraws++
                        if (!canvas.isHardwareAccelerated) softwareDraws++
                        paint.color = android.graphics.Color.rgb(40, 70, 90)
                        canvas.drawColor(paint.color)
                        paint.color = android.graphics.Color.WHITE
                        canvas.drawRect(0f, 20f, width.toFloat(), 40f, paint)
                    }
                    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                        if (changed) sourceLayouts++
                    }
                }
                val dock = SectionDockView(activity, get("palette") as AppPalette, true,
                    listOf(SectionDockView.Item(1, "Video", AppIcon.PLAY), SectionDockView.Item(6, "Music", AppIcon.MUSIC))) {}
                host.addView(sourceView, android.widget.FrameLayout.LayoutParams(-1, -1))
                host.addView(dock, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.RIGHT or android.view.Gravity.CENTER_VERTICAL))
                activity.setContentView(host)
                var frames = 0
                val callback = object : android.view.Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (host.isLaidOut) {
                            if (initialDockWidth < 0) initialDockWidth = dock.width
                            else if (dock.width != initialDockWidth) unstableWidth = true
                            dock.setActive(if (frames % 2 == 0) 1 else 6)
                            sourceView.scrollTo(0, frames * 3)
                            sourceView.invalidate()
                            frames++
                        }
                        if (frames >= 24) rendered.countDown()
                        else android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                android.view.Choreographer.getInstance().postFrameCallback(callback)
            }
            val done = rendered.await(20, java.util.concurrent.TimeUnit.SECONDS)
            runOnMainSync {
                try {
                    verify(done && sourceDraws >= 10, "GPU scrolling frames rendered")
                    verify(softwareDraws == 0, "no software redraw of scrolling content")
                    verify(sourceLayouts == 1 && !unstableWidth, "section changes keep content and dock geometry stable")
                } catch (error: Throwable) { failure = error }
            }
        }
        runOnMainSync { activity.finish() }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("result", if (failure == null) "PASS: $checks waiting-flow checks (simulated vehicle)" else "FAIL after $checks: ${failure?.message}")
        })
    }
}
