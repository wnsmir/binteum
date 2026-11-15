package com.binteum.app

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.binteum.app.ui.AmbientBackgroundDrawable
import com.binteum.app.ui.AppPalette
import com.binteum.app.ui.AppTheme
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

/** Real settings callbacks and fresh Activity loads; only the two appearance preferences are staged/restored. */
class ThemeSettingsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = Bundle()
        val preferences = targetContext.getSharedPreferences("binteum_experience", 0)
        val keys = listOf("themeMode", "backgroundMode")
        val original = keys.associateWith { preferences.getString(it, null) }
        val originallyPresent = keys.filter(preferences::contains).toSet()
        var app: MainActivity? = null
        var checks = 0
        var result = Activity.RESULT_CANCELED
        fun verify(value: Boolean, description: String) { check(value) { description }; checks++ }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun get(name: String): Any? = field(name).get(app!!)
        fun set(name: String, value: Any?) = field(name).set(app!!, value)
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun root() = app!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        fun settle() {
            waitForIdleSync()
            val frames = CountDownLatch(1)
            runOnMainSync {
                var count = 0
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (++count >= 3) frames.countDown()
                        else Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                Choreographer.getInstance().postFrameCallback(callback)
            }
            check(frames.await(5, TimeUnit.SECONDS)) { "Layout timeout" }
            waitForIdleSync()
        }
        fun freshActivity() {
            runOnMainSync { app?.finish() }
            waitForIdleSync()
            app = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            runOnMainSync {
                (get("handler") as Handler).removeCallbacks(get("ticker") as Runnable)
                (get("handler") as Handler).removeCallbacks(get("connectionRefresh") as Runnable)
                (get("source") as VehicleDataSource).stop()
                (get("comfort") as ComfortController).stop()
                (get("transientDialog") as? Dialog)?.dismiss()
                val now = System.currentTimeMillis()
                set("sourceStartedAt", now - 1)
                set("started", true); set("resumed", true)
                set("snapshot", VehicleSnapshot(65, true, false, true, now, 1800, 80, now))
                set("status", SessionStatus.CHARGING)
                set("waitingPlan", WaitingPlan(SystemClock.elapsedRealtime(), 1200, 65))
                MainActivity::class.java.getDeclaredMethod("render", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(app, false)
            }
            settle()
        }
        fun stage(theme: String?, background: String?) {
            val edit = preferences.edit()
            if (theme == null) edit.remove("themeMode") else edit.putString("themeMode", theme)
            if (background == null) edit.remove("backgroundMode") else edit.putString("backgroundMode", background)
            check(edit.commit()) { "Could not stage appearance preferences" }
        }
        fun expectedSystemDark() = app!!.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        fun checkAppearance(dark: Boolean, description: String) {
            val palette = get("palette") as AppPalette
            verify(palette.dark == dark && palette.flat, "$description palette")
            val expected = if (dark) Color.BLACK else Color.WHITE
            verify(palette.background == expected, "$description palette background")
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            try {
                // Draw only the root background Drawable, not a live View hierarchy.
                root().background.draw(Canvas(bitmap))
                verify(bitmap.getPixel(2, 2) == expected, "$description actual root background")
            } finally { bitmap.recycle() }
            verify(if (dark) Color.luminance(palette.text) > .8f else Color.luminance(palette.text) < .1f,
                "$description foreground contrast")
            verify(descendants(root()).single { it.tag == "theme-shortcut-DARK" }.isSelected == dark &&
                descendants(root()).single { it.tag == "theme-shortcut-LIGHT" }.isSelected != dark,
                "$description quick controls reflect the visible mode")
        }
        fun openSettings() {
            runOnMainSync {
                val button = descendants(root()).filterIsInstance<Button>()
                    .single { it.text.toString() in listOf("설정", "Settings") }
                button.performClick()
                verify(get("tab") == 2, "real sidebar action opens settings")
            }
            settle()
        }
        val skyChoices = listOf(BackgroundMode.AUTO, BackgroundMode.DAWN, BackgroundMode.DAY,
            BackgroundMode.SUNSET, BackgroundMode.NIGHT)
        fun modeTitle(mode: ThemeMode) = app!!.getString(when (mode) {
            ThemeMode.SYSTEM -> R.string.ui_text_078
            ThemeMode.LIGHT -> R.string.ui_text_079
            ThemeMode.DARK -> R.string.ui_text_080
        })
        fun skyTitle(mode: BackgroundMode) = app!!.getString(when (mode) {
            BackgroundMode.AUTO -> R.string.sky_auto
            BackgroundMode.DAWN -> R.string.sky_dawn
            BackgroundMode.DAY -> R.string.sky_day
            BackgroundMode.SUNSET -> R.string.sky_sunset
            BackgroundMode.NIGHT -> R.string.sky_night
            else -> error("Not a sky choice: $mode")
        })
        fun appearanceRow() = descendants(root()).single {
            it.transitionName == "setting-row:${app!!.getString(R.string.ui_text_081)}"
        }
        fun checkSettingsStructure(expectedLabel: String, gradient: Boolean) {
            val views = descendants(root())
            val screen = appearanceRow()
            val control = descendants(screen).filterIsInstance<Button>().single()
            verify(control.contentDescription.toString() == "${app!!.getString(R.string.ui_text_081)}, $expectedLabel",
                "screen mode row names the selected appearance: $expectedLabel")
            verify(views.none { it.transitionName == "setting-row:${app!!.getString(R.string.ui_text_088)}" } &&
                views.filterIsInstance<TextView>().none { it.text.toString() == app!!.getString(R.string.ui_text_087) },
                "separate background style row and card are absent")
            val motion = views.filter { it.transitionName == "setting-switch:${app!!.getString(R.string.ui_text_090)}" }
            verify(if (gradient) motion.size == 1 && motion.single().visibility == View.VISIBLE else motion.isEmpty(),
                "background motion appears only for a sky choice")
            if (gradient) {
                val parent = screen.parent as ViewGroup
                verify(motion.single().parent === parent && parent.indexOfChild(motion.single()) == parent.indexOfChild(screen) + 1,
                    "background motion is immediately below screen mode")
            }
        }
        fun selectChoice(name: String) {
            runOnMainSync {
                descendants(appearanceRow()).filterIsInstance<Button>().single().performClick()
                val dialog = get("transientDialog") as Dialog
                verify(dialog.isShowing, "appearance button opens real chooser")
                val expected = ThemeMode.entries.map(::modeTitle) + skyChoices.map(::skyTitle)
                val offered = descendants(dialog.window!!.decorView)
                    .filter { it.isClickable && it.contentDescription?.toString() in expected }
                    .map { it.contentDescription.toString() }
                verify(offered.size == 8 && offered.toSet() == expected.toSet(),
                    "single screen mode chooser contains three flat modes and all five skies")
                val option = descendants(dialog.window!!.decorView).single { it.isClickable && it.contentDescription?.toString() == name }
                option.performClick()
                verify(!dialog.isShowing, "selecting mode closes chooser")
            }
            settle()
        }
        fun selectMode(mode: ThemeMode) = selectChoice(modeTitle(mode))
        val evidence = File(targetContext.filesDir, "theme-evidence").apply { mkdirs() }
        fun capture(name: String) {
            val done = CountDownLatch(1)
            var error: String? = null
            runOnMainSync {
                val decor = app!!.window.decorView
                val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(app!!.window, bitmap, { status ->
                    try {
                        if (status != PixelCopy.SUCCESS) error = "PixelCopy $status"
                        else File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } catch (failure: Throwable) { error = failure.javaClass.simpleName }
                    finally { bitmap.recycle(); done.countDown() }
                }, Handler(Looper.getMainLooper()))
            }
            check(done.await(10, TimeUnit.SECONDS)) { "Capture timeout" }
            check(error == null) { error.orEmpty() }
        }
        try {
            runOnMainSync {
                for (night in listOf(false, true)) for (mode in ThemeMode.entries) {
                    val context = targetContext.createConfigurationContext(Configuration(targetContext.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    })
                    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && night)
                    for (background in BackgroundMode.entries) {
                        val palette = AppTheme.palette(context, mode, background)
                        verify(palette.dark == dark, "mode takes precedence: $mode/$background/night=$night")
                        verify(palette.flat == background.isFlat, "background style retained: $mode/$background")
                        if (background.isFlat) {
                            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                            val drawable = AmbientBackgroundDrawable()
                            try {
                                drawable.setBounds(0, 0, 4, 4)
                                drawable.configure(background, palette.dark, false)
                                drawable.draw(Canvas(bitmap))
                                verify(bitmap.getPixel(2, 2) == if (dark) Color.BLACK else Color.WHITE,
                                    "flat renderer matches resolved mode: $mode/$background/night=$night")
                            } finally { drawable.release(); bitmap.recycle() }
                        }
                    }
                }
            }
            stage("DARK", null)
            freshActivity()
            runOnMainSync { checkAppearance(true, "saved DARK with default background") }
            val homeMedia = get("media")
            val homePlan = get("waitingPlan")
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                runOnMainSync {
                    val shortcut = descendants(root()).single { it.tag == "theme-shortcut-${mode.name}" }
                    verify(shortcut.width >= 48 && shortcut.height >= 48 && shortcut.contentDescription.isNotBlank(),
                        "$mode shortcut has a labeled touch target")
                    shortcut.performClick()
                }
                settle()
                runOnMainSync {
                    checkAppearance(mode == ThemeMode.DARK, "home shortcut $mode")
                    verify(get("tab") == 0 && (get("transientDialog") as? Dialog)?.isShowing != true,
                        "$mode shortcut works outside settings without a dialog")
                    verify(get("media") === homeMedia && get("waitingPlan") === homePlan,
                        "$mode shortcut retains media and timer")
                    verify(preferences.getString("themeMode", null) == mode.name, "$mode shortcut persists selection")
                }
                capture(if (mode == ThemeMode.DARK) "dark-home-shortcuts" else "light-home-shortcuts")
            }
            openSettings()
            runOnMainSync { checkSettingsStructure(modeTitle(ThemeMode.DARK), false) }
            val media = get("media")
            val plan = get("waitingPlan")
            var previousScroll = 0
            runOnMainSync {
                (get("contentScroll") as ScrollView).scrollTo(0, 100)
                previousScroll = (get("contentScroll") as ScrollView).scrollY
                verify(previousScroll > 0, "settings scroll fixture is nonzero")
            }
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)) {
                selectMode(mode)
                runOnMainSync {
                    checkAppearance(mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && expectedSystemDark()), "selected $mode")
                    verify(preferences.getString("themeMode", null) == mode.name, "$mode saved by UI callback")
                    verify(get("media") === media && get("waitingPlan") === plan, "$mode retains media and timer instances")
                    verify((get("contentScroll") as ScrollView).scrollY == previousScroll, "$mode retains settings scroll")
                }
                if (mode != ThemeMode.SYSTEM) capture(if (mode == ThemeMode.DARK) "dark-settings" else "light-settings")
                else {
                    runOnMainSync {
                        val visibleMode = if ((get("palette") as AppPalette).dark) ThemeMode.DARK else ThemeMode.LIGHT
                        descendants(root()).single { it.tag == "theme-shortcut-${visibleMode.name}" }.performClick()
                        verify(get("themeMode") == visibleMode && preferences.getString("themeMode", null) == visibleMode.name,
                            "highlighted quick control exits system mode into an explicit choice")
                    }
                    settle()
                }
            }
            val retainedTheme = get("themeMode") as ThemeMode
            for (sky in skyChoices) {
                selectChoice(skyTitle(sky))
                runOnMainSync {
                    verify(get("backgroundMode") == sky && preferences.getString("backgroundMode", null) == sky.name,
                        "$sky choice applies and persists its background")
                    verify(get("themeMode") == retainedTheme && preferences.getString("themeMode", null) == retainedTheme.name,
                        "$sky retains the existing tint preference")
                    verify(!(get("palette") as AppPalette).flat,
                        "$sky uses the gradient palette")
                    verify(listOf("LIGHT", "DARK").all { name ->
                        !descendants(root()).single { it.tag == "theme-shortcut-$name" }.isSelected
                    }, "$sky does not highlight either flat-mode shortcut")
                    verify(get("media") === media && get("waitingPlan") === plan &&
                        (get("contentScroll") as ScrollView).scrollY == previousScroll,
                        "$sky retains media, timer and settings scroll")
                    checkSettingsStructure(skyTitle(sky), true)
                }
            }
            selectChoice(skyTitle(BackgroundMode.SUNSET))
            freshActivity()
            runOnMainSync {
                verify(get("backgroundMode") == BackgroundMode.SUNSET && get("themeMode") == retainedTheme &&
                    !(get("palette") as AppPalette).flat, "fresh Activity reloads the selected sunset appearance")
            }
            openSettings()
            runOnMainSync { checkSettingsStructure(skyTitle(BackgroundMode.SUNSET), true) }
            capture("sunset-settings")
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                selectMode(mode)
                selectChoice(skyTitle(BackgroundMode.SUNSET))
                val gradientMedia = get("media")
                val gradientPlan = get("waitingPlan")
                var gradientScroll = 0
                runOnMainSync {
                    (get("contentScroll") as ScrollView).scrollTo(0, 100)
                    gradientScroll = (get("contentScroll") as ScrollView).scrollY
                    descendants(root()).single { it.tag == "theme-shortcut-${mode.name}" }.performClick()
                }
                settle()
                runOnMainSync {
                    checkAppearance(mode == ThemeMode.DARK, "$mode shortcut replaces sky with a flat mode")
                    verify(get("backgroundMode") == BackgroundMode.SYSTEM &&
                        preferences.getString("backgroundMode", null) == "SYSTEM" &&
                        preferences.getString("themeMode", null) == mode.name,
                        "$mode shortcut resets and persists the flat background even when tint already matches")
                    verify(get("tab") == 2 && get("media") === gradientMedia && get("waitingPlan") === gradientPlan &&
                        (get("contentScroll") as ScrollView).scrollY == gradientScroll,
                        "$mode shortcut from a sky retains screen, media, timer and scroll")
                    checkSettingsStructure(modeTitle(mode), false)
                }
            }
            selectMode(ThemeMode.DARK)
            freshActivity()
            runOnMainSync {
                verify(get("themeMode") == ThemeMode.DARK, "fresh Activity reloads UI-selected dark mode")
                checkAppearance(true, "fresh Activity dark mode")
            }
            for ((legacyTheme, legacyBackground, expected) in listOf(
                Triple("DARK", "WHITE", ThemeMode.DARK),
                Triple(null, "WHITE", ThemeMode.LIGHT),
                Triple(null, "BLACK", ThemeMode.DARK),
                Triple("SYSTEM", "BLACK", ThemeMode.SYSTEM),
            )) {
                stage(legacyTheme, legacyBackground)
                freshActivity()
                runOnMainSync {
                    verify(get("themeMode") == expected && get("backgroundMode") == BackgroundMode.SYSTEM,
                        "legacy $legacyTheme/$legacyBackground normalizes consistently")
                    verify(preferences.getString("themeMode", null) == expected.name &&
                        preferences.getString("backgroundMode", null) == "SYSTEM", "legacy appearance normalization persists")
                    checkAppearance(expected == ThemeMode.DARK || (expected == ThemeMode.SYSTEM && expectedSystemDark()),
                        "migrated $legacyTheme/$legacyBackground")
                }
            }
            result = Activity.RESULT_OK
        } catch (failure: Throwable) {
            val cause = failure.cause ?: failure
            report.putString("failure", "${cause.javaClass.simpleName}: ${cause.message}")
        } finally {
            runOnMainSync { app?.finish() }
            waitForIdleSync()
            val edit = preferences.edit()
            for (key in keys) if (key in originallyPresent) edit.putString(key, original[key]) else edit.remove(key)
            val restored = edit.commit()
            report.putBoolean("appearancePreferencesRestored", restored)
            if (!restored) { result = Activity.RESULT_CANCELED; report.putString("failure", "Could not restore appearance preferences") }
        }
        report.putInt("checks", checks)
        report.putString("result", if (result == Activity.RESULT_OK) "PASS: unified appearance choices, quick controls, rendering, persistence and legacy migration" else "FAIL")
        report.putString("evidence", evidence.absolutePath)
        report.putString("scope", "SIMULATED_VEHICLE_AND_TIMER; no media playback/location/vehicle commands; fresh Activity loads, not OS process death")
        finish(result, report)
    }
}
