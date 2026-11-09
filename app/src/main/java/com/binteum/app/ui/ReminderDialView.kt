package com.binteum.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A 270-degree knob with touch, keyboard and accessibility range controls. */
class ReminderDialView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private var palette = AppTheme.palette(context, ThemeMode.DARK)
    private var dragEmphasis = 0f
    private var dragAnimator: android.animation.ValueAnimator? = null
    private fun emphasizeDrag(dragging: Boolean) {
        dragAnimator?.cancel()
        val end = if (dragging) 1f else 0f
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { dragEmphasis = end; invalidate(); return }
        dragAnimator = android.animation.ValueAnimator.ofFloat(dragEmphasis, end).apply {
            duration = if (dragging) 110 else 180
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { dragEmphasis = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() {
        dragAnimator?.cancel(); dragEmphasis = 0f
        super.onDetachedFromWindow()
    }
    private var ringColor = 0xFFD6DADF.toInt()
    fun updateTint(color: Int) { if (ringColor != color) { ringColor = color; invalidate() } }
    private var minimum = 1
    private var maximum = 120
    private var value = 20
    private var unit = context.getString(com.binteum.app.R.string.ui_text_017)
    private var editable = true
    private var fraction = 0f
    private var title = "20$unit"
    private var subtitle = context.getString(com.binteum.app.R.string.ui_text_126)
    var onValueChanged: ((Int) -> Unit)? = null

    init { isFocusable = true; isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun configure(value: Int, minimum: Int, maximum: Int, unit: String, palette: AppPalette, enabled: Boolean) {
        this.minimum = minimum; this.maximum = maximum; this.unit = unit; this.palette = palette
        this.value = value.coerceIn(minimum, maximum); editable = true; isEnabled = enabled
        title = "${this.value}$unit"; subtitle = if (unit == "%") context.getString(com.binteum.app.R.string.ui_text_125) else context.getString(com.binteum.app.R.string.ui_text_126)
        fraction = (this.value - minimum).toFloat() / (maximum - minimum)
        contentDescription = context.getString(com.binteum.app.R.string.dial_accessibility, title, subtitle)
        invalidate()
    }

    fun progress(title: String, subtitle: String, fraction: Float) {
        editable = false; this.title = title; this.subtitle = subtitle
        this.fraction = fraction.coerceIn(0f, 1f)
        contentDescription = "$title $subtitle"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f
        val radius = (minOf(width, height) / 2f - 24 * d).coerceAtLeast(0f)
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = (9 + 5 * dragEmphasis) * d; paint.strokeCap = Paint.Cap.ROUND
        paint.color = palette.border; canvas.drawArc(arc, 135f, 270f, false, paint)
        paint.color = ringColor; canvas.drawArc(arc, 135f, 270f * fraction, false, paint)
        paint.style = Paint.Style.FILL
        if (editable) {
            val angle = Math.toRadians((135 + 270 * fraction).toDouble())
            canvas.drawCircle(cx + cos(angle).toFloat() * radius, cy + sin(angle).toFloat() * radius, (13 + 5 * dragEmphasis) * d, paint)
        }
        paint.color = palette.text; paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 52f, resources.displayMetrics)
        val available = radius * 1.6f
        if (paint.measureText(title) > available) paint.textSize *= available / paint.measureText(title)
        canvas.drawText(title, cx, cy - (paint.ascent() + paint.descent()) / 2 - 8 * d, paint)
        paint.color = palette.textMuted; paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
        canvas.drawText(subtitle, cx, cy + 37 * d, paint)
        if (isFocused) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d; paint.color = palette.text
            canvas.drawCircle(cx, cy, radius + 18 * d, paint)
        }
    }

    private fun change(next: Int) {
        val n = next.coerceIn(minimum, maximum)
        if (n == value) return
        value = n; title = "$value$unit"; fraction = (value - minimum).toFloat() / (maximum - minimum)
        contentDescription = context.getString(com.binteum.app.R.string.dial_accessibility, title, subtitle)
        onValueChanged?.invoke(value); invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val distance = kotlin.math.hypot(event.x - width / 2f, event.y - height / 2f)
                val radius = minOf(width, height) / 2f - 24 * resources.displayMetrics.density
                if (distance < maxOf(16 * resources.displayMetrics.density, radius * .16f)) return false
                parent?.requestDisallowInterceptTouchEvent(true); isPressed = true; emphasizeDrag(true)
            }
            MotionEvent.ACTION_MOVE -> Unit
            MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false); isPressed = false; emphasizeDrag(false); performClick() }
            MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); isPressed = false; emphasizeDrag(false); return true }
            else -> return false
        }
        // Close to the centre the angle is unstable; retain the last value while dragging through it.
        if (kotlin.math.hypot(event.x - width / 2f, event.y - height / 2f) < 16 * resources.displayMetrics.density) return true
        var angle = (Math.toDegrees(atan2((event.y - height / 2f).toDouble(), (event.x - width / 2f).toDouble())) - 135 + 360) % 360
        if (angle > 270) angle = if (angle < 315) 270.0 else 0.0
        change(minimum + kotlin.math.round(angle / 270 * (maximum - minimum)).toInt())
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (editable && isEnabled && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            change(value + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (editable && isEnabled) {
            info.className = "android.widget.SeekBar"
            info.rangeInfo = if (android.os.Build.VERSION.SDK_INT >= 30) AccessibilityNodeInfo.RangeInfo(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, minimum.toFloat(), maximum.toFloat(), value.toFloat()) else legacyRange()
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }
    @Suppress("DEPRECATION")
    private fun legacyRange() = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, minimum.toFloat(), maximum.toFloat(), value.toFloat())

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (editable && isEnabled) when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { change(value + 1); return true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { change(value - 1); return true }
            android.R.id.accessibilityActionSetProgress -> {
                val next = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false
                if (!next.isFinite()) return false
                change(next.toInt()); return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
