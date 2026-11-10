package com.binteum.app.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

enum class AppIcon {
    DASHBOARD, PLAY, SETTINGS, SUN, MOON, SYSTEM, BATTERY, CLOCK, LEAF, SPARKLES,
    QUIZ, ARROW_RIGHT, BACK, CHECK, CLOSE, RESET, PAUSE, SHIELD, INFO, CHEVRON_RIGHT, MAP, COFFEE, BED,
}

/** Original outline symbols on a shared 24-unit grid; no font or external asset. */
class IconDrawable(private val icon: AppIcon, color: Int, private val sizePx: Int) : Drawable() {
    private val outline = Path()
    private val arc = RectF(4f, 4f, 20f, 20f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init { require(sizePx > 0) }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val scale = minOf(bounds.width(), bounds.height()) / 24f
        val checkpoint = canvas.save()
        canvas.translate(bounds.left + (bounds.width() - scale * 24) / 2,
            bounds.top + (bounds.height() - scale * 24) / 2)
        canvas.scale(scale, scale)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
        fun path(block: Path.() -> Unit) { outline.reset(); outline.block(); canvas.drawPath(outline, paint) }
        fun dot(x: Float, y: Float) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, 1f, paint)
            paint.style = Paint.Style.STROKE
        }
        when (icon) {
            AppIcon.MAP -> {
                path { moveTo(3f, 5f); lineTo(9f, 3f); lineTo(15f, 5f); lineTo(21f, 3f); lineTo(21f, 19f); lineTo(15f, 21f); lineTo(9f, 19f); lineTo(3f, 21f); close() }
                line(9f, 3f, 9f, 19f); line(15f, 5f, 15f, 21f)
            }
            AppIcon.COFFEE -> {
                path { moveTo(4f, 8f); lineTo(17f, 8f); lineTo(17f, 15f); quadTo(17f, 19f, 13f, 19f); lineTo(8f, 19f); quadTo(4f, 19f, 4f, 15f); close() }
                path { moveTo(17f, 9f); lineTo(20f, 9f); cubicTo(24f, 9f, 23f, 15f, 17f, 15f) }
                line(3f, 22f, 20f, 22f); line(8f, 2f, 8f, 4f); line(13f, 2f, 13f, 4f)
            }
            AppIcon.BED -> {
                line(3f, 7f, 3f, 21f); line(21f, 12f, 21f, 21f); line(3f, 17f, 21f, 17f)
                canvas.drawRoundRect(5f, 10f, 10f, 15f, 1f, 1f, paint)
                path { moveTo(12f, 15f); lineTo(12f, 9f); lineTo(18f, 9f); quadTo(21f, 9f, 21f, 12f) }
            }
            AppIcon.DASHBOARD -> {
                canvas.drawRoundRect(3f, 3f, 10f, 10f, 1.6f, 1.6f, paint)
                canvas.drawRoundRect(14f, 3f, 21f, 10f, 1.6f, 1.6f, paint)
                canvas.drawRoundRect(3f, 14f, 10f, 21f, 1.6f, 1.6f, paint)
                canvas.drawRoundRect(14f, 14f, 21f, 21f, 1.6f, 1.6f, paint)
            }
            AppIcon.BACK -> { line(20f, 12f, 4f, 12f); line(4f, 12f, 11f, 5f); line(4f, 12f, 11f, 19f) }
            AppIcon.PLAY -> path { moveTo(8f, 4f); lineTo(20f, 12f); lineTo(8f, 20f); close() }
            AppIcon.SETTINGS -> {
                // Eight teeth and a central hub, recognizable as a settings cog.
                path {
                    for (i in 0 until 32) {
                        val angle = i * Math.PI / 16 - Math.PI / 2
                        val radius = if (i % 4 in 1..2) 10f else 8f
                        val x = 12f + (kotlin.math.cos(angle) * radius).toFloat()
                        val y = 12f + (kotlin.math.sin(angle) * radius).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                canvas.drawCircle(12f, 12f, 3.2f, paint)
            }
            AppIcon.SUN -> {
                canvas.drawCircle(12f, 12f, 4.2f, paint)
                line(12f, 2f, 12f, 4f); line(12f, 20f, 12f, 22f)
                line(2f, 12f, 4f, 12f); line(20f, 12f, 22f, 12f)
                line(4.9f, 4.9f, 6.4f, 6.4f); line(17.6f, 17.6f, 19.1f, 19.1f)
                line(4.9f, 19.1f, 6.4f, 17.6f); line(17.6f, 6.4f, 19.1f, 4.9f)
            }
            AppIcon.MOON -> path {
                moveTo(20.7f, 14.8f)
                cubicTo(16.7f, 16.2f, 12.7f, 14.8f, 10.7f, 11.4f)
                cubicTo(9.2f, 8.8f, 9.3f, 5.7f, 10.8f, 3.2f)
                cubicTo(5.8f, 3.7f, 2.6f, 8.3f, 3.5f, 13.2f)
                cubicTo(4.3f, 18f, 8.9f, 21.3f, 13.6f, 20.5f)
                cubicTo(16.8f, 20f, 19.4f, 17.8f, 20.7f, 14.8f); close()
            }
            AppIcon.SYSTEM -> {
                canvas.drawRoundRect(3f, 4f, 21f, 17f, 2f, 2f, paint)
                line(12f, 17f, 12f, 21f); line(8f, 21f, 16f, 21f)
            }
            AppIcon.BATTERY -> {
                canvas.drawRoundRect(2f, 6f, 19.5f, 18f, 2f, 2f, paint)
                line(22f, 10f, 22f, 14f)
                path { moveTo(11.5f, 8.5f); lineTo(8f, 12.5f); lineTo(12.5f, 12.5f); lineTo(10f, 15.5f) }
            }
            AppIcon.CLOCK -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                path { moveTo(12f, 6.5f); lineTo(12f, 12f); lineTo(15.5f, 14f) }
            }
            AppIcon.LEAF -> {
                path {
                    moveTo(20f, 3.5f); cubicTo(14f, 4f, 5.2f, 3.8f, 4f, 11.5f)
                    cubicTo(3f, 18f, 9.7f, 21f, 14.4f, 17.2f)
                    cubicTo(18.7f, 13.8f, 19.2f, 8.8f, 20f, 3.5f); close()
                }
                path { moveTo(3.5f, 21f); quadTo(7.5f, 14f, 14.5f, 9f) }
            }
            AppIcon.SPARKLES -> {
                path {
                    moveTo(10f, 3f); lineTo(12.4f, 9.6f); lineTo(19f, 12f)
                    lineTo(12.4f, 14.4f); lineTo(10f, 21f); lineTo(7.6f, 14.4f)
                    lineTo(1f, 12f); lineTo(7.6f, 9.6f); close()
                }
                line(19f, 2f, 19f, 6f); line(17f, 4f, 21f, 4f)
                line(20f, 18f, 20f, 22f); line(18f, 20f, 22f, 20f)
            }
            AppIcon.QUIZ -> {
                canvas.drawRoundRect(4f, 2.5f, 20f, 21.5f, 3f, 3f, paint)
                path { moveTo(9f, 8.5f); cubicTo(9f, 5.7f, 15f, 5.7f, 15f, 8.8f)
                    cubicTo(15f, 11.3f, 12f, 11.1f, 12f, 13.5f) }
                dot(12f, 17f)
            }
            AppIcon.ARROW_RIGHT -> {
                line(3f, 12f, 21f, 12f)
                path { moveTo(14f, 5f); lineTo(21f, 12f); lineTo(14f, 19f) }
            }
            AppIcon.CHECK -> path { moveTo(4f, 12.5f); lineTo(9.3f, 18f); lineTo(20f, 6f) }
            AppIcon.CLOSE -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
            AppIcon.RESET -> {
                canvas.drawArc(arc, 215f, 290f, false, paint)
                path { moveTo(3.5f, 3.5f); lineTo(3.5f, 8.5f); lineTo(8.5f, 8.5f) }
            }
            AppIcon.PAUSE -> {
                canvas.drawRoundRect(6f, 4f, 9f, 20f, 1f, 1f, paint)
                canvas.drawRoundRect(15f, 4f, 18f, 20f, 1f, 1f, paint)
            }
            AppIcon.SHIELD -> {
                path { moveTo(12f, 2.5f); lineTo(20f, 6f); lineTo(20f, 11f)
                    cubicTo(20f, 16f, 16f, 19.6f, 12f, 21.5f)
                    cubicTo(8f, 19.6f, 4f, 16f, 4f, 11f); lineTo(4f, 6f); close() }
                path { moveTo(8f, 11.6f); lineTo(10.8f, 14.5f); lineTo(16f, 9.2f) }
            }
            AppIcon.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                dot(12f, 7.5f); line(12f, 11f, 12f, 17f)
            }
            AppIcon.CHEVRON_RIGHT -> path { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
        }
        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) {
        if (paint.alpha != alpha) { paint.alpha = alpha; invalidateSelf() }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (paint.colorFilter != colorFilter) { paint.colorFilter = colorFilter; invalidateSelf() }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
}
