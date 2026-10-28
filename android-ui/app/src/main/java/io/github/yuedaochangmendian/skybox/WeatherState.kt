package io.github.yuedaochangmendian.skybox

/**
 * Immutable weather snapshot. Temperatures are Celsius, precipitation is mm and wind is km/h.
 * Missing measurements are Double.NaN; updatedAt is the last successful fetch time (epoch ms),
 * or zero before the first success. An error never discards the last successful measurements.
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
) {
    fun hasData(): Boolean = updatedAt > 0L && !temperature.isNaN() && !temperature.isInfinite()

    fun withStatus(stale: Boolean, error: Boolean, loading: Boolean, message: String): WeatherState =
        WeatherState(
            city, country, latitude, longitude, temperature, apparentTemperature,
            precipitation, windSpeed, weatherCode, isDay, updatedAt,
            stale, error, loading, message,
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
