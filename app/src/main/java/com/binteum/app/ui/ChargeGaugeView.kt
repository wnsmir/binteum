package com.binteum.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A 270-degree SOC ring. The host overlays text and owns its accessibility label. */
class ChargeGaugeView(context: Context) : View(context) {
    private var percent: Int? = null
    private var palette = AppTheme.palette(context, ThemeMode.SYSTEM)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Invalid or unavailable SOC displays only the neutral track, never a made-up percentage. */
    fun update(percent: Int?, palette: AppPalette) {
        val normalized = percent?.takeIf { it in 0..100 }
        if (this.percent == normalized && this.palette == palette) return
        this.percent = normalized
        this.palette = palette
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (280 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        val diameter = minOf(availableWidth, availableHeight)
        if (diameter <= 0) return
        val density = resources.displayMetrics.density
        paint.strokeWidth = minOf(12 * density, diameter / 20)
        val inset = minOf(16 * density, diameter / 12) + paint.strokeWidth / 2
        val radius = (diameter / 2 - inset).coerceAtLeast(0f)
        if (radius <= 0) return
        val centerX = paddingLeft + availableWidth / 2
        val centerY = paddingTop + availableHeight / 2
        arcBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        paint.color = palette.border
        canvas.drawArc(arcBounds, 135f, 270f, false, paint)
        percent?.takeIf { it > 0 }?.let {
            paint.color = palette.textMuted
            canvas.drawArc(arcBounds, 135f, 270f * it / 100, false, paint)
        }
    }
}
