package com.binteum.app.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** The dock replays the same GPU content frame; it never calls View.draw into a bitmap. */
class DockBackdrop : Drawable() {
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var layers: Layers? = null
    private data class Region(val bounds: RectF, val edgeFade: Shader, val centerFade: Shader)
    private var regions = emptyList<Region>()

    fun setRegions(bounds: List<RectF>) {
        if (regions.map { it.bounds } == bounds) return
        fun fade(rect: RectF, solid: Float) = RadialGradient(.5f, .5f, .5f,
            intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT), floatArrayOf(0f, solid, 1f), Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(rect.width(), rect.height()); postTranslate(rect.left, rect.top) })
        }
        regions = bounds.filter { !it.isEmpty }.map { Region(RectF(it), fade(it, .8f), fade(it, .55f)) }
    }

    @android.annotation.TargetApi(31)
    fun update(content: RenderNode, host: View, dock: View) {
        if (dock.width <= 0 || dock.height <= 0) return
        val next = layers ?: Layers(dock.resources.displayMetrics.density).also { layers = it }
        next.update(content, host, dock)
        // Called inside the host's draw pass; invalidating here would create a redraw loop.
    }
    override fun draw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated || layers == null || bounds.isEmpty) return
        // Each item owns its blur footprint. Expanded text gets the full selected row footprint.
        for (region in regions) {
            val r = region.bounds
            val clip = canvas.save()
            canvas.clipRect(r)
            val outer = canvas.saveLayer(r, null)
            layers?.drawWeak(canvas)
            val center = canvas.saveLayer(r, null)
            layers?.drawStrong(canvas)
            mask.shader = region.centerFade; canvas.drawRect(r, mask)
            canvas.restoreToCount(center)
            mask.shader = region.edgeFade; canvas.drawRect(r, mask)
            canvas.restoreToCount(outer)
            canvas.restoreToCount(clip)
        }
    }

    fun release() { layers = null; regions = emptyList() }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    @android.annotation.TargetApi(31)
    private class Layers(density: Float) {
        private val weak = RenderNode("dock-edge-blur").apply {
            setRenderEffect(RenderEffect.createBlurEffect(3f * density, 3f * density, Shader.TileMode.CLAMP))
        }
        private val strong = RenderNode("dock-center-blur").apply {
            setRenderEffect(RenderEffect.createBlurEffect(22f * density, 22f * density, Shader.TileMode.CLAMP))
        }
        private val hostPosition = IntArray(2)
        private val ancestorPosition = IntArray(2)
        fun update(content: RenderNode, host: View, dock: View) {
            var ancestor: View? = host
            while (ancestor != null && ancestor.background == null) ancestor = ancestor.parent as? View
            host.getLocationInWindow(hostPosition)
            ancestor?.getLocationInWindow(ancestorPosition)
            fun record(node: RenderNode) {
                node.setPosition(0, 0, dock.width, dock.height)
                val canvas = node.beginRecording(dock.width, dock.height)
                ancestor?.let {
                    canvas.save()
                    canvas.translate((ancestorPosition[0] - hostPosition[0] - dock.left).toFloat(),
                        (ancestorPosition[1] - hostPosition[1] - dock.top).toFloat())
                    it.background?.draw(canvas)
                    canvas.restore()
                }
                canvas.translate(-dock.left.toFloat(), -dock.top.toFloat())
                canvas.drawRenderNode(content)
                node.endRecording()
            }
            record(weak); record(strong)
        }
        fun drawWeak(canvas: Canvas) = canvas.drawRenderNode(weak)
        fun drawStrong(canvas: Canvas) = canvas.drawRenderNode(strong)
    }
}

/** Separate content and navigation bounds; the rail never covers content or its touch targets. */
class DockContentLayout(context: android.content.Context) : FrameLayout(context) {
    private var contentNode: Any? = null
    private var measuredDock: SectionDockView? = null
    private var expandedDockWidth = 0
    private val gap get() = (8 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val source = getChildAt(0)
        val dock = getChildAt(1) as? SectionDockView
        if (source == null || dock == null) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val dockHeightSpec = MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST)
        if (measuredDock !== dock) {
            dock.setCompact(false)
            dock.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), dockHeightSpec)
            expandedDockWidth = dock.measuredWidth
            measuredDock = dock
        }
        val readingWidth = (960 * resources.displayMetrics.density * resources.configuration.fontScale.coerceAtLeast(1f)).toInt()
        dock.setCompact(availableWidth - expandedDockWidth - gap < readingWidth)
        dock.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST), dockHeightSpec)
        val sourceWidth = (availableWidth - dock.measuredWidth - gap).coerceAtLeast(0)
        source.measure(MeasureSpec.makeMeasureSpec(sourceWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val source = getChildAt(0)
        val dock = getChildAt(1) as? SectionDockView
        if (source == null || dock == null) { super.onLayout(changed, left, top, right, bottom); return }
        val gravity = (dock.layoutParams as LayoutParams).gravity
        val onRight = Gravity.getAbsoluteGravity(gravity, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT
        val dockLeft = if (onRight) width - paddingRight - dock.measuredWidth else paddingLeft
        val sourceLeft = if (onRight) paddingLeft else paddingLeft + dock.measuredWidth + gap
        val dockTop = paddingTop + ((height - paddingTop - paddingBottom - dock.measuredHeight) / 2).coerceAtLeast(0)
        source.layout(sourceLeft, paddingTop, sourceLeft + source.measuredWidth, paddingTop + source.measuredHeight)
        dock.layout(dockLeft, dockTop, dockLeft + dock.measuredWidth, dockTop + dock.measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val source = getChildAt(0)
        val dock = getChildAt(1) as? SectionDockView
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated || source == null || dock == null || width == 0 || height == 0) {
            super.dispatchDraw(canvas)
            return
        }
        drawGpu(canvas, source, dock)
    }
    @android.annotation.TargetApi(31)
    private fun drawGpu(canvas: Canvas, source: View, dock: SectionDockView) {
        val node = (contentNode as? RenderNode) ?: RenderNode("content-under-dock").also { contentNode = it }
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        drawChild(recording, source, drawingTime)
        node.endRecording()
        canvas.drawRenderNode(node)
        dock.updateBackdrop(node, this)
        drawChild(canvas, dock, drawingTime)
    }
    override fun onDetachedFromWindow() { contentNode = null; super.onDetachedFromWindow() }
}
