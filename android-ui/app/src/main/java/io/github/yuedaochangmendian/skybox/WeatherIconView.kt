package io.github.yuedaochangmendian.skybox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/** Original vector weather illustrations; no assets, icon fonts, bitmaps or third-party code. */
class WeatherIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var type = TYPE_UNKNOWN
    private var tint = 0xFF174268.toInt()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        updateDescription(null, null)
    }

    fun setType(value: Int) {
        val next = if (value in TYPE_UNKNOWN..TYPE_THUNDERSTORM) value else TYPE_UNKNOWN
        if (type != next) {
            type = next
            updateDescription(null, null)
            invalidate()
        }
    }

    fun getType(): Int = type

    fun setTint(color: Int) {
        if (tint != color) {
            tint = color
            invalidate()
        }
    }

    fun getTint(): Int = tint

    /** Also sets the localized accessibility description, including data freshness/error state. */
    fun setWeather(state: WeatherState?) {
        val theme = WeatherTheme.from(state)
        type = theme.iconType
        tint = theme.iconColor
        updateDescription(theme, state)
        invalidate()
    }

    fun setWeather(theme: WeatherTheme?) {
        val resolved = theme ?: WeatherTheme.from(null)
        type = resolved.iconType
        tint = resolved.iconColor
        updateDescription(resolved, null)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = Math.round(72 * resources.displayMetrics.density)
        val width = maxOf(suggestedMinimumWidth, size + paddingLeft + paddingRight)
        val height = maxOf(suggestedMinimumHeight, size + paddingTop + paddingBottom)
        setMeasuredDimension(
            resolveSizeAndState(width, widthMeasureSpec, 0),
            resolveSizeAndState(height, heightMeasureSpec, 0),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = (width - paddingLeft - paddingRight).toFloat()
        val height = (height - paddingTop - paddingBottom).toFloat()
        if (width <= 0 || height <= 0) return
        val size = minOf(width, height)
        val checkpoint = canvas.save()
        canvas.translate(
            paddingLeft + (width - size) / 2f,
            paddingTop + (height - size) / 2f,
        )
        canvas.scale(size / 100f, size / 100f)
        when (type) {
            TYPE_SUN -> sun(canvas, 50f, 50f, 19f)
            TYPE_MOON -> {
                moon(canvas, 50f, 49f, 30f)
                star(canvas, 76f, 25f, 4f)
                star(canvas, 79f, 56f, 2.5f)
            }
            TYPE_PARTLY_CLOUDY -> {
                sun(canvas, 66f, 32f, 12f)
                cloud(canvas, 0f, 7f, 0.95f)
            }
            TYPE_CLOUDY_NIGHT -> {
                moon(canvas, 67f, 29f, 18f)
                cloud(canvas, 0f, 9f, 0.95f)
            }
            TYPE_CLOUD -> cloud(canvas, 0f, 9f, 1f)
            TYPE_RAIN -> {
                cloud(canvas, 0f, -5f, 1f)
                stroke(4.5f, 1f)
                canvas.drawLine(34f, 67f, 28f, 80f, paint)
                canvas.drawLine(51f, 71f, 45f, 84f, paint)
                canvas.drawLine(68f, 67f, 62f, 80f, paint)
            }
            TYPE_SNOW -> {
                cloud(canvas, 0f, -9f, 1f)
                snowflake(canvas, 30f, 76f, 7f)
                snowflake(canvas, 51f, 84f, 6f)
                snowflake(canvas, 73f, 75f, 7f)
            }
            TYPE_FOG -> {
                cloud(canvas, 0f, -10f, 1f)
                stroke(4f, 0.75f)
                canvas.drawLine(19f, 68f, 67f, 68f, paint)
                canvas.drawLine(76f, 68f, 83f, 68f, paint)
                canvas.drawLine(28f, 78f, 80f, 78f, paint)
                canvas.drawLine(18f, 87f, 61f, 87f, paint)
            }
            TYPE_THUNDERSTORM -> {
                cloud(canvas, 0f, -8f, 1f)
                fill(1f)
                path.reset()
                path.moveTo(51f, 59f)
                path.lineTo(39f, 77f)
                path.lineTo(50f, 76f)
                path.lineTo(45f, 94f)
                path.lineTo(66f, 68f)
                path.lineTo(54f, 69f)
                path.lineTo(62f, 59f)
                path.close()
                canvas.drawPath(path, paint)
                stroke(3f, 0.75f)
                canvas.drawLine(28f, 68f, 24f, 78f, paint)
                canvas.drawLine(77f, 68f, 73f, 78f, paint)
            }
            else -> {
                stroke(4f, 0.65f)
                canvas.drawCircle(50f, 50f, 29f, paint)
                canvas.drawLine(38f, 50f, 62f, 50f, paint)
            }
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun sun(canvas: Canvas, x: Float, y: Float, radius: Float) {
        fill(0.17f)
        canvas.drawCircle(x, y, radius + 7, paint)
        fill(1f)
        canvas.drawCircle(x, y, radius, paint)
        stroke(3.5f, 1f)
        for (i in 0 until 8) {
            val angle = Math.PI * i / 4
            val dx = Math.cos(angle).toFloat()
            val dy = Math.sin(angle).toFloat()
            canvas.drawLine(
                x + dx * (radius + 10), y + dy * (radius + 10),
                x + dx * (radius + 16), y + dy * (radius + 16), paint,
            )
        }
    }

    private fun moon(canvas: Canvas, x: Float, y: Float, radius: Float) {
        // A crescent made of Bezier curves, not background-colored overpainting.
        fill(1f)
        path.reset()
        path.moveTo(x + radius * 0.35f, y - radius)
        path.cubicTo(
            x - radius * 1.18f, y - radius * 0.93f,
            x - radius * 1.2f, y + radius * 0.72f, x - radius * 0.03f, y + radius,
        )
        path.cubicTo(
            x + radius * 0.63f, y + radius * 1.09f,
            x + radius * 0.94f, y + radius * 0.62f, x + radius, y + radius * 0.35f,
        )
        path.cubicTo(
            x + radius * 0.06f, y + radius * 0.72f,
            x - radius * 0.56f, y - radius * 0.25f, x + radius * 0.35f, y - radius,
        )
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun cloud(canvas: Canvas, dx: Float, dy: Float, scale: Float) {
        val checkpoint = canvas.save()
        canvas.translate(dx + 50 * (1 - scale), dy + 50 * (1 - scale))
        canvas.scale(scale, scale)
        path.reset()
        path.moveTo(27f, 62f)
        path.cubicTo(8f, 62f, 9f, 39f, 27f, 37f)
        path.cubicTo(31f, 17f, 59f, 16f, 67f, 35f)
        path.cubicTo(86f, 30f, 97f, 57f, 77f, 62f)
        path.close()
        fill(0.16f)
        canvas.drawPath(path, paint)
        stroke(4f, 1f)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(checkpoint)
    }

    private fun snowflake(canvas: Canvas, x: Float, y: Float, radius: Float) {
        stroke(2.5f, 1f)
        for (i in 0 until 3) {
            val angle = Math.PI * i / 3
            val dx = (Math.cos(angle) * radius).toFloat()
            val dy = (Math.sin(angle) * radius).toFloat()
            canvas.drawLine(x - dx, y - dy, x + dx, y + dy, paint)
        }
    }

    private fun star(canvas: Canvas, x: Float, y: Float, radius: Float) {
        stroke(2f, 0.75f)
        canvas.drawLine(x - radius, y, x + radius, y, paint)
        canvas.drawLine(x, y - radius, x, y + radius, paint)
    }

    private fun fill(opacity: Float) {
        paint.style = Paint.Style.FILL
        paint.color = tint
        paint.alpha = Math.round(Color.alpha(tint) * opacity)
    }

    private fun stroke(width: Float, opacity: Float) {
        fill(opacity)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
    }

    private fun chinese(): Boolean =
        "zh" == resources.configuration.locales[0].language

    private fun updateDescription(theme: WeatherTheme?, state: WeatherState?) {
        val zh = chinese()
        var description = if (theme == null) typeDescription(zh)
        else if (zh) theme.weatherNameZh else theme.weatherNameEn
        if (state != null) {
            if (state.city.isNotEmpty()) description = state.city + "，" + description
            if (state.hasData()) {
                val locale = resources.configuration.locales[0]
                description += String.format(
                    locale,
                    if (zh) "，%.1f 摄氏度" else ", %.1f degrees Celsius",
                    state.temperature,
                )
            }
            if (state.loading) description += if (zh) "，正在更新" else ", updating"
            else if (state.error) description += if (zh) "，更新失败" else ", update failed"
            if (state.stale && state.hasData()) description += if (zh) "，上次天气数据" else ", last known weather"
        }
        contentDescription = description
    }

    private fun typeDescription(zh: Boolean): String = when (type) {
        TYPE_SUN -> if (zh) "晴" else "Sunny"
        TYPE_MOON -> if (zh) "晴朗夜空" else "Clear night"
        TYPE_PARTLY_CLOUDY -> if (zh) "多云" else "Partly cloudy"
        TYPE_CLOUDY_NIGHT -> if (zh) "多云夜空" else "Partly cloudy night"
        TYPE_CLOUD -> if (zh) "阴" else "Overcast"
        TYPE_RAIN -> if (zh) "雨" else "Rain"
        TYPE_SNOW -> if (zh) "雪" else "Snow"
        TYPE_FOG -> if (zh) "雾" else "Fog"
        TYPE_THUNDERSTORM -> if (zh) "雷暴" else "Thunderstorm"
        else -> if (zh) "天气未知" else "Weather unavailable"
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ImageView"
        info.contentDescription = contentDescription
    }

    companion object {
        const val TYPE_UNKNOWN = 0
        const val TYPE_SUN = 1
        const val TYPE_MOON = 2
        const val TYPE_PARTLY_CLOUDY = 3
        const val TYPE_CLOUDY_NIGHT = 4
        const val TYPE_CLOUD = 5
        const val TYPE_RAIN = 6
        const val TYPE_SNOW = 7
        const val TYPE_FOG = 8
        const val TYPE_THUNDERSTORM = 9
    }
}
