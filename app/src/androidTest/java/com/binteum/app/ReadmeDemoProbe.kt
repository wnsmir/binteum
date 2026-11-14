package com.binteum.app

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.binteum.app.ui.ReminderDialView

/** Exercises README controls against the normal debug demo source, without injecting app state. */
class ReadmeDemoProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var checks = 0
        var failure: Throwable? = null
        val app = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val prefs = app.getSharedPreferences("binteum_experience", Activity.MODE_PRIVATE)
        val previous = prefs.all.filterKeys { it in setOf("demoMode", "demoScenario", "target", "followVehicleTarget") }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun root() = app.window.decorView
        fun button(vararg labels: String) = views(root()).filterIsInstance<Button>().first { it.text.toString() in labels }
        fun dialog() = MainActivity::class.java.getDeclaredField("transientDialog").apply { isAccessible = true }.get(app) as Dialog
        fun verify(ok: Boolean, detail: String) { check(ok) { detail }; checks++ }
        fun ui(action: () -> Unit) {
            var error: Throwable? = null
            runOnMainSync { try { action() } catch (caught: Throwable) { error = caught } }
            error?.let { throw it }
        }
        fun settle() { waitForIdleSync(); Thread.sleep(600); waitForIdleSync() }
        try {
            settle()
            ui {
                verify(app.getString(R.string.app_name) in listOf("웨이팅", "Waiting"), "renamed app resource")
                button("설정", "Settings").performClick()
            }
            settle()
            ui {
                val toggle = views(root()).filterIsInstance<Switch>().first {
                    it.contentDescription?.toString().orEmpty().startsWith(app.getString(R.string.ui_text_016) + ".")
                }
                if (!toggle.isChecked) toggle.performClick()
            }
            settle()
            ui { button(app.getString(R.string.settings_apply)).performClick() }
            settle()
            ui {
                verify(views(root()).filterIsInstance<TextView>().any { it.text.toString() in listOf("모의 충전", "Simulated charging") }, "demo label visible")
                verify(!button("남은 시간 동안 수면", "Sleep for the remaining time").isEnabled, "sleep requires a timer")
                views(root()).single { it.contentDescription?.toString().orEmpty() in listOf("대기 타이머 설정", "Configure waiting timer") }.performClick()
            }
            settle()
            ui {
                val modal = dialog()
                verify(modal.isShowing, "timer modal opened by tapping dial")
                val dial = views(modal.window!!.decorView).filterIsInstance<ReminderDialView>().single()
                verify(dial.performAccessibilityAction(android.R.id.accessibilityActionSetProgress,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 20f) }), "dial accepts 20 minutes")
                views(modal.window!!.decorView).filterIsInstance<Button>().first { it.text.toString() in listOf("시작", "Start") }.performClick()
            }
            settle()
            ui {
                verify(views(root()).filterIsInstance<ReminderDialView>().single().contentDescription.toString().contains(":"), "main dial starts countdown")
                val sleep = button("남은 시간 동안 수면", "Sleep for the remaining time")
                verify(sleep.isEnabled, "sleep enabled after starting timer")
                sleep.performClick()
            }
            settle()
            var first = ""
            ui {
                val screen = app.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                verify((screen.background as ColorDrawable).color == Color.BLACK, "sleep screen black")
                val labels = views(screen).filterIsInstance<TextView>()
                verify(labels.size == 2 && labels.count { it is Button } == 1, "only countdown and End")
                first = labels.single { it !is Button }.text.toString()
            }
            Thread.sleep(2200)
            ui {
                val current = views(app.findViewById<ViewGroup>(android.R.id.content)).filterIsInstance<TextView>().single { it !is Button }.text.toString()
                verify(current != first, "real ticker advances sleep countdown")
                button("종료", "End sleep").performClick()
            }
            settle()
            ui {
                verify(button("남은 시간 동안 수면", "Sleep for the remaining time").isEnabled, "End returns to home")
                verify(views(root()).filterIsInstance<ReminderDialView>().single().contentDescription.toString().contains(":"), "main timer survives End")
                button("설정", "Settings").performClick()
            }
            settle()
            ui {
                verify(views(root()).filterIsInstance<Button>().count { it.text == app.getString(R.string.settings_apply) } == 1, "one Apply settings action")
                button(app.getString(R.string.settings_apply)).performClick()
            }
        } catch (error: Throwable) { failure = error.cause ?: error }
        finally {
            ui {
                app.finish()
                val edit = prefs.edit()
                for (key in listOf("demoMode", "demoScenario", "target", "followVehicleTarget")) {
                    when (val value = previous[key]) {
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        else -> edit.remove(key)
                    }
                }
                edit.commit()
            }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("result", if (failure == null) "PASS: $checks README UI checks; real demo source and ticker" else "FAIL after $checks: ${failure?.message}")
            putString("scope", "No vehicle writes, location requests, account access or media playback; one simulated sleep record")
        })
    }
}
