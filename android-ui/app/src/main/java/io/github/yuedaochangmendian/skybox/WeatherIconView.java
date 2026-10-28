package io.github.yuedaochangmendian.skybox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

/** Original vector weather illustrations; no assets, icon fonts, bitmaps or third-party code. */
public final class WeatherIconView extends View {
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_SUN = 1;
    public static final int TYPE_MOON = 2;
    public static final int TYPE_PARTLY_CLOUDY = 3;
    public static final int TYPE_CLOUDY_NIGHT = 4;
    public static final int TYPE_CLOUD = 5;
    public static final int TYPE_RAIN = 6;
    public static final int TYPE_SNOW = 7;
    public static final int TYPE_FOG = 8;
    public static final int TYPE_THUNDERSTORM = 9;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int type = TYPE_UNKNOWN;
    private int tint = 0xFF174268;

    public WeatherIconView(Context context) {
        this(context, null);
    }

    public WeatherIconView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WeatherIconView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        updateDescription(null, null);
    }

    public void setType(int value) {
        int next = value >= TYPE_UNKNOWN && value <= TYPE_THUNDERSTORM ? value : TYPE_UNKNOWN;
        if (type != next) {
            type = next;
            updateDescription(null, null);
            invalidate();
        }
    }

    public int getType() {
        return type;
    }

    public void setTint(int color) {
        if (tint != color) {
            tint = color;
            invalidate();
        }
    }

    public int getTint() {
        return tint;
    }

    /** Also sets the localized accessibility description, including data freshness/error state. */
    public void setWeather(WeatherState state) {
        WeatherTheme theme = WeatherTheme.from(state);
        type = theme.iconType;
        tint = theme.iconColor;
        updateDescription(theme, state);
        invalidate();
    }

    public void setWeather(WeatherTheme theme) {
        if (theme == null) theme = WeatherTheme.from(null);
        type = theme.iconType;
        tint = theme.iconColor;
        updateDescription(theme, null);
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.round(72 * getResources().getDisplayMetrics().density);
        int width = Math.max(getSuggestedMinimumWidth(), size + getPaddingLeft() + getPaddingRight());
        int height = Math.max(getSuggestedMinimumHeight(), size + getPaddingTop() + getPaddingBottom());
        setMeasuredDimension(resolveSizeAndState(width, widthMeasureSpec, 0),
                resolveSizeAndState(height, heightMeasureSpec, 0));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) return;
        float size = Math.min(width, height);
        int checkpoint = canvas.save();
        canvas.translate(getPaddingLeft() + (width - size) / 2f,
                getPaddingTop() + (height - size) / 2f);
        canvas.scale(size / 100f, size / 100f);
        switch (type) {
            case TYPE_SUN:
                sun(canvas, 50, 50, 19);
                break;
            case TYPE_MOON:
                moon(canvas, 50, 49, 30);
                star(canvas, 76, 25, 4);
                star(canvas, 79, 56, 2.5f);
                break;
            case TYPE_PARTLY_CLOUDY:
                sun(canvas, 66, 32, 12);
                cloud(canvas, 0, 7, 0.95f);
                break;
            case TYPE_CLOUDY_NIGHT:
                moon(canvas, 67, 29, 18);
                cloud(canvas, 0, 9, 0.95f);
                break;
            case TYPE_CLOUD:
                cloud(canvas, 0, 9, 1);
                break;
            case TYPE_RAIN:
                cloud(canvas, 0, -5, 1);
                stroke(4.5f, 1);
                canvas.drawLine(34, 67, 28, 80, paint);
                canvas.drawLine(51, 71, 45, 84, paint);
                canvas.drawLine(68, 67, 62, 80, paint);
                break;
            case TYPE_SNOW:
                cloud(canvas, 0, -9, 1);
                snowflake(canvas, 30, 76, 7);
                snowflake(canvas, 51, 84, 6);
                snowflake(canvas, 73, 75, 7);
                break;
            case TYPE_FOG:
                cloud(canvas, 0, -10, 1);
                stroke(4, 0.75f);
                canvas.drawLine(19, 68, 67, 68, paint);
                canvas.drawLine(76, 68, 83, 68, paint);
                canvas.drawLine(28, 78, 80, 78, paint);
                canvas.drawLine(18, 87, 61, 87, paint);
                break;
            case TYPE_THUNDERSTORM:
                cloud(canvas, 0, -8, 1);
                fill(1);
                path.reset();
                path.moveTo(51, 59);
                path.lineTo(39, 77);
                path.lineTo(50, 76);
                path.lineTo(45, 94);
                path.lineTo(66, 68);
                path.lineTo(54, 69);
                path.lineTo(62, 59);
                path.close();
                canvas.drawPath(path, paint);
                stroke(3, 0.75f);
                canvas.drawLine(28, 68, 24, 78, paint);
                canvas.drawLine(77, 68, 73, 78, paint);
                break;
            default:
                stroke(4, 0.65f);
                canvas.drawCircle(50, 50, 29, paint);
                canvas.drawLine(38, 50, 62, 50, paint);
                break;
        }
        canvas.restoreToCount(checkpoint);
    }

    private void sun(Canvas canvas, float x, float y, float radius) {
        fill(0.17f);
        canvas.drawCircle(x, y, radius + 7, paint);
        fill(1);
        canvas.drawCircle(x, y, radius, paint);
        stroke(3.5f, 1);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * i / 4;
            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);
            canvas.drawLine(x + dx * (radius + 10), y + dy * (radius + 10),
                    x + dx * (radius + 16), y + dy * (radius + 16), paint);
        }
    }

    private void moon(Canvas canvas, float x, float y, float radius) {
        // A crescent made of Bezier curves, not background-colored overpainting.
        fill(1);
        path.reset();
        path.moveTo(x + radius * 0.35f, y - radius);
        path.cubicTo(x - radius * 1.18f, y - radius * 0.93f,
                x - radius * 1.2f, y + radius * 0.72f, x - radius * 0.03f, y + radius);
        path.cubicTo(x + radius * 0.63f, y + radius * 1.09f,
                x + radius * 0.94f, y + radius * 0.62f, x + radius, y + radius * 0.35f);
        path.cubicTo(x + radius * 0.06f, y + radius * 0.72f,
                x - radius * 0.56f, y - radius * 0.25f, x + radius * 0.35f, y - radius);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void cloud(Canvas canvas, float dx, float dy, float scale) {
        int checkpoint = canvas.save();
        canvas.translate(dx + 50 * (1 - scale), dy + 50 * (1 - scale));
        canvas.scale(scale, scale);
        path.reset();
        path.moveTo(27, 62);
        path.cubicTo(8, 62, 9, 39, 27, 37);
        path.cubicTo(31, 17, 59, 16, 67, 35);
        path.cubicTo(86, 30, 97, 57, 77, 62);
        path.close();
        fill(0.16f);
        canvas.drawPath(path, paint);
        stroke(4, 1);
        canvas.drawPath(path, paint);
        canvas.restoreToCount(checkpoint);
    }

    private void snowflake(Canvas canvas, float x, float y, float radius) {
        stroke(2.5f, 1);
        for (int i = 0; i < 3; i++) {
            double angle = Math.PI * i / 3;
            float dx = (float) Math.cos(angle) * radius;
            float dy = (float) Math.sin(angle) * radius;
            canvas.drawLine(x - dx, y - dy, x + dx, y + dy, paint);
        }
    }

    private void star(Canvas canvas, float x, float y, float radius) {
        stroke(2, 0.75f);
        canvas.drawLine(x - radius, y, x + radius, y, paint);
        canvas.drawLine(x, y - radius, x, y + radius, paint);
    }

    private void fill(float opacity) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(tint);
        paint.setAlpha(Math.round(Color.alpha(tint) * opacity));
    }

    private void stroke(float width, float opacity) {
        fill(opacity);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
    }

    private boolean chinese() {
        return "zh".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private void updateDescription(WeatherTheme theme, WeatherState state) {
        boolean zh = chinese();
        String description = theme == null ? typeDescription(zh)
                : zh ? theme.weatherNameZh : theme.weatherNameEn;
        if (state != null) {
            if (!state.city.isEmpty()) description = state.city + "，" + description;
            if (state.hasData()) {
                Locale locale = getResources().getConfiguration().getLocales().get(0);
                description += String.format(locale, zh ? "，%.1f 摄氏度" : ", %.1f degrees Celsius",
                        state.temperature);
            }
            if (state.loading) description += zh ? "，正在更新" : ", updating";
            else if (state.error) description += zh ? "，更新失败" : ", update failed";
            if (state.stale && state.hasData()) description += zh ? "，上次天气数据" : ", last known weather";
        }
        setContentDescription(description);
    }

    private String typeDescription(boolean zh) {
        switch (type) {
            case TYPE_SUN: return zh ? "晴" : "Sunny";
            case TYPE_MOON: return zh ? "晴朗夜空" : "Clear night";
            case TYPE_PARTLY_CLOUDY: return zh ? "多云" : "Partly cloudy";
            case TYPE_CLOUDY_NIGHT: return zh ? "多云夜空" : "Partly cloudy night";
            case TYPE_CLOUD: return zh ? "阴" : "Overcast";
            case TYPE_RAIN: return zh ? "雨" : "Rain";
            case TYPE_SNOW: return zh ? "雪" : "Snow";
            case TYPE_FOG: return zh ? "雾" : "Fog";
            case TYPE_THUNDERSTORM: return zh ? "雷暴" : "Thunderstorm";
            default: return zh ? "天气未知" : "Weather unavailable";
        }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.ImageView");
        info.setContentDescription(getContentDescription());
    }
}
