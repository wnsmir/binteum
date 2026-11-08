package com.binteum.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Original, unbranded vehicle illustration drawn locally. */
class CarSilhouetteView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Path().apply {
            moveTo(30f, 135f); lineTo(48f, 103f); lineTo(124f, 89f)
            cubicTo(167f, 35f, 214f, 32f, 280f, 37f)
            cubicTo(324f, 39f, 357f, 60f, 391f, 87f)
            lineTo(476f, 104f); quadTo(507f, 115f, 510f, 146f)
            lineTo(493f, 158f); lineTo(53f, 158f); close()
        }
    private val glass = Path().apply {
            moveTo(148f, 86f); quadTo(185f, 47f, 237f, 49f)
            lineTo(269f, 50f); lineTo(269f, 87f); close()
            moveTo(282f, 50f); quadTo(330f, 54f, 368f, 87f)
            lineTo(282f, 87f); close()
        }
    private val wheels = floatArrayOf(124f, 413f)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 540f, height / 200f)
        canvas.save()
        canvas.translate((width - 540 * scale) / 2, (height - 200 * scale) / 2)
        canvas.scale(scale, scale)
        paint.color = Color.rgb(219, 223, 221)
        canvas.drawOval(28f, 154f, 518f, 178f, paint)

        paint.color = Color.rgb(126, 148, 142); canvas.drawPath(body, paint)

        paint.color = Color.rgb(224, 239, 237); canvas.drawPath(glass, paint)
        wheels.forEach { x ->
            paint.color = Color.rgb(47, 55, 52); canvas.drawCircle(x, 151f, 35f, paint)
            paint.color = Color.rgb(202, 212, 208); canvas.drawCircle(x, 151f, 20f, paint)
            paint.color = Color.rgb(105, 122, 115); canvas.drawCircle(x, 151f, 7f, paint)
        }
        paint.color = Color.rgb(245, 242, 215); canvas.drawRoundRect(474f, 113f, 501f, 124f, 5f, 5f, paint)
        canvas.restore()
    }
}
