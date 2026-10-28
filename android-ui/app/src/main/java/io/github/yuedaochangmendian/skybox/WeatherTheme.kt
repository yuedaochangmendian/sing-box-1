package io.github.yuedaochangmendian.skybox

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.WindowInsetsController

/** WMO weather names, original sky palettes and icon selection. */
class WeatherTheme private constructor(
    val type: Type,
    val isDay: Boolean,
    val weatherNameZh: String,
    val weatherNameEn: String,
    val iconType: Int,
    top: Int,
    middle: Int,
    bottom: Int,
) {
    enum class Type {
        CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDERSTORM, UNKNOWN
    }

    private val gradient = intArrayOf(top, middle, bottom)
    val surfaceColor: Int = bottom
    val cardColor: Int = mix(bottom, if (isDay) Color.WHITE else 0xFF091322.toInt(), if (isDay) 0.70f else 0.25f)
    val dockColor: Int = mix(bottom, if (isDay) Color.WHITE else 0xFF091322.toInt(), if (isDay) 0.88f else 0.45f)
    val textColor: Int = if (isDay) 0xFF142B43.toInt() else 0xFFF4F8FF.toInt()
    val secondaryTextColor: Int = if (isDay) 0xFF354C63.toInt() else 0xFFD0DBEB.toInt()
    val iconColor: Int = if (isDay) 0xFF174268.toInt() else 0xFFE5F2FF.toInt()

    /** Returns a defensive copy, suitable for GradientDrawable or LinearGradient. */
    fun getGradientColors(): IntArray = gradient.copyOf()

    fun newBackground(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, getGradientColors())

    companion object {
        /** Unknown/no measurements uses a neutral palette rather than pretending it is sunny. */
        fun from(state: WeatherState?): WeatherTheme {
            val day = state?.isDay ?: true
            val code = state?.weatherCode ?: -1
            val type: Type
            val icon: Int
            val zh: String
            val en: String
            when (code) {
                0 -> {
                    type = Type.CLEAR
                    icon = if (day) WeatherIconView.TYPE_SUN else WeatherIconView.TYPE_MOON
                    zh = if (day) "晴" else "晴朗夜空"
                    en = if (day) "Clear sky" else "Clear night"
                }
                1 -> {
                    type = Type.PARTLY_CLOUDY
                    icon = if (day) WeatherIconView.TYPE_PARTLY_CLOUDY else WeatherIconView.TYPE_CLOUDY_NIGHT
                    zh = "晴间少云"
                    en = "Mainly clear"
                }
                2 -> {
                    type = Type.PARTLY_CLOUDY
                    icon = if (day) WeatherIconView.TYPE_PARTLY_CLOUDY else WeatherIconView.TYPE_CLOUDY_NIGHT
                    zh = "多云"
                    en = "Partly cloudy"
                }
                3 -> {
                    type = Type.CLOUDY
                    icon = WeatherIconView.TYPE_CLOUD
                    zh = "阴"
                    en = "Overcast"
                }
                45, 48 -> {
                    type = Type.FOG
                    icon = WeatherIconView.TYPE_FOG
                    zh = if (code == 48) "雾凇" else "雾"
                    en = if (code == 48) "Depositing rime fog" else "Fog"
                }
                51, 53, 55, 56, 57 -> {
                    type = Type.DRIZZLE
                    icon = WeatherIconView.TYPE_RAIN
                    if (code == 56 || code == 57) {
                        zh = if (code == 56) "轻冻毛毛雨" else "强冻毛毛雨"
                        en = if (code == 56) "Light freezing drizzle" else "Dense freezing drizzle"
                    } else {
                        zh = when (code) {
                            51 -> "轻毛毛雨"
                            53 -> "中等毛毛雨"
                            else -> "强毛毛雨"
                        }
                        en = when (code) {
                            51 -> "Light drizzle"
                            53 -> "Moderate drizzle"
                            else -> "Dense drizzle"
                        }
                    }
                }
                61, 63, 65, 66, 67, 80, 81, 82 -> {
                    type = Type.RAIN
                    icon = WeatherIconView.TYPE_RAIN
                    when (code) {
                        61 -> { zh = "小雨"; en = "Slight rain" }
                        63 -> { zh = "中雨"; en = "Moderate rain" }
                        65 -> { zh = "大雨"; en = "Heavy rain" }
                        66 -> { zh = "轻冻雨"; en = "Light freezing rain" }
                        67 -> { zh = "强冻雨"; en = "Heavy freezing rain" }
                        80 -> { zh = "小阵雨"; en = "Slight rain showers" }
                        81 -> { zh = "中等阵雨"; en = "Moderate rain showers" }
                        else -> { zh = "强阵雨"; en = "Violent rain showers" }
                    }
                }
                71, 73, 75, 77, 85, 86 -> {
                    type = Type.SNOW
                    icon = WeatherIconView.TYPE_SNOW
                    when (code) {
                        71 -> { zh = "小雪"; en = "Slight snowfall" }
                        73 -> { zh = "中雪"; en = "Moderate snowfall" }
                        75 -> { zh = "大雪"; en = "Heavy snowfall" }
                        77 -> { zh = "米雪"; en = "Snow grains" }
                        85 -> { zh = "小阵雪"; en = "Slight snow showers" }
                        else -> { zh = "强阵雪"; en = "Heavy snow showers" }
                    }
                }
                95, 96, 99 -> {
                    type = Type.THUNDERSTORM
                    icon = WeatherIconView.TYPE_THUNDERSTORM
                    zh = when (code) {
                        95 -> "雷暴"
                        96 -> "雷暴伴小冰雹"
                        else -> "雷暴伴大冰雹"
                    }
                    en = when (code) {
                        95 -> "Thunderstorm"
                        96 -> "Thunderstorm with slight hail"
                        else -> "Thunderstorm with heavy hail"
                    }
                }
                else -> {
                    type = Type.UNKNOWN
                    icon = WeatherIconView.TYPE_UNKNOWN
                    zh = "天气未知"
                    en = "Weather unavailable"
                }
            }
            val colors = palette(type, day)
            return WeatherTheme(type, day, zh, en, icon, colors[0], colors[1], colors[2])
        }

        private fun palette(type: Type, day: Boolean): IntArray {
            if (!day) {
                return when (type) {
                    Type.CLEAR -> intArrayOf(0xFF0A1330.toInt(), 0xFF1A2A4E.toInt(), 0xFF2A3C5E.toInt())
                    Type.PARTLY_CLOUDY -> intArrayOf(0xFF0F1A30.toInt(), 0xFF1F2F4A.toInt(), 0xFF31435D.toInt())
                    Type.CLOUDY -> intArrayOf(0xFF181F2D.toInt(), 0xFF283140.toInt(), 0xFF38434F.toInt())
                    Type.FOG -> intArrayOf(0xFF1D2830.toInt(), 0xFF2D3C46.toInt(), 0xFF3F4F59.toInt())
                    Type.DRIZZLE, Type.RAIN ->
                        intArrayOf(0xFF0E1826.toInt(), 0xFF1B2A3E.toInt(), 0xFF2A3B50.toInt())
                    Type.SNOW -> intArrayOf(0xFF19222F.toInt(), 0xFF2A3749.toInt(), 0xFF3C4C60.toInt())
                    Type.THUNDERSTORM -> intArrayOf(0xFF141127.toInt(), 0xFF241F35.toInt(), 0xFF35304A.toInt())
                    else -> intArrayOf(0xFF151E2B.toInt(), 0xFF24313F.toInt(), 0xFF34424F.toInt())
                }
            }
            return when (type) {
                Type.CLEAR -> intArrayOf(0xFF82C4F4.toInt(), 0xFFB9DEFA.toInt(), 0xFFF1E8D7.toInt())
                Type.PARTLY_CLOUDY -> intArrayOf(0xFF99C9E8.toInt(), 0xFFC9E2EF.toInt(), 0xFFEDF0EA.toInt())
                Type.CLOUDY -> intArrayOf(0xFFB3C4D4.toInt(), 0xFFD0DCE5.toInt(), 0xFFEBEFF3.toInt())
                Type.FOG -> intArrayOf(0xFFBACCCF.toInt(), 0xFFD6E1DF.toInt(), 0xFFF0F2EC.toInt())
                Type.DRIZZLE, Type.RAIN ->
                    intArrayOf(0xFF8EB6D0.toInt(), 0xFFBCD4E3.toInt(), 0xFFE1EBF1.toInt())
                Type.SNOW -> intArrayOf(0xFFB5CFED.toInt(), 0xFFD8E7F6.toInt(), 0xFFF5F8FC.toInt())
                Type.THUNDERSTORM -> intArrayOf(0xFFADA7C8.toInt(), 0xFFC8C4DD.toInt(), 0xFFE6E4F0.toInt())
                else -> intArrayOf(0xFFB3C8DD.toInt(), 0xFFD3E0EB.toInt(), 0xFFEEF3F7.toInt())
            }
        }

        private fun mix(from: Int, to: Int, amount: Float): Int = Color.rgb(
            Math.round(Color.red(from) * (1 - amount) + Color.red(to) * amount),
            Math.round(Color.green(from) * (1 - amount) + Color.green(to) * amount),
            Math.round(Color.blue(from) * (1 - amount) + Color.blue(to) * amount),
        )

        /**
         * UI-thread only. Applies a fresh root gradient and system-bar appearance, preserving layout
         * flags. Returns the palette so callers can explicitly tint their own cards/text/dock.
         */
        @Suppress("DEPRECATION")
        fun apply(activity: Activity, root: View, state: WeatherState?): WeatherTheme {
            check(Looper.myLooper() == Looper.getMainLooper()) { "WeatherTheme.apply must run on the main thread" }
            val theme = from(state)
            root.background = theme.newBackground()
            val window = activity.window
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = theme.dockColor
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val controller: WindowInsetsController? = window.insetsController
                if (controller != null) {
                    val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    controller.setSystemBarsAppearance(if (theme.isDay) mask else 0, mask)
                }
            } else {
                val decor = window.decorView
                var flags = decor.systemUiVisibility
                flags = if (theme.isDay) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                if (Build.VERSION.SDK_INT >= 26) {
                    flags = if (theme.isDay) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else if (theme.isDay) {
                    // Android 7 cannot draw dark navigation icons: keep their background dark.
                    window.navigationBarColor = 0xFF23394F.toInt()
                }
                decor.systemUiVisibility = flags
            }
            return theme
        }
    }
}
