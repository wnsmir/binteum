package com.binteum.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One segmented switch; selection moves without rebuilding the screen. */
// Created only in code with a palette and state callback, never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
class ReminderModeToggle(context: Context, private val palette: AppPalette, target: Boolean,
                         private val onChanged: (Boolean) -> Unit) : FrameLayout(context) {
    private val thumb = View(context)
    private val labels = mutableListOf<TextView>()
    private var selected = target
    private var position = if (target) 1f else 0f
    private var animator: ValueAnimator? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(32).toFloat(); setStroke(dp(1), palette.border)
    }
    init {
        background = shape(palette.surfaceAlt)
        thumb.background = GradientDrawable().apply {
            setColor(if (palette.flat) palette.surfaceAlt else 0x665F6975); cornerRadius = dp(28).toFloat()
            setStroke(dp(2), if (palette.flat) palette.textMuted else 0xFF939DA8.toInt())
        }
        addView(thumb, LayoutParams(0, -1).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        val row = LinearLayout(context)
        listOf(context.getString(com.binteum.app.R.string.ui_text_170), context.getString(com.binteum.app.R.string.ui_text_171)).forEachIndexed { index, title ->
            val text = TextView(context).apply {
                this.text = title; textSize = 23f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isClickable = true; isFocusable = true
                setOnClickListener { if (this@ReminderModeToggle.isEnabled) select(index == 1) }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"
                        info.isCheckable = true; info.isChecked = (index == 1) == selected
                        info.isEnabled = this@ReminderModeToggle.isEnabled
                    }
                }
            }
            labels += text
            row.addView(text, LinearLayout.LayoutParams(0, -1, 1f))
        }
        addView(row, LayoutParams(-1, -1))
        updateLabels()
    }
    private fun updateLabels() {
        labels.forEachIndexed { i, label ->
            label.isSelected = (i == 1) == selected
            label.text = context.getString(if (i == 0) {
                if (label.isSelected) com.binteum.app.R.string.reminder_time_selected else com.binteum.app.R.string.reminder_time
            } else {
                if (label.isSelected) com.binteum.app.R.string.reminder_target_selected else com.binteum.app.R.string.reminder_target
            })
            label.setTextColor(if (palette.flat) { if (label.isSelected) palette.text else palette.textMuted } else if (label.isSelected) 0xFFFFFFFF.toInt() else 0xFFADB2BA.toInt())
            label.typeface = Typeface.create("sans-serif", if (label.isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
    }
    private fun select(target: Boolean) {
        if (target == selected) return
        selected = target; updateLabels(); onChanged(target)
        animator?.cancel()
        val end = if (target) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) { position = end; placeThumb(); return }
        animator = ValueAnimator.ofFloat(position, end).apply {
            duration = 240; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { position = it.animatedValue as Float; placeThumb() }
            start()
        }
    }
    private fun placeThumb() { thumb.translationX = position * (width - dp(8)) / 2f }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        thumb.layoutParams = (thumb.layoutParams as LayoutParams).apply { width = (w - dp(8)) / 2 }
        placeThumb()
    }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}
