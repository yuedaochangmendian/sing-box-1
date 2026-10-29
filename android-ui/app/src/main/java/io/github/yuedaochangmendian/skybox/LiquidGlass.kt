// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.yuedaochangmendian.skybox

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.view.View
import android.view.ViewOutlineProvider

/**
 * iOS 26 "liquid glass" surfaces, drawn as real lighting layers instead of a flat translucent fill.
 *
 * A liquid-glass surface is built from four cues; dropping any one of them makes it read as a
 * plain frosted rectangle:
 *  1. body    — a vertical tint gradient that is lighter at the top (light enters from above);
 *  2. gloss    — a soft bright band that hugs the top edge;
 *  3. specular — an off-centre highlight where the light source "hits" the panel;
 *  4. rim      — a 1 dp edge light that fades out towards the bottom, plus a faint bottom bounce
 *                that stands in for the light refracting through the glass thickness.
 *
 * The three styles mirror the reference design ("一共三种"): [Style.REGULAR] for cards and panels,
 * [Style.CLEAR] for controls that must stay see-through, [Style.TINTED] for the primary action.
 */
object Glass {

    /**
     * One radius scale for every glass surface. Anything that is a glass panel uses [RADIUS_CARD_DP];
     * controls (pills, buttons, close button) use [RADIUS_CONTROL_DP], which turns a 40 dp pill into
     * an exact capsule; the dock keeps its own larger radius.
     */
    const val RADIUS_CARD_DP = 22
    const val RADIUS_CONTROL_DP = 20
    const val RADIUS_DOCK_DP = 40

    enum class Style { REGULAR, CLEAR, TINTED }

    /** Primary-action tint; a slightly deeper blue in light mode so white text stays legible. */
    fun accent(dark: Boolean): Int =
        if (dark) 0xFF3D7BFF.toInt() else 0xFF2C6BEA.toInt()

    /** Thickness of the edge light in dp; below ~1 dp the rim disappears on xxhdpi screens. */
    const val RIM_DP = 1.2f
}

/**
 * Drawable implementation of one glass surface. The drawable is stateful so a pressed surface
 * brightens the way the reference interaction does; the ripple stays in the parent [RippleDrawable].
 */
