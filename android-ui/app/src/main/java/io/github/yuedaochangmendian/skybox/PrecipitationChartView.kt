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
 * 15-minute precipitation nowcast bars with the dashed 大/中/小 grid of the reference layout.
 * Values are millimetres per 15 minutes; the axis scales to the largest value in the window so a
 * light drizzle still renders visibly, and every bar keeps a minimum height when it is non-zero.
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

        val count = if (values.isEmpty()) 8 else values.size
        val slot = width / count
        val barWidth = Math.max(density * 4f, slot * 0.52f)
        val peak = values.maxOrNull() ?: 0.0
        val scale = if (peak <= 0.0) 1.0 else Math.max(peak, 0.25)

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

        // Hour separators: a subtle dotted line every four 15-minute slots.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Math.max(1f, density * 0.6f)
        paint.pathEffect = DashPathEffect(floatArrayOf(1f, 5f), 0f)
        paint.color = gridColor
        for (i in 4 until count step 4) {
            val x = slot * i
            canvas.drawLine(x, 0f, x, height, paint)
        }
        paint.pathEffect = null

        for (i in 0 until count) {
            val value = values.getOrNull(i) ?: 0.0
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
