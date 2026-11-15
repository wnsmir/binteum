package com.binteum.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Content-side shortcuts inside a reserved rail. Selection never changes the rail's geometry. */
@android.annotation.SuppressLint("ViewConstructor")
class SectionDockView(context: Context, private val palette: AppPalette, private val onRight: Boolean,
                      items: List<Item>, private val select: (Int) -> Unit) : LinearLayout(context) {
    data class Item(val id: Int, val title: String, val icon: AppIcon)
    private data class Entry(val item: Item, val row: LinearLayout, val title: TextView, val icon: ImageView)
    private val entries = mutableListOf<Entry>()
    private val backdrop = DockBackdrop()
    private var compact = false
    @android.annotation.TargetApi(31)
    fun updateBackdrop(content: android.graphics.RenderNode, host: View) {
        val regions = entries.map { entry ->
            val view = entry.row
            val rect = android.graphics.Rect(0, 0, view.width, view.height)
            offsetDescendantRectToMyCoords(view, rect)
            android.graphics.RectF(rect).apply {
                inset(-dp(4).toFloat(), -dp(4).toFloat())
                intersect(0f, 0f, width.toFloat(), height.toFloat())
            }
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
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        clipChildren = true; clipToPadding = true
        items.forEach { item ->
            val icon = ImageView(context).apply {
                setImageDrawable(IconDrawable(item.icon, palette.text, dp(28)))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val title = TextView(context).apply {
                text = item.title; textSize = 18f; setTextColor(palette.text)
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val row = LinearLayout(context).apply {
                clipChildren = true; clipToPadding = true
                isClickable = true; isFocusable = true; contentDescription = item.title
                background = rowBackground()
                setOnClickListener { setActive(item.id); select(item.id) }
            }
            entries += Entry(item, row, title, icon)
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (entries.size > 1) topMargin = dp(6)
            })
        }
        configureEntries()
        refresh()
    }
    private fun rowBackground(): RippleDrawable {
        fun selectedSurface() = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(palette.surfaceAlt)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selectedSurface())
            addState(intArrayOf(android.R.attr.state_focused), selectedSurface())
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        val mask = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(palette.border), states, mask)
    }

    /** Called by the host before measurement; content and rail keep separate bounds. */
    fun setCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        configureEntries()
        requestLayout()
    }

    private fun configureEntries() {
        entries.forEach { entry ->
            entry.row.apply {
                removeAllViews()
                orientation = if (compact) VERTICAL else HORIZONTAL
                gravity = if (compact) Gravity.CENTER else Gravity.CENTER_VERTICAL or
                    if (onRight) Gravity.RIGHT else Gravity.LEFT
                minimumHeight = dp(if (compact) 76 else 64)
                setPadding(dp(if (compact) 6 else 4), dp(6), dp(if (compact) 6 else 4), dp(6))
            }
            entry.title.apply {
                textSize = if (compact) 15f else 18f
                gravity = if (compact) Gravity.CENTER else Gravity.CENTER_VERTICAL
                setPadding(dp(if (compact) 0 else 12), 0, dp(if (compact) 0 else 12), 0)
            }
            val iconSize = dp(if (compact) 40 else 56)
            val iconPadding = dp(if (compact) 6 else 14)
            entry.icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            val iconParams = LayoutParams(iconSize, iconSize)
            val titleParams = LayoutParams(if (compact) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT)
            if (compact || !onRight) {
                entry.row.addView(entry.icon, iconParams)
                entry.row.addView(entry.title, titleParams)
            } else {
                entry.row.addView(entry.title, titleParams)
                entry.row.addView(entry.icon, iconParams)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!compact) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // Keep the narrow rail useful with enlarged text without consuming the whole viewport.
        val textScale = resources.configuration.fontScale.coerceAtLeast(1.3f) / 1.3f
        val preferred = dp((104f * textScale).coerceAtMost(160f).toInt())
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(preferred, MeasureSpec.getSize(widthMeasureSpec))
            else -> preferred
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
    fun setActive(id: Int?) {
        if (activeId == id) return
        activeId = id
        refresh()
    }
    private fun refresh() {
        entries.forEach { entry ->
            val active = entry.item.id == activeId
            entry.row.isSelected = active
            if (Build.VERSION.SDK_INT >= 30) entry.row.stateDescription = if (active) {
                if (resources.configuration.locales[0].language == "ko") "선택됨" else "Selected"
            } else null
        }
    }
    override fun onDetachedFromWindow() {
        backdrop.release()
        super.onDetachedFromWindow()
    }
}
