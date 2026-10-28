package io.github.yuedaochangmendian.skybox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Decorative sky and connection orbit. Does not represent measurements or traffic. */
public final class WeatherView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean connected;

    public WeatherView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setConnected(boolean value) {
        if (connected != value) {
            connected = value;
            invalidate();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;
        canvas.save();
        canvas.scale(width / 360f, height / 228f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 330, 228,
                new int[]{Color.rgb(99, 180, 255), Color.rgb(42, 116, 244), Color.rgb(52, 91, 224)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 360, 228, paint);
        paint.setShader(null);
        paint.setColor(0x12FFFFFF);
        canvas.drawCircle(318, 10, 136, paint);
        paint.setColor(0x0CFFFFFF);
        canvas.drawCircle(318, 10, 171, paint);
        paint.setColor(0x24FFFFFF);
        canvas.drawCircle(273, 71, 29, paint);
        paint.setColor(0xB8FFFFFF);
        canvas.drawCircle(273, 71, 20, paint);
        cloud(canvas, 258, 106, 1.1f, 0xE6FFFFFF);
        cloud(canvas, 194, 136, 0.68f, 0x75FFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(0x33FFFFFF);
        canvas.drawArc(new RectF(-70, 116, 440, 326), 194, 151, false, paint);
        paint.setColor(0x24FFFFFF);
        canvas.drawArc(new RectF(-80, 136, 440, 356), 194, 151, false, paint);
        paint.setStyle(Paint.Style.FILL);
        float x = connected ? 211 : 140;
        float y = connected ? 124 : 133;
        paint.setColor(0x32FFFFFF);
        canvas.drawCircle(x, y, 10, paint);
        paint.setColor(0xEDFFFFFF);
        canvas.drawCircle(x, y, 3.5f, paint);
        Path hill = new Path();
        hill.moveTo(0, 216);
        hill.cubicTo(80, 183, 128, 236, 226, 208);
        hill.cubicTo(285, 190, 320, 207, 360, 185);
        hill.lineTo(360, 228);
        hill.lineTo(0, 228);
        hill.close();
        paint.setColor(0x0DFFFFFF);
        canvas.drawPath(hill, paint);
        canvas.restore();
    }

    private void cloud(Canvas canvas, float x, float y, float scale, int color) {
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        Path cloud = new Path();
        cloud.moveTo(-36, 12);
        cloud.cubicTo(-49, 12, -49, -9, -34, -10);
        cloud.cubicTo(-33, -35, 3, -39, 11, -14);
        cloud.cubicTo(34, -22, 45, 12, 23, 12);
        cloud.close();
        canvas.drawPath(cloud, paint);
        canvas.restore();
    }
}
