package com.binteum.app.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Content-side shortcuts: the current section expands, touch/focus magnifies each icon. */
@android.annotation.SuppressLint("ViewConstructor")
class SectionDockView(context: Context, private val palette: AppPalette, private val onRight: Boolean,
                      items: List<Item>, private val select: (Int) -> Unit) : LinearLayout(context) {
    data class Item(val id: Int, val title: String, val icon: AppIcon)
    private data class Entry(val item: Item, val row: LinearLayout, val title: TextView, val icon: ImageView)
    private val entries = mutableListOf<Entry>()
    private val backdrop = DockBackdrop()
    @android.annotation.TargetApi(31)
    fun updateBackdrop(content: android.graphics.RenderNode, host: View) {
        val regions = entries.map { entry ->
            val view = if (entry.item.id == activeId) entry.row else entry.icon
            val rect = android.graphics.Rect(0, 0, view.width, view.height)
            offsetDescendantRectToMyCoords(view, rect)
            android.graphics.RectF(rect).apply { inset(-dp(10).toFloat(), -dp(8).toFloat()) }
        }
        backdrop.setRegions(regions)
        backdrop.update(content, host, this)
    }
    var activeId: Int? = null
        private set
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    init {
        orientation = VERTICAL
        background = backdrop
        gravity = if (onRight) Gravity.END else Gravity.START
        setPadding(dp(10), dp(10), dp(10), dp(10))
        clipChildren = false; clipToPadding = false
        items.forEach { item ->
            val icon = ImageView(context).apply {
                setImageDrawable(IconDrawable(item.icon, palette.text, dp(28)))
                setPadding(dp(14), dp(14), dp(14), dp(14))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val title = TextView(context).apply {
                text = item.title; textSize = 18f; setTextColor(palette.text)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(12), 0, dp(12), 0); alpha = 0f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false; clipToPadding = false
                isClickable = true; isFocusable = true; contentDescription = item.title
                if (onRight) { addView(title); addView(icon, LayoutParams(dp(56), dp(56))) }
                else { addView(icon, LayoutParams(dp(56), dp(56))); addView(title) }
                setOnClickListener { setActive(item.id); select(item.id) }
                setOnFocusChangeListener { _, focused -> magnify(icon, focused || item.id == activeId) }
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> magnify(icon, true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> magnify(icon, item.id == activeId)
                    }
                    false
                }
            }
            entries += Entry(item, row, title, icon)
            addView(row, LayoutParams(-2, dp(64)).apply { topMargin = dp(6) })
        }
        refresh()
    }
    private fun magnify(icon: View, selected: Boolean) {
        val scale = if (selected) 1.28f else 1f
        icon.animate().scaleX(scale).scaleY(scale)
            .setDuration(if (android.animation.ValueAnimator.areAnimatorsEnabled()) 90 else 0).start()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Hidden labels retain their measured width to keep scrolling stable, but
            // must not cover the underlying content with an invisible touch target.
            val hitsVisibleControl = entries.any { entry ->
                val target = if (entry.item.id == activeId) entry.row else entry.icon
                val rect = android.graphics.Rect(0, 0, target.width, target.height)
                offsetDescendantRectToMyCoords(target, rect)
                rect.contains(event.x.toInt(), event.y.toInt())
            }
            if (!hitsVisibleControl) return false
        }
        return super.dispatchTouchEvent(event)
    }
    fun setActive(id: Int?) {
        if (activeId == id) return
        activeId = id
        refresh()
    }
    private fun refresh() {
        entries.forEach { entry ->
            val active = entry.item.id == activeId
            entry.title.animate().alpha(if (active) 1f else 0f)
                .translationX(if (active) 0f else dp(if (onRight) 12 else -12).toFloat())
                .setDuration(if (android.animation.ValueAnimator.areAnimatorsEnabled()) 90 else 0).start()
            entry.row.isSelected = active
            magnify(entry.icon, active || entry.row.hasFocus())
        }
    }
    override fun onDetachedFromWindow() {
        backdrop.release()
        entries.forEach { it.icon.animate().cancel(); it.title.animate().cancel() }
        super.onDetachedFromWindow()
    }
}
