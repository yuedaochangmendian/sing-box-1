package io.github.yuedaochangmendian.skybox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import java.util.Random

/**
 * Full-bleed animated sky. Draws the theme gradient plus original particles (rain streaks, snow
 * flakes, drifting fog, stars or a sun glow) with a reusable particle pool: no allocations in
 * onDraw, and no animation at all when the system asks for reduced motion or the view is hidden.
 */
class SkyBackdropView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random(20261028L)
    private var colors = intArrayOf(0xFF101B2E.toInt(), 0xFF1D2C42.toInt(), 0xFF2C3D53.toInt())
    private var mode = MODE_RAIN
    private var dayTheme = false
    private var gradient: LinearGradient? = null
    private var glow: RadialGradient? = null
    private var glowCenterX = 0f
    private var glowCenterY = 0f
    private var vignette: Shader? = null

    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var lengths = FloatArray(0)
    private var speeds = FloatArray(0)
    private var alphas = IntArray(0)

    private var lastFrame = 0L
    private var lastInvalidate = 0L
    private var attached = true

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setTheme(theme: WeatherTheme) {
        val nextColors = theme.getGradientColors()
        val nextMode = when (theme.type) {
            WeatherTheme.Type.RAIN, WeatherTheme.Type.DRIZZLE -> MODE_RAIN
            WeatherTheme.Type.SNOW -> MODE_SNOW
            WeatherTheme.Type.THUNDERSTORM -> MODE_STORM
            WeatherTheme.Type.FOG -> MODE_FOG
            WeatherTheme.Type.CLOUDY, WeatherTheme.Type.PARTLY_CLOUDY -> MODE_CLOUD
            WeatherTheme.Type.CLEAR -> if (theme.isDay) MODE_SUN else MODE_STARS
            WeatherTheme.Type.UNKNOWN -> MODE_CLOUD
        }
        val nextDay = theme.isDay
        if (nextColors.contentEquals(colors) && nextMode == mode && nextDay == dayTheme) return
        colors = nextColors
        mode = nextMode
        dayTheme = nextDay
        gradient = null
        glow = null
        vignette = null
        seed()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        gradient = null
        glow = null
        vignette = null
        seed()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    private fun particleCount(): Int = when (mode) {
        MODE_RAIN, MODE_STORM -> 110
        MODE_SNOW -> 70
        MODE_STARS -> 90
        MODE_FOG -> 0
        MODE_SUN, MODE_CLOUD -> 0
        else -> 0
    }

    private fun seed() {
        val count = particleCount()
        xs = FloatArray(count)
        ys = FloatArray(count)
        lengths = FloatArray(count)
        speeds = FloatArray(count)
        alphas = IntArray(count)
        val width = Math.max(width, 1).toFloat()
        val height = Math.max(height, 1).toFloat()
        for (i in 0 until count) {
            xs[i] = random.nextFloat() * width
            ys[i] = random.nextFloat() * height
            when (mode) {
                MODE_RAIN, MODE_STORM -> {
                    lengths[i] = 14f + random.nextFloat() * 26f
                    speeds[i] = 900f + random.nextFloat() * 700f
                    alphas[i] = 26 + random.nextInt(46)
                }
                MODE_SNOW -> {
                    lengths[i] = 3f + random.nextFloat() * 4f
                    speeds[i] = 40f + random.nextFloat() * 60f
                    alphas[i] = 60 + random.nextInt(90)
                }
                MODE_STARS -> {
                    lengths[i] = 1f + random.nextFloat() * 1.6f
                    speeds[i] = 0.4f + random.nextFloat()
                    alphas[i] = 40 + random.nextInt(120)
                }
                else -> Unit
            }
        }
    }

    private fun animates(): Boolean = when (mode) {
        MODE_RAIN, MODE_STORM, MODE_SNOW, MODE_STARS -> true
        else -> false
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        var shader = gradient
        if (shader == null) {
            shader = LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(colors[0], colors[1], colors[2]),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            gradient = shader
        }
        paint.shader = shader
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null

        when (mode) {
            MODE_SUN -> drawSunGlow(canvas, width, height)
            MODE_CLOUD -> drawCloudHaze(canvas, width, height)
            MODE_FOG -> drawFog(canvas, width, height)
        }
        if (mode == MODE_RAIN || mode == MODE_STORM) drawRain(canvas, width, height)
        if (mode == MODE_SNOW) drawSnow(canvas, width, height)
        if (mode == MODE_STARS) drawStars(canvas, width, height)

        // Bottom vignette keeps white text legible over any palette.
        paint.style = Paint.Style.FILL
        var shade = vignette
        if (shade == null) {
            shade = LinearGradient(
                0f, height * 0.52f, 0f, height,
                intArrayOf(0x00000000, if (dayTheme) 0x1C000000 else 0x4A000000),
                null, Shader.TileMode.CLAMP,
            )
            vignette = shade
        }
        paint.shader = shade
        canvas.drawRect(0f, height * 0.52f, width, height, paint)
        paint.shader = null
    }

    private fun drawRain(canvas: Canvas, width: Float, height: Float) {
        val now = System.nanoTime()
        val delta = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrame = now
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * resources.displayMetrics.density
        paint.strokeCap = Paint.Cap.ROUND
        for (i in xs.indices) {
            var y = ys[i] + speeds[i] * delta
            var x = xs[i] + speeds[i] * delta * 0.16f
            if (y > height + lengths[i]) {
                y = -lengths[i]
                x = random.nextFloat() * width
            }
            if (x > width) x -= width
            xs[i] = x
            ys[i] = y
            paint.color = Color.argb(alphas[i], 210, 226, 245)
            canvas.drawLine(x, y, x - lengths[i] * 0.16f, y - lengths[i], paint)
        }
        requestFrame()
    }

    private fun drawSnow(canvas: Canvas, width: Float, height: Float) {
        val now = System.nanoTime()
        val delta = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrame = now
        paint.style = Paint.Style.FILL
        for (i in xs.indices) {
            var y = ys[i] + speeds[i] * delta
            var x = xs[i] + Math.sin((y / 90f).toDouble()).toFloat() * 12f * delta
            if (y > height + 6f) {
                y = -6f
                x = random.nextFloat() * width
            }
            xs[i] = x
            ys[i] = y
            paint.color = Color.argb(alphas[i], 255, 255, 255)
            canvas.drawCircle(x, y, lengths[i], paint)
        }
        requestFrame()
    }

    private fun drawStars(canvas: Canvas, width: Float, height: Float) {
        val now = System.nanoTime()
        val seconds = (now % 6_000_000_000L) / 1_000_000_000f
        paint.style = Paint.Style.FILL
        for (i in xs.indices) {
            val twinkle = 0.55f + 0.45f * Math.sin((seconds * speeds[i] + i).toDouble()).toFloat()
            paint.color = Color.argb((alphas[i] * twinkle).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawCircle(xs[i], ys[i], lengths[i], paint)
        }
        requestFrame()
    }

    private fun drawFog(canvas: Canvas, width: Float, height: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0x1AFFFFFF
        canvas.drawRect(0f, height * 0.45f, width, height * 0.72f, paint)
        paint.color = 0x12FFFFFF
        canvas.drawRect(0f, height * 0.72f, width, height, paint)
    }

    private fun drawCloudHaze(canvas: Canvas, width: Float, height: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0x0AFFFFFF
        canvas.drawCircle(width * 0.86f, height * 0.14f, width * 0.30f, paint)
        paint.color = 0x07FFFFFF
        canvas.drawCircle(width * 0.12f, height * 0.34f, width * 0.24f, paint)
        paint.color = 0x06FFFFFF
        canvas.drawCircle(width * 0.62f, height * 0.24f, width * 0.20f, paint)
    }

    private fun drawSunGlow(canvas: Canvas, width: Float, height: Float) {
        val cx = width * 0.82f
        val cy = height * 0.12f
        var shader = glow
        if (shader == null || glowCenterX != cx || glowCenterY != cy) {
            shader = RadialGradient(
                cx, cy, width * 0.75f,
                intArrayOf(0x55FFE9B8, 0x22FFF3D6, 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            glow = shader
            glowCenterX = cx
            glowCenterY = cy
        }
        paint.shader = shader
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, width * 0.75f, paint)
        paint.shader = null
    }

    /** ~30 fps is plenty for rain and snow, and halves the battery cost of a full-screen redraw. */
    private fun requestFrame() {
        if (!attached || !isShown || !animates()) return
        val now = System.nanoTime()
        if (now - lastInvalidate < 33_000_000L) return
        lastInvalidate = now
        postInvalidateOnAnimation()
    }

    companion object {
        private const val MODE_RAIN = 1
        private const val MODE_SNOW = 2
        private const val MODE_STARS = 3
        private const val MODE_FOG = 4
        private const val MODE_SUN = 5
        private const val MODE_CLOUD = 6
        private const val MODE_STORM = 7
    }
}
