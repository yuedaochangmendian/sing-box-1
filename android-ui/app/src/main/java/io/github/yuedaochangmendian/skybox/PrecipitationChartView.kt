package io.github.yuedaochangmendian.skybox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Shared bar chart for the home screen.
 *
 * Two different timelines use it, so the axes are configurable:
 * - 15-minute precipitation nowcast: default settings, bars fill left → right starting at "now".
 * - 10-minute speed curve: [setSlotCount] fixes the bucket count (60 × 10 s) and, whenever fewer
 *   samples have been collected, bars are drawn right-aligned so the newest bar sits at "现在"
 *   and the left edge really is 10 minutes ago.
 *
 * Values are millimetres per slot for rain and bytes per 10 s for speed; the axis scales to the
 * largest value in the window so a light drizzle still renders visibly, and every bar keeps a
 * minimum height when it is non-zero.
 */
class PrecipitationChartView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(5f, 6f), 0f)
    private val rect = RectF()
    private var values: List<Double> = emptyList()
    private var barShader: Shader? = null
    private var barTop = Color.argb(235, 186, 227, 255)
    private var barBottom = Color.argb(150, 120, 186, 240)
    private var gridColor = 0x33FFFFFF
    private var slotCount = 0
    private var separatorSlots = 4

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setValues(next: List<Double>) {
        values = next
        barShader = null
        invalidate()
    }

    fun setGridColor(color: Int) {
        if (gridColor == color) return
        gridColor = color
        invalidate()
    }

    fun setBarColors(top: Int, bottom: Int) {
        if (top == barTop && bottom == barBottom) return
        barTop = top
        barBottom = bottom
        barShader = null
        invalidate()
    }

    /**
     * Fixes the number of horizontal slots. With a fixed count the bars are right-aligned to the
     * newest sample, so the axis labels keep meaning something while history is still filling up.
     */
    fun setSlotCount(count: Int) {
        val next = Math.max(0, count)
        if (slotCount == next) return
        slotCount = next
        invalidate()
    }

    /** Draws the dotted separator every [slots] slots; 0 disables it. */
    fun setSeparatorSlots(slots: Int) {
        val next = Math.max(0, slots)
        if (separatorSlots == next) return
        separatorSlots = next
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        barShader = null
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val density = resources.displayMetrics.density

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Math.max(1f, density * 0.7f)
        paint.pathEffect = dash
        paint.color = gridColor
        for (i in 1..3) {
            val y = height * i / 3.4f
            canvas.drawLine(0f, y, width, y, paint)
        }
        paint.pathEffect = null
        paint.color = gridColor
        canvas.drawLine(0f, height, width, height, paint)

        val count = Math.max(if (values.isEmpty() && slotCount == 0) 8 else slotCount, values.size)
        val slot = width / count
        val barWidth = Math.max(density * 4f, slot * 0.52f)
        val peak = values.maxOrNull() ?: 0.0
        val scale = if (peak <= 0.0) 1.0 else Math.max(peak, 0.25)
        // A fixed slot count keeps the newest bucket glued to the right edge ("现在").
        val offset = count - values.size

        var shader = barShader
        if (shader == null) {
            shader = LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(barTop, barBottom),
                null, Shader.TileMode.CLAMP,
            )
            barShader = shader
        }
        barPaint.shader = shader
        barPaint.style = Paint.Style.FILL

        // Separator lines: hourly for the nowcast (4 × 15 min), every 5 minutes for the speed curve.
        if (separatorSlots > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Math.max(1f, density * 0.6f)
            paint.pathEffect = DashPathEffect(floatArrayOf(1f, 5f), 0f)
            paint.color = gridColor
            for (i in separatorSlots until count step separatorSlots) {
                val x = slot * i
                canvas.drawLine(x, 0f, x, height, paint)
            }
            paint.pathEffect = null
        }

        for (i in 0 until count) {
            val value = values.getOrNull(i - offset) ?: 0.0
            val left = slot * i + (slot - barWidth) / 2f
            val barHeight = if (value <= 0.0) {
                0f
            } else {
                Math.max(density * 3f, (value / scale).toFloat() * (height - density * 2f))
            }
            if (barHeight <= 0f) continue
            rect.set(left, height - barHeight, left + barWidth, height)
            val radius = Math.min(barWidth / 2f, density * 3f)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }
    }
}
