package com.binteum.app.media

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable

/** Offline provider identifiers; use the installed official app's own icon when available. */
object VideoProviderIcon {
    fun load(context: Context, provider: VideoProvider): Drawable = runCatching {
        context.packageManager.getApplicationIcon(provider.appPackage)
    }.getOrElse { ProviderSymbol(provider) }

    private class ProviderSymbol(private val provider: VideoProvider) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var opacity = 255
        private var filter: ColorFilter? = null
        private fun color(value: Int) {
            paint.color = value
            paint.alpha = opacity
            paint.colorFilter = filter
        }
        override fun draw(canvas: Canvas) {
            val checkpoint = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 48f, bounds.height() / 48f)
            when (provider) {
                VideoProvider.YOUTUBE -> {
                    color(0xFFFF0033.toInt())
                    canvas.drawRoundRect(1f, 8f, 47f, 40f, 9f, 9f, paint)
                    color(Color.WHITE)
                    canvas.drawPath(Path().apply {
                        moveTo(19f, 16f); lineTo(33f, 24f); lineTo(19f, 32f); close()
                    }, paint)
                }
                VideoProvider.NETFLIX -> {
                    color(0xFF101010.toInt())
                    canvas.drawRoundRect(1f, 1f, 47f, 47f, 10f, 10f, paint)
                    color(0xFFB20710.toInt())
                    canvas.drawRect(13f, 9f, 20f, 39f, paint)
                    canvas.drawRect(28f, 9f, 35f, 39f, paint)
                    color(0xFFE50914.toInt())
                    canvas.drawPath(Path().apply {
                        moveTo(13f, 9f); lineTo(21f, 9f); lineTo(35f, 39f); lineTo(27f, 39f); close()
                    }, paint)
                }
            }
            canvas.restoreToCount(checkpoint)
        }
        override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
