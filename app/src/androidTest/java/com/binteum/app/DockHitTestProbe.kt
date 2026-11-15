package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.binteum.app.ui.AppIcon
import com.binteum.app.ui.AppTheme
import com.binteum.app.ui.SectionDockView
import com.binteum.app.ui.ThemeMode

/** Detached test views only; no app state, location, media or vehicle operations. */
class DockHitTestProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var checks = 0
        var failure: Throwable? = null
        runOnMainSync {
            try {
                fun verify(value: Boolean, name: String) { check(value) { name }; checks++ }
                for (onRight in listOf(false, true)) {
                    var contentTouches = 0
                    var rowTouched: Int? = null
                    var selected: Int? = null
                    val frame = FrameLayout(targetContext)
                    frame.addView(View(targetContext).apply {
                        setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) contentTouches++
                            true
                        }
                    }, FrameLayout.LayoutParams(-1, -1))
                    val dock = SectionDockView(targetContext, AppTheme.palette(targetContext, ThemeMode.LIGHT), onRight,
                        listOf(SectionDockView.Item(1, "Videos", AppIcon.PLAY),
                            SectionDockView.Item(4, "Nearby places", AppIcon.MAP))) { selected = it }
                    frame.addView(dock, FrameLayout.LayoutParams(-2, -2,
                        Gravity.CENTER_VERTICAL or if (onRight) Gravity.RIGHT else Gravity.LEFT))
                    val size = (800 * targetContext.resources.displayMetrics.density).toInt()
                    frame.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
                    frame.layout(0, 0, size, size)
                    dock.setActive(1)
                    val inactive = dock.getChildAt(1) as LinearLayout
                    inactive.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) rowTouched = 4
                        false
                    }
                    val title = (0 until inactive.childCount).map(inactive::getChildAt).filterIsInstance<TextView>().single()
                    val icon = (0 until inactive.childCount).map(inactive::getChildAt).filterIsInstance<ImageView>().single()
                    fun touch(view: View) {
                        val rect = Rect(0, 0, view.width, view.height)
                        frame.offsetDescendantRectToMyCoords(view, rect)
                        val now = SystemClock.uptimeMillis()
                        // Cancel after down: detached Views cannot run their posted click callbacks.
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
                            val event = MotionEvent.obtain(now, now, action, rect.exactCenterX(), rect.exactCenterY(), 0)
                            try { frame.dispatchTouchEvent(event) } finally { event.recycle() }
                        }
                    }
                    val widthBefore = dock.width
                    touch(title)
                    verify(contentTouches == 1 && rowTouched == null, "hidden label passes through, right=$onRight")
                    touch(icon)
                    verify(rowTouched == 4 && contentTouches == 1, "inactive icon receives touch, right=$onRight")
                    inactive.performClick()
                    verify(selected == 4, "icon row activates section, right=$onRight")
                    rowTouched = null
                    touch(title)
                    verify(rowTouched == 4 && contentTouches == 1, "active label receives touch, right=$onRight")
                    verify(dock.width == widthBefore, "selection retains stable geometry, right=$onRight")
                    dock.setActive(1)
                    selected = null
                    inactive.performClick()
                    verify(selected == 4, "keyboard/accessibility click remains available, right=$onRight")
                }
            } catch (error: Throwable) { failure = error.cause ?: error }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putInt("checks", checks)
            putString("result", failure?.let { "FAIL: ${it.javaClass.simpleName}: ${it.message}" } ?: "PASS: dock visible touch targets")
        })
    }
}
