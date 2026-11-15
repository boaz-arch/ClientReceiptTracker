package com.company.supportvisit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Small donut chart (Vico has no pie charts). Slices start at 12 o'clock and go clockwise in
 * left-to-right layouts, counter-clockwise in right-to-left ones.
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Slice(val value: Float, val color: Int)

    private var slices: List<Slice> = emptyList()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val oval = RectF()

    fun setSlices(newSlices: List<Slice>) {
        slices = newSlices.filter { it.value > 0 }
        contentDescription = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = slices.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) return

        val stroke = min(width, height) * 0.18f
        val inset = stroke / 2 + paddingStart.coerceAtLeast(paddingLeft)
        val size = min(width, height) - 2 * inset
        val left = (width - size) / 2
        val top = (height - size) / 2
        oval.set(left, top, left + size, top + size)
        paint.strokeWidth = stroke

        val direction = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f
        val gap = if (slices.size > 1) 2f else 0f
        var start = -90f
        slices.forEach { slice ->
            val sweep = 360f * slice.value / total
            paint.color = slice.color
            canvas.drawArc(oval, start + direction * gap / 2, direction * (sweep - gap), false, paint)
            start += direction * sweep
        }
    }
}
