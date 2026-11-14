package com.binteum.app.music

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable

/** Prefer the installed provider icon; offline vector identifiers remain available without its app. */
object MusicProviderIcon {
    fun load(context: Context, provider: MusicProvider): Drawable = runCatching {
        context.packageManager.getApplicationIcon(provider.appPackage)
    }.getOrElse { ProviderSymbol(provider) }
    private class ProviderSymbol(private val provider: MusicProvider) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 48f, bounds.height() / 48f)
            paint.style = Paint.Style.FILL
            when (provider) {
                MusicProvider.YOUTUBE_MUSIC -> {
                    paint.color = 0xFFFF0033.toInt(); canvas.drawCircle(24f, 24f, 23f, paint)
                    paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                    canvas.drawCircle(24f, 24f, 15f, paint); paint.style = Paint.Style.FILL
                    canvas.drawPath(Path().apply { moveTo(20f, 16f); lineTo(33f, 24f); lineTo(20f, 32f); close() }, paint)
                }
                MusicProvider.SPOTIFY -> {
                    paint.color = 0xFF1ED760.toInt(); canvas.drawCircle(24f, 24f, 23f, paint)
                    paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
                    for (i in 0..2) {
                        paint.strokeWidth = 3.5f - i * .45f
                        canvas.drawPath(Path().apply { moveTo(11f + i, 17f + i * 7); quadTo(25f, 11f + i * 7, 37f - i, 21f + i * 6) }, paint)
                    }
                }
                MusicProvider.APPLE_MUSIC -> {
                    paint.shader = LinearGradient(0f, 0f, 0f, 48f, 0xFFFF6377.toInt(), 0xFFEF2049.toInt(), Shader.TileMode.CLAMP)
                    canvas.drawRoundRect(1f, 1f, 47f, 47f, 11f, 11f, paint); paint.shader = null
                    paint.color = Color.WHITE
                    canvas.drawPath(Path().apply {
                        moveTo(19f, 13f); lineTo(35f, 9f); lineTo(35f, 32f); lineTo(31f, 32f)
                        lineTo(31f, 17f); lineTo(23f, 19f); lineTo(23f, 36f); lineTo(19f, 36f); close()
                    }, paint)
                    canvas.drawOval(11f, 31f, 23f, 40f, paint); canvas.drawOval(23f, 27f, 35f, 36f, paint)
                }
            }
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
