package io.github.yuedaochangmendian.skybox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** Decorative sky and connection orbit. Does not represent measurements or traffic. */
class WeatherView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var connected = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setConnected(value: Boolean) {
        if (connected != value) {
            connected = value
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return
        canvas.save()
        canvas.scale(width / 360f, height / 228f)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, 330f, 228f,
            intArrayOf(Color.rgb(99, 180, 255), Color.rgb(42, 116, 244), Color.rgb(52, 91, 224)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 360f, 228f, paint)
        paint.shader = null
        paint.color = 0x12FFFFFF
        canvas.drawCircle(318f, 10f, 136f, paint)
        paint.color = 0x0CFFFFFF
        canvas.drawCircle(318f, 10f, 171f, paint)
        paint.color = 0x24FFFFFF
        canvas.drawCircle(273f, 71f, 29f, paint)
        paint.color = 0xB8FFFFFF.toInt()
        canvas.drawCircle(273f, 71f, 20f, paint)
        cloud(canvas, 258f, 106f, 1.1f, 0xE6FFFFFF.toInt())
        cloud(canvas, 194f, 136f, 0.68f, 0x75FFFFFF)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0x33FFFFFF
        canvas.drawArc(RectF(-70f, 116f, 440f, 326f), 194f, 151f, false, paint)
        paint.color = 0x24FFFFFF
        canvas.drawArc(RectF(-80f, 136f, 440f, 356f), 194f, 151f, false, paint)
        paint.style = Paint.Style.FILL
        val x = if (connected) 211f else 140f
        val y = if (connected) 124f else 133f
        paint.color = 0x32FFFFFF
        canvas.drawCircle(x, y, 10f, paint)
        paint.color = 0xEDFFFFFF.toInt()
        canvas.drawCircle(x, y, 3.5f, paint)
        path.reset()
        path.moveTo(0f, 216f)
        path.cubicTo(80f, 183f, 128f, 236f, 226f, 208f)
        path.cubicTo(285f, 190f, 320f, 207f, 360f, 185f)
        path.lineTo(360f, 228f)
        path.lineTo(0f, 228f)
        path.close()
        paint.color = 0x0DFFFFFF
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun cloud(canvas: Canvas, x: Float, y: Float, scale: Float, color: Int) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        paint.style = Paint.Style.FILL
        paint.color = color
        path.reset()
        path.moveTo(-36f, 12f)
        path.cubicTo(-49f, 12f, -49f, -9f, -34f, -10f)
        path.cubicTo(-33f, -35f, 3f, -39f, 11f, -14f)
        path.cubicTo(34f, -22f, 45f, 12f, 23f, 12f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
