package com.binteum.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Original decorative shapes; the adjacent label carries all meaning. */
class WellnessArtView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var kind: Int = 0
        set(value) { field = value; invalidate() }

    private class Stone(val cx: Float, val cy: Float, val rx: Float, val ry: Float, light: Int, dark: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx - rx / 3, cy - ry / 2, rx * 1.8f, light, dark, Shader.TileMode.CLAMP)
        }
    }

    private val shapes = listOf(
        listOf(
            Stone(88f, 76f, 43f, 15f, 0xFFFFBD9D.toInt(), 0xFFC76854.toInt()),
            Stone(94f, 55f, 32f, 16f, 0xFFFFD2B2.toInt(), 0xFFDE8163.toInt()),
            Stone(99f, 32f, 22f, 17f, 0xFFFFDDC5.toInt(), 0xFFE78E71.toInt()),
        ),
        listOf(
            Stone(86f, 50f, 41f, 41f, 0xFFD0B8FF.toInt(), 0xFF7360BC.toInt()),
            Stone(97f, 45f, 27f, 30f, 0xFFA18AE4.toInt(), 0xFF6453AA.toInt()),
        ),
        listOf(
            Stone(86f, 78f, 39f, 16f, 0xFF8CDFB6.toInt(), 0xFF3B907D.toInt()),
            Stone(87f, 54f, 28f, 16f, 0xFFA3EAC1.toInt(), 0xFF4CA58A.toInt()),
            Stone(91f, 30f, 17f, 17f, 0xFFC2F5CD.toInt(), 0xFF63B795.toInt()),
        ),
    )
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 220, 207, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val saved = canvas.save()
        val scale = minOf(width / 160f, height / 100f)
        canvas.translate((width - 160 * scale) / 2, (height - 100 * scale) / 2)
        canvas.scale(scale, scale)
        val stones = shapes[kind.coerceIn(0, 2)]
        for (i in stones.indices) {
            val s = stones[i]
            canvas.drawOval(s.cx - s.rx, s.cy - s.ry, s.cx + s.rx, s.cy + s.ry, s.paint)
        }
        if (kind == 1) canvas.drawCircle(86f, 50f, 46f, ring)
        canvas.restoreToCount(saved)
    }
}
