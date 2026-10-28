package io.github.yuedaochangmendian.skybox

/**
 * One day of the forecast. label is already localized (今天/明天/周五), max/min are Celsius.
 * weatherCode is the WMO code for the day; -1 means the provider returned no value.
 */
class DailyForecast(
    val label: String,
    val weatherCode: Int,
    val max: Double,
    val min: Double,
) {
    fun isValid(): Boolean = max.isFinite() && min.isFinite() && max >= min
}

/** One hour of the nowcast-style detail view. label is localized ("14时"), temperature Celsius. */
class HourForecast(
    val label: String,
    val weatherCode: Int,
    val temperature: Double,
) {
    fun isValid(): Boolean = temperature.isFinite()
}

/**
 * Immutable weather snapshot. Temperatures are Celsius, precipitation is mm and wind is km/h.
 * Missing measurements are Double.NaN; updatedAt is the last successful fetch time (epoch ms),
 * or zero before the first success. An error never discards the last successful measurements.
 *
 * [precipitation15] holds the provider's 15-minute precipitation nowcast (8 entries = 2 hours),
 * [daily] the next few days, and [aqi]/[aqiLabel] the US AQI value with its Chinese band name.
 */
class WeatherState(
    val city: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val temperature: Double,
    val apparentTemperature: Double,
    val precipitation: Double,
    val windSpeed: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val updatedAt: Long,
    val stale: Boolean,
    val error: Boolean,
    val loading: Boolean,
    val errorMessage: String = "",
    val daily: List<DailyForecast> = emptyList(),
    val precipitation15: List<Double> = emptyList(),
    val aqi: Double = Double.NaN,
    val aqiLabel: String = "",
    val hourly: List<HourForecast> = emptyList(),
) {
    fun hasData(): Boolean = updatedAt > 0L && !temperature.isNaN() && !temperature.isInfinite()

    fun todayMax(): Double = daily.firstOrNull()?.max ?: Double.NaN

    fun todayMin(): Double = daily.firstOrNull()?.min ?: Double.NaN

    fun withStatus(stale: Boolean, error: Boolean, loading: Boolean, message: String): WeatherState =
        WeatherState(
            city, country, latitude, longitude, temperature, apparentTemperature,
            precipitation, windSpeed, weatherCode, isDay, updatedAt,
            stale, error, loading, message, daily, precipitation15, aqi, aqiLabel, hourly,
        )

    companion object {
        @JvmStatic
        fun empty(city: String, country: String, latitude: Double, longitude: Double): WeatherState =
            WeatherState(
                city, country, latitude, longitude,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                -1, true, 0L, true, false, false,
            )
    }
}
