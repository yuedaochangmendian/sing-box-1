package io.github.yuedaochangmendian.skybox;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

/** WMO weather names, original sky palettes and icon selection; no Activity implementation coupling. */
public final class WeatherTheme {
    public enum Type {
        CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDERSTORM, UNKNOWN
    }

    public final Type type;
    public final boolean isDay;
    public final int surfaceColor;
    public final int cardColor;
    public final int dockColor;
    public final int textColor;
    public final int secondaryTextColor;
    public final int iconColor;
    public final String weatherNameZh;
    public final String weatherNameEn;
    public final int iconType;
    private final int[] gradient;

    private WeatherTheme(Type type, boolean isDay, String zh, String en, int iconType,
                         int top, int middle, int bottom) {
        this.type = type;
        this.isDay = isDay;
        this.weatherNameZh = zh;
        this.weatherNameEn = en;
        this.iconType = iconType;
        gradient = new int[]{top, middle, bottom};
        surfaceColor = bottom;
        cardColor = mix(bottom, isDay ? Color.WHITE : 0xFF091322, isDay ? 0.70f : 0.25f);
        dockColor = mix(bottom, isDay ? Color.WHITE : 0xFF091322, isDay ? 0.88f : 0.45f);
        textColor = isDay ? 0xFF142B43 : 0xFFF4F8FF;
        secondaryTextColor = isDay ? 0xFF354C63 : 0xFFD0DBEB;
        iconColor = isDay ? 0xFF174268 : 0xFFE5F2FF;
    }

    /** Returns a defensive copy, suitable for GradientDrawable or LinearGradient. */
    public int[] getGradientColors() {
        return gradient.clone();
    }

