package io.github.yuedaochangmendian.skybox

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * The compact temperature bar of the daily rows: a translucent track spanning the coldest and
 * warmest day of the visible forecast, with the selected day's min-to-max span highlighted.
 */
class TemperatureRangeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var min = Double.NaN
    private var max = Double.NaN
    private var globalMin = Double.NaN
    private var globalMax = Double.NaN
    private var shader: Shader? = null
    private var trackColor = 0x33FFFFFF

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setTrackColor(color: Int) {
        if (trackColor == color) return
        trackColor = color
        invalidate()
    }

    fun setRange(minimum: Double, maximum: Double, floor: Double, ceiling: Double) {
        if (min == minimum && max == maximum && globalMin == floor && globalMax == ceiling) return
        min = minimum
        max = maximum
        globalMin = floor
        globalMax = ceiling
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        shader = null
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val radius = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = trackColor
        rect.set(0f, 0f, width, height)
        canvas.drawRoundRect(rect, radius, radius, paint)

        if (!min.isFinite() || !max.isFinite() || !globalMin.isFinite() || !globalMax.isFinite()) return
        val span = Math.max(1.0, globalMax - globalMin)
        val start = ((min - globalMin) / span).coerceIn(0.0, 1.0).toFloat()
        val end = ((max - globalMin) / span).coerceIn(0.0, 1.0).toFloat()
        val left = start * width
        val right = Math.max(left + height, end * width)
        var gradient = shader
        if (gradient == null) {
            gradient = LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(0xFF9BE06B.toInt(), 0xFFE8D95C.toInt(), 0xFFFFC15A.toInt()),
                null, Shader.TileMode.CLAMP,
            )
            shader = gradient
        }
        paint.shader = gradient
        rect.set(left, 0f, right.coerceAtMost(width), height)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
    }
}
