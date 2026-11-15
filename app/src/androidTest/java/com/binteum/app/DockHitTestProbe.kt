package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.content.res.Configuration
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
import com.binteum.app.ui.DockContentLayout
import com.binteum.app.ui.SectionDockView
import com.binteum.app.ui.ThemeMode
import java.util.Locale

/** Detached layout/touch fixtures only; no app preferences, location, media or vehicle operations. */
class DockHitTestProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var checks = 0
        var scenarios = 0
        var failure: Throwable? = null
        runOnMainSync {
            try {
                fun verify(value: Boolean, name: String) { check(value) { name }; checks++ }
                for (onRight in listOf(false, true)) {
                    for (widthDp in listOf(480, 900, 1400)) {
                        for (fontScale in listOf(1f, 1.3f)) {
                            for (language in listOf("ko", "en")) {
                                val case = "right=$onRight width=$widthDp font=$fontScale language=$language"
                                val context = targetContext.createConfigurationContext(Configuration(targetContext.resources.configuration).apply {
                                    this.fontScale = fontScale
                                    setLocale(Locale.forLanguageTag(language))
                                })
                                fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
                                val titles = if (language == "ko") listOf("영상", "음악", "주변 장소", "수면")
                                    else listOf("Videos", "Music", "Nearby places", "Sleep")
                                val items = listOf(
                                    SectionDockView.Item(1, titles[0], AppIcon.PLAY),
                                    SectionDockView.Item(6, titles[1], AppIcon.MUSIC),
                                    SectionDockView.Item(4, titles[2], AppIcon.MAP),
                                    SectionDockView.Item(5, titles[3], AppIcon.BED),
                                )
                                var contentTouches = 0
                                var contentClicks = 0
                                var rowTouched: Int? = null
                                var selected: Int? = null
                                val host = DockContentLayout(context)
                                val contentButton = TextView(context).apply {
                                    text = "지도 · 가는 길"
                                    isClickable = true
                                    setOnClickListener { contentClicks++ }
                                    setOnTouchListener { _, event ->
                                        if (event.actionMasked == MotionEvent.ACTION_DOWN) contentTouches++
                                        false
                                    }
                                }
                                host.addView(contentButton, FrameLayout.LayoutParams(-1, -1))
                                val dock = SectionDockView(context, AppTheme.palette(context, ThemeMode.LIGHT), onRight, items) {
                                    selected = it
                                }
                                host.addView(dock, FrameLayout.LayoutParams(-2, -2,
                                    Gravity.CENTER_VERTICAL or if (onRight) Gravity.RIGHT else Gravity.LEFT))
                                fun measure() {
                                    host.measure(View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(dp(720), View.MeasureSpec.EXACTLY))
                                    host.layout(0, 0, host.measuredWidth, host.measuredHeight)
                                }
                                fun bounds(view: View): Rect = Rect(0, 0, view.width, view.height).also {
                                    host.offsetDescendantRectToMyCoords(view, it)
                                }
                                fun touchPoint(x: Float, y: Float) {
                                    val now = SystemClock.uptimeMillis()
                                    // Detached Views cannot run posted click callbacks; test dispatch and click separately.
                                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
                                        val event = MotionEvent.obtain(now, now, action, x, y, 0)
                                        try { host.dispatchTouchEvent(event) } finally { event.recycle() }
                                    }
                                }
                                fun touchView(view: View) = bounds(view).let { touchPoint(it.exactCenterX(), it.exactCenterY()) }
                                dock.setActive(1)
                                measure()
                                val contentBounds = bounds(contentButton)
                                val dockBounds = bounds(dock)
                                val hostBounds = Rect(0, 0, host.width, host.height)
                                verify(!contentBounds.isEmpty && !dockBounds.isEmpty, "nonempty layout: $case")
                                verify(hostBounds.contains(contentBounds) && hostBounds.contains(dockBounds), "content and rail fit host: $case")
                                verify(!Rect.intersects(contentBounds, dockBounds), "content and rail never overlap: $case")
                                verify(if (onRight) contentBounds.right < dockBounds.left else dockBounds.right < contentBounds.left,
                                    "separate rail on chosen side: $case")

                                for (index in items.indices) {
                                    val row = dock.getChildAt(index) as LinearLayout
                                    val label = (0 until row.childCount).map(row::getChildAt).filterIsInstance<TextView>().single()
                                    val icon = (0 until row.childCount).map(row::getChildAt).filterIsInstance<ImageView>().single()
                                    verify(dockBounds.contains(bounds(row)) && dockBounds.contains(bounds(label)) && dockBounds.contains(bounds(icon)),
                                        "row and contents stay inside rail, item=$index: $case")
                                    verify(label.visibility == View.VISIBLE && label.alpha == 1f, "label is visible, item=$index: $case")
                                    val textLayout = label.layout
                                    verify(textLayout != null && textLayout.lineCount > 0 &&
                                        textLayout.getLineEnd(textLayout.lineCount - 1) == label.text.length &&
                                        (0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 } &&
                                        textLayout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom,
                                        "complete label fits without clipping, item=$index: $case")
                                    if (widthDp <= 900) verify(row.orientation == LinearLayout.VERTICAL,
                                        "narrow host uses icon above label, item=$index: $case")
                                }

                                val nearby = dock.getChildAt(2) as LinearLayout
                                nearby.setOnTouchListener { _, event ->
                                    if (event.actionMasked == MotionEvent.ACTION_DOWN) rowTouched = 4
                                    false
                                }
                                val nearbyLabel = (0 until nearby.childCount).map(nearby::getChildAt).filterIsInstance<TextView>().single()
                                val nearbyIcon = (0 until nearby.childCount).map(nearby::getChildAt).filterIsInstance<ImageView>().single()
                                // Touch the content edge beside the navigation label, where overlap used to occur.
                                touchPoint((if (onRight) contentBounds.right - dp(8) else contentBounds.left + dp(8)).toFloat(),
                                    bounds(nearbyLabel).exactCenterY())
                                verify(contentTouches == 1 && rowTouched == null, "content edge receives touch: $case")
                                contentButton.performClick()
                                verify(contentClicks == 1 && selected == null, "content action stays separate: $case")
                                touchView(nearbyLabel)
                                verify(rowTouched == 4 && contentTouches == 1, "label receives nav touch only: $case")
                                rowTouched = null
                                touchView(nearbyIcon)
                                verify(rowTouched == 4 && contentTouches == 1, "icon receives nav touch only: $case")
                                nearby.performClick()
                                verify(selected == 4 && dock.activeId == 4 && contentClicks == 1, "nav click selects nearby only: $case")

                                for (item in items) {
                                    dock.setActive(item.id)
                                    measure()
                                    verify(bounds(contentButton) == contentBounds && bounds(dock) == dockBounds,
                                        "selection preserves content and rail geometry, item=${item.id}: $case")
                                }
                                selected = null
                                nearby.performClick()
                                verify(selected == 4, "keyboard/accessibility click remains available: $case")
                                scenarios++
                            }
                        }
                    }
                }
            } catch (error: Throwable) { failure = error.cause ?: error }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putInt("checks", checks)
            putInt("scenarios", scenarios)
            putString("result", failure?.let { "FAIL: ${it.javaClass.simpleName}: ${it.message}" }
                ?: "PASS: reserved navigation rail, complete labels and independent content/navigation touches")
        })
    }
}
