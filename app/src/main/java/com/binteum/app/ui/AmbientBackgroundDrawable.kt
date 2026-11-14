package com.binteum.app.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import com.binteum.core.AmbientSky
import com.binteum.core.BackgroundMode
import com.binteum.core.SkyColors
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

/** Only the background invalidates: no layout rebuild, WebView reload or frame allocations. */
class AmbientBackgroundDrawable : Drawable() {
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseMatrix = Matrix()
    private val glowMatrix = Matrix()
    private var baseShader: LinearGradient? = null
    private var glowShader: RadialGradient? = null
    private var colors: SkyColors? = null
    private var mode = BackgroundMode.AUTO
    private var dark = true
    private var motion = true
    private var active = false
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 120_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidateSelf()
        }
    }

    fun configure(mode: BackgroundMode, dark: Boolean, motion: Boolean) {
        this.mode = mode
        if (this.dark != dark) colors = null
        this.dark = dark
        this.motion = motion
        updateClock()
        updateAnimation()
    }

    fun updateClock() {
        val time = LocalTime.now()
        val next = AmbientSky.colors(mode, time.hour * 60 + time.minute)
        if (next != colors) {
            colors = next
            rebuildShaders()
            invalidateSelf()
        }
    }

    fun setActive(active: Boolean) {
        this.active = active
        updateAnimation()
    }

    private fun updateAnimation() {
        val animate = active && motion && !mode.isFlat && ValueAnimator.areAnimatorsEnabled()
        if (animate) {
            if (animator.isPaused) animator.resume()
            else if (!animator.isStarted) animator.start()
        } else if (animator.isStarted && !animator.isPaused) animator.pause()
    }

    fun release() { animator.cancel() }

    override fun onBoundsChange(bounds: Rect) { rebuildShaders() }

    private fun rebuildShaders() {
        val c = colors ?: return
        if (bounds.isEmpty) return
        // Keep text contrast stable in either text theme, even over the brightest sky.
        fun tone(color: Int) = AmbientSky.blend(color,
            0xFF171A28.toInt(), if (dark) 0.70f else 0.58f)
        val h = bounds.height().toFloat()
        baseShader = LinearGradient(0f, -h * .2f, bounds.width() * .3f, h * 1.2f,
            intArrayOf(tone(c.top), tone(c.middle), tone(c.bottom)), null, Shader.TileMode.CLAMP)
        glowShader = RadialGradient(0f, 0f, maxOf(bounds.width(), bounds.height()) * .8f,
            intArrayOf((tone(c.bottom) and 0x00FFFFFF) or 0x66000000, tone(c.middle) and 0x00FFFFFF),
            null, Shader.TileMode.CLAMP)
        basePaint.shader = baseShader
        glowPaint.shader = glowShader
    }

    override fun draw(canvas: Canvas) {
        if (mode.isFlat) {
            basePaint.shader = null
            basePaint.color = if (mode == BackgroundMode.BLACK || (mode == BackgroundMode.SYSTEM && dark)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            canvas.drawRect(bounds, basePaint)
            return
        }
        basePaint.shader = baseShader
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val p = if (motion && ValueAnimator.areAnimatorsEnabled()) phase else 0f
        baseMatrix.setTranslate(bounds.left + sin(p * 2) * w * .08f, bounds.top + cos(p * 3) * h * .06f)
        baseShader?.setLocalMatrix(baseMatrix)
        glowMatrix.setTranslate(bounds.left + w * (.5f + sin(p) * .27f), bounds.top + h * (.5f + cos(p * 2) * .23f))
        glowShader?.setLocalMatrix(glowMatrix)
        canvas.drawRect(bounds, basePaint)
        canvas.drawRect(bounds, glowPaint)
    }

    override fun setAlpha(alpha: Int) { basePaint.alpha = alpha; glowPaint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { basePaint.colorFilter = filter; glowPaint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