    public GradientDrawable newBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, getGradientColors());
    }

    /** Unknown/no measurements uses a neutral palette rather than pretending it is sunny. */
    public static WeatherTheme from(WeatherState state) {
        boolean day = state == null || state.isDay;
        int code = state == null ? -1 : state.weatherCode;
        Type type;
        int icon;
        String zh;
        String en;
        switch (code) {
            case 0:
                type = Type.CLEAR;
                icon = day ? WeatherIconView.TYPE_SUN : WeatherIconView.TYPE_MOON;
                zh = day ? "晴" : "晴朗夜空";
                en = day ? "Clear sky" : "Clear night";
                break;
            case 1:
                type = Type.PARTLY_CLOUDY;
                icon = day ? WeatherIconView.TYPE_PARTLY_CLOUDY : WeatherIconView.TYPE_CLOUDY_NIGHT;
                zh = "晴间少云";
                en = "Mainly clear";
                break;
            case 2:
                type = Type.PARTLY_CLOUDY;
                icon = day ? WeatherIconView.TYPE_PARTLY_CLOUDY : WeatherIconView.TYPE_CLOUDY_NIGHT;
                zh = "多云";
                en = "Partly cloudy";
                break;
            case 3:
                type = Type.CLOUDY;
                icon = WeatherIconView.TYPE_CLOUD;
                zh = "阴";
                en = "Overcast";
                break;
            case 45: case 48:
                type = Type.FOG;
                icon = WeatherIconView.TYPE_FOG;
                zh = code == 48 ? "雾凇" : "雾";
                en = code == 48 ? "Depositing rime fog" : "Fog";
                break;
            case 51: case 53: case 55: case 56: case 57:
                type = Type.DRIZZLE;
                icon = WeatherIconView.TYPE_RAIN;
                if (code == 56 || code == 57) {
                    zh = code == 56 ? "轻冻毛毛雨" : "强冻毛毛雨";
                    en = code == 56 ? "Light freezing drizzle" : "Dense freezing drizzle";
                } else {
                    zh = code == 51 ? "轻毛毛雨" : code == 53 ? "中等毛毛雨" : "强毛毛雨";
                    en = code == 51 ? "Light drizzle" : code == 53 ? "Moderate drizzle" : "Dense drizzle";
                }
                break;
            case 61: case 63: case 65: case 66: case 67: case 80: case 81: case 82:
                type = Type.RAIN;
                icon = WeatherIconView.TYPE_RAIN;
                switch (code) {
                    case 61: zh = "小雨"; en = "Slight rain"; break;
                    case 63: zh = "中雨"; en = "Moderate rain"; break;
                    case 65: zh = "大雨"; en = "Heavy rain"; break;
                    case 66: zh = "轻冻雨"; en = "Light freezing rain"; break;
                    case 67: zh = "强冻雨"; en = "Heavy freezing rain"; break;
                    case 80: zh = "小阵雨"; en = "Slight rain showers"; break;
                    case 81: zh = "中等阵雨"; en = "Moderate rain showers"; break;
                    default: zh = "强阵雨"; en = "Violent rain showers"; break;
                }
                break;
            case 71: case 73: case 75: case 77: case 85: case 86:
                type = Type.SNOW;
                icon = WeatherIconView.TYPE_SNOW;
                switch (code) {
                    case 71: zh = "小雪"; en = "Slight snowfall"; break;
                    case 73: zh = "中雪"; en = "Moderate snowfall"; break;
                    case 75: zh = "大雪"; en = "Heavy snowfall"; break;
                    case 77: zh = "米雪"; en = "Snow grains"; break;
                    case 85: zh = "小阵雪"; en = "Slight snow showers"; break;
                    default: zh = "强阵雪"; en = "Heavy snow showers"; break;
                }
                break;
            case 95: case 96: case 99:
                type = Type.THUNDERSTORM;
                icon = WeatherIconView.TYPE_THUNDERSTORM;
                zh = code == 95 ? "雷暴" : code == 96 ? "雷暴伴小冰雹" : "雷暴伴大冰雹";
                en = code == 95 ? "Thunderstorm" : code == 96
                        ? "Thunderstorm with slight hail" : "Thunderstorm with heavy hail";
                break;
            default:
                type = Type.UNKNOWN;
                icon = WeatherIconView.TYPE_UNKNOWN;
                zh = "天气未知";
                en = "Weather unavailable";
                break;
        }
        int[] colors = palette(type, day);
        return new WeatherTheme(type, day, zh, en, icon, colors[0], colors[1], colors[2]);
    }

    private static int[] palette(Type type, boolean day) {
        if (!day) {
            switch (type) {
                case CLEAR: return new int[]{0xFF101C40, 0xFF263D66, 0xFF344C70};
                case PARTLY_CLOUDY: return new int[]{0xFF18243C, 0xFF2A3E59, 0xFF3C526E};
                case CLOUDY: return new int[]{0xFF222C3D, 0xFF354257, 0xFF46576C};
                case FOG: return new int[]{0xFF26343D, 0xFF394C57, 0xFF4D626C};
                case DRIZZLE: case RAIN:
                    return new int[]{0xFF112B3C, 0xFF254657, 0xFF3A5C6C};
                case SNOW: return new int[]{0xFF243148, 0xFF384D65, 0xFF4B6278};
                case THUNDERSTORM: return new int[]{0xFF201B37, 0xFF37304C, 0xFF4B4360};
                default: return new int[]{0xFF1C293A, 0xFF2F4055, 0xFF42566A};
            }
        }
        switch (type) {
            case CLEAR: return new int[]{0xFF82C4F4, 0xFFB9DEFA, 0xFFF1E8D7};
            case PARTLY_CLOUDY: return new int[]{0xFF99C9E8, 0xFFC9E2EF, 0xFFEDF0EA};
            case CLOUDY: return new int[]{0xFFB3C4D4, 0xFFD0DCE5, 0xFFEBEFF3};
            case FOG: return new int[]{0xFFBACCCF, 0xFFD6E1DF, 0xFFF0F2EC};
            case DRIZZLE: case RAIN:
                return new int[]{0xFF8EB6D0, 0xFFBCD4E3, 0xFFE1EBF1};
            case SNOW: return new int[]{0xFFB5CFED, 0xFFD8E7F6, 0xFFF5F8FC};
            case THUNDERSTORM: return new int[]{0xFFADA7C8, 0xFFC8C4DD, 0xFFE6E4F0};
            default: return new int[]{0xFFB3C8DD, 0xFFD3E0EB, 0xFFEEF3F7};
        }
    }

    private static int mix(int from, int to, float amount) {
        return Color.rgb(Math.round(Color.red(from) * (1 - amount) + Color.red(to) * amount),
                Math.round(Color.green(from) * (1 - amount) + Color.green(to) * amount),
                Math.round(Color.blue(from) * (1 - amount) + Color.blue(to) * amount));
    }

    /**
     * UI-thread only. Applies a fresh root gradient and system-bar appearance, preserving layout
     * flags. Returns the palette so callers can explicitly tint their own cards/text/dock.
     * Does not inspect private fields or recursively overwrite child view backgrounds.
     */
    @SuppressWarnings("deprecation")
    public static WeatherTheme apply(Activity activity, View root, WeatherState state) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("WeatherTheme.apply must run on the main thread");
        }
        if (activity == null || root == null) throw new IllegalArgumentException("activity/root");
        WeatherTheme theme = from(state);
        root.setBackground(theme.newBackground());
        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(theme.dockColor);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(theme.isDay ? mask : 0, mask);
            }
        } else {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags = theme.isDay ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags = theme.isDay ? flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        : flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else if (theme.isDay) {
                // Android 7 cannot draw dark navigation icons: keep their background dark.
                window.setNavigationBarColor(0xFF23394F);
            }
            decor.setSystemUiVisibility(flags);
        }
        return theme;
    }
}
