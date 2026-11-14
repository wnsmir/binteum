package com.binteum.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Theme-colored digital wheel; touch, directional keys and screen-reader adjustments. */
class TimeWheelView(context: Context, private val count: Int, initial: Int,
                    private val palette: AppPalette, private val unit: String) : View(context) {
    var value = initial
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val step get() = height / 3f
    private var offset = 0f
    private var lastY = 0f
    private var downY = 0f
    private var settle: ValueAnimator? = null
    init { isFocusable = true; isClickable = true; describe() }
    private fun describe() { contentDescription = "%02d %s".format(value, unit) }
    private fun change(delta: Int) { value = (value + delta + count) % count; describe(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        paint.color = palette.surfaceAlt
        canvas.drawRoundRect(8f, cy - step / 2, width - 8f, cy + step / 2, 20f, 20f, paint)
        paint.textAlign = Paint.Align.CENTER
        for (i in -2..2) {
            paint.color = if (i == 0) palette.text else palette.textMuted
            paint.alpha = if (i == 0) 255 else 125
            paint.textSize = (if (i == 0) 52 else 30) * resources.displayMetrics.scaledDensity
            paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            canvas.drawText("%02d".format((value + i + count) % count), width / 2f,
                cy + i * step + offset - (paint.ascent() + paint.descent()) / 2, paint)
        }
        paint.alpha = 255
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { settle?.cancel(); lastY = event.y; downY = event.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                offset += event.y - lastY; lastY = event.y
                while (offset > step / 2) { change(-1); offset -= step }
                while (offset < -step / 2) { change(1); offset += step }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && kotlin.math.abs(event.y - downY) < 8 && kotlin.math.abs(offset) < 8) {
                    if (event.y < height / 3f) change(-1) else if (event.y > height * 2 / 3f) change(1)
                    performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                settle = ValueAnimator.ofFloat(offset, 0f).apply {
                    duration = 160; addUpdateListener { offset = it.animatedValue as Float; invalidate() }; start()
                }
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { settle?.cancel(); super.onDetachedFromWindow() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            change(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.NumberPicker"
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            change(if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1); return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