class LiquidGlassDrawable(
    private val style: Glass.Style,
    private val radiusPx: Float,
    private val surface: Int,
    private val surfaceAlpha: Float,
    private val dark: Boolean,
    private val rimWidthPx: Float,
) : Drawable() {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bouncePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimWidthPx
    }
    private val shape = Path()
    private val area = RectF()

    private var pressed = false

    /**
     * Body opacity per style. CLEAR keeps roughly half the tint of a regular panel so the sky keeps
     * showing through, TINTED keeps slightly more because the accent must read as "primary".
     */
    private val bodyAlphaScale: Float
        get() = when (style) {
            Glass.Style.REGULAR -> 1f
            Glass.Style.CLEAR -> 0.56f
            Glass.Style.TINTED -> 0.94f
        }

    private val glossAlpha: Int
        get() = when (style) {
            Glass.Style.REGULAR -> 0x4A
            Glass.Style.CLEAR -> 0x5C
            Glass.Style.TINTED -> 0x4A
        }

    private val specularAlpha: Int
        get() = when (style) {
            Glass.Style.REGULAR -> 0x66
            Glass.Style.CLEAR -> 0x7A
            Glass.Style.TINTED -> 0x5C
        }

    private val rimTopAlpha: Int
        get() = when (style) {
            Glass.Style.REGULAR -> 0xB8
            Glass.Style.CLEAR -> 0xD8
            Glass.Style.TINTED -> 0xC8
        }

    private val rimBottomAlpha: Int
        get() = when (style) {
            Glass.Style.REGULAR -> 0x1E
            Glass.Style.CLEAR -> 0x30
            Glass.Style.TINTED -> 0x26
        }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val nowPressed = state.contains(android.R.attr.state_pressed)
        if (nowPressed == pressed) return false
        pressed = nowPressed
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(bounds: Rect) {
        val inset = rimWidthPx / 2f
        area.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset,
        )
        if (area.width() <= 0f || area.height() <= 0f) return
        val radius = Math.min(radiusPx, Math.min(area.width(), area.height()) / 2f)
        shape.reset()
        shape.addRoundRect(area, radius, radius, Path.Direction.CW)
        buildShaders()
    }

    private fun buildShaders() {
        val top = area.top
        val bottom = area.bottom
        val height = area.height()
        val width = area.width()

        val alpha = (surfaceAlpha * bodyAlphaScale * 255f).toInt().coerceIn(0, 255)
        val bodyTop = lerp(surface, Color.WHITE, if (dark) 0.10f else 0.18f)
        val bodyBottom = lerp(surface, Color.BLACK, if (dark) 0.14f else 0.06f)
        bodyPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                withAlpha(bodyTop, alpha),
                withAlpha(surface, (alpha * 0.94f).toInt()),
                withAlpha(bodyBottom, alpha),
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )

        // Gloss: bright at the very top edge, gone by ~55% of the height.
        glossPaint.shader = LinearGradient(
            0f, top, 0f, top + height * 0.55f,
            intArrayOf(withAlpha(Color.WHITE, glossAlpha), withAlpha(Color.WHITE, 0)),
            null,
            Shader.TileMode.CLAMP,
        )

        // Specular: light source sits above and slightly left, so the hotspot is off-centre.
        specularPaint.shader = RadialGradient(
            area.left + width * 0.30f,
            top + height * 0.02f,
            Math.max(width * 0.62f, 1f),
            intArrayOf(withAlpha(Color.WHITE, specularAlpha), withAlpha(Color.WHITE, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )

        // Bounce: light that travelled through the glass and reflects off the bottom edge.
        bouncePaint.shader = LinearGradient(
            0f, bottom - height * 0.42f, 0f, bottom,
            intArrayOf(withAlpha(Color.WHITE, 0), withAlpha(Color.WHITE, if (dark) 0x1C else 0x24)),
            null,
            Shader.TileMode.CLAMP,
        )

        rimPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(withAlpha(Color.WHITE, rimTopAlpha), withAlpha(Color.WHITE, rimBottomAlpha)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (area.width() <= 0f || area.height() <= 0f) return
        canvas.save()
        canvas.clipPath(shape)
        canvas.drawRect(area, bodyPaint)
        canvas.drawRect(area, glossPaint)
        canvas.drawRect(area, specularPaint)
        canvas.drawRect(area, bouncePaint)
        if (pressed) {
            // Pressed glass lights up rather than turning grey, matching the reference interaction.
            pressPaint.color = withAlpha(Color.WHITE, if (dark) 0x1A else 0x22)
            canvas.drawRect(area, pressPaint)
        }
        canvas.restore()
        canvas.drawPath(shape, rimPaint)
    }

    override fun setAlpha(alpha: Int) {
        bodyPaint.alpha = alpha
        glossPaint.alpha = alpha
        specularPaint.alpha = alpha
        bouncePaint.alpha = alpha
        rimPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bodyPaint.colorFilter = colorFilter
        glossPaint.colorFilter = colorFilter
        specularPaint.colorFilter = colorFilter
        bouncePaint.colorFilter = colorFilter
        rimPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

        fun lerp(from: Int, to: Int, amount: Float): Int = Color.rgb(
            Math.round(Color.red(from) * (1 - amount) + Color.red(to) * amount),
            Math.round(Color.green(from) * (1 - amount) + Color.green(to) * amount),
            Math.round(Color.blue(from) * (1 - amount) + Color.blue(to) * amount),
        )
    }
}

/**
 * Installs a liquid-glass background with a ripple confined to the glass shape.
 * Set [elevationDp] above zero to get a real drop shadow (the outline is supplied too, otherwise
 * translucent surfaces never cast one).
 */
fun View.applyLiquidGlass(
    style: Glass.Style,
    radiusDp: Int,
    surface: Int,
    surfaceAlpha: Float,
    dark: Boolean,
    elevationDp: Int = 0,
    rippleColor: Int = 0x33FFFFFF,
): LiquidGlassDrawable {
    val density = resources.displayMetrics.density
    val radiusPx = radiusDp * density
    val glass = LiquidGlassDrawable(
        style = style,
        radiusPx = radiusPx,
        surface = surface,
        surfaceAlpha = surfaceAlpha,
        dark = dark,
        rimWidthPx = Glass.RIM_DP * density,
    )
    val mask = ShapeDrawable(RoundRectShape(floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx), null, null))
    mask.paint.color = Color.WHITE
    background = RippleDrawable(ColorStateList.valueOf(rippleColor), glass, mask)
    applyGlassOutline(radiusPx, elevationDp)
    return glass
}

/** Same material without press feedback, for content panels (cards) that hold their own widgets. */
fun View.applyLiquidGlassSurface(
    style: Glass.Style,
    radiusDp: Int,
    surface: Int,
    surfaceAlpha: Float,
    dark: Boolean,
    elevationDp: Int = 0,
): LiquidGlassDrawable {
    val density = resources.displayMetrics.density
    val radiusPx = radiusDp * density
    val glass = LiquidGlassDrawable(
        style = style,
        radiusPx = radiusPx,
        surface = surface,
        surfaceAlpha = surfaceAlpha,
        dark = dark,
        rimWidthPx = Glass.RIM_DP * density,
    )
    background = glass
    applyGlassOutline(radiusPx, elevationDp)
    return glass
}

private fun View.applyGlassOutline(radiusPx: Float, elevationDp: Int) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            outline.alpha = 1f
        }
    }
    clipToOutline = true
    if (elevationDp > 0) elevation = elevationDp * resources.displayMetrics.density
}
