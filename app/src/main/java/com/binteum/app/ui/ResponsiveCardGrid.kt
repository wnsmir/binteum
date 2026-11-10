package com.binteum.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

/** Measures the actual content column; short final rows keep the same card width. */
class ResponsiveCardGrid @JvmOverloads constructor(
    context: Context,
    private val minCardWidthDp: Int = 280,
    private val maxCardWidthDp: Int = 380,
    private val maxColumns: Int = 3,
    private val gapDp: Int = 16,
    var centerContent: Boolean = false,
) : ViewGroup(context) {
    private var columns = 1
    private var cardWidth = 0
    private val rowHeights = mutableListOf<Int>()
    private val gap get() = (gapDp * resources.displayMetrics.density).toInt()

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val fontScale = resources.configuration.fontScale.coerceAtLeast(1f)
        val minCard = (minCardWidthDp * density * fontScale).toInt().coerceAtLeast(1)
        val maxCard = (maxCardWidthDp * density * fontScale).toInt().coerceAtLeast(minCard)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            maxCard + paddingLeft + paddingRight else MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        columns = ((available + gap) / (minCard + gap)).coerceIn(1, maxColumns.coerceAtLeast(1))
        cardWidth = min(maxCard, ((available - gap * (columns - 1)) / columns).coerceAtLeast(0))
        rowHeights.clear()
        var visibleIndex = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val row = visibleIndex++ / columns
            if (row == rowHeights.size) rowHeights.add(0)
            rowHeights[row] = max(rowHeights[row], child.measuredHeight)
        }
        val height = paddingTop + paddingBottom + rowHeights.sum() + gap * (rowHeights.size - 1).coerceAtLeast(0)
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val count = (0 until childCount).count { getChildAt(it).visibility != View.GONE }
        val usedColumns = min(columns, count)
        val gridWidth = cardWidth * usedColumns + gap * (usedColumns - 1).coerceAtLeast(0)
        val startX = paddingLeft + if (centerContent) ((width - paddingLeft - paddingRight - gridWidth) / 2).coerceAtLeast(0) else 0
        var index = 0
        var y = paddingTop
        for (childIndex in 0 until childCount) {
            val child = getChildAt(childIndex)
            if (child.visibility == View.GONE) continue
            val column = index % columns
            val row = index / columns
            if (column == 0 && row > 0) y += rowHeights[row - 1] + gap
            val x = startX + column * (cardWidth + gap)
            child.layout(x, y, x + cardWidth, y + child.measuredHeight)
            index++
        }
    }
}
