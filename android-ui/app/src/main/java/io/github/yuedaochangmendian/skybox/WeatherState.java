package io.github.yuedaochangmendian.skybox;

/**
 * Immutable weather snapshot. Temperatures are Celsius, precipitation is mm and wind is km/h.
 * Missing measurements are Double.NaN; updatedAt is the last successful fetch time (epoch ms),
 * or zero before the first success. An error never discards the last successful measurements.
 */
public final class WeatherState {
    public final String city;
    public final String country;
    public final double latitude;
    public final double longitude;
    public final double temperature;
    public final double apparentTemperature;
    public final double precipitation;
    public final double windSpeed;
    public final int weatherCode;
    public final boolean isDay;
    public final long updatedAt;
    public final boolean stale;
    public final boolean error;
    public final boolean loading;
    public final String errorMessage;

    public WeatherState(String city, String country, double latitude, double longitude,
                        double temperature, double apparentTemperature, double precipitation,
                        double windSpeed, int weatherCode, boolean isDay, long updatedAt,
                        boolean stale, boolean error, boolean loading) {
        this(city, country, latitude, longitude, temperature, apparentTemperature,
                precipitation, windSpeed, weatherCode, isDay, updatedAt,
                stale, error, loading, "");
    }

    public WeatherState(String city, String country, double latitude, double longitude,
                        double temperature, double apparentTemperature, double precipitation,
                        double windSpeed, int weatherCode, boolean isDay, long updatedAt,
                        boolean stale, boolean error, boolean loading, String errorMessage) {
        this.city = city == null ? "" : city;
        this.country = country == null ? "" : country;
        this.latitude = latitude;
        this.longitude = longitude;
        this.temperature = temperature;
        this.apparentTemperature = apparentTemperature;
        this.precipitation = precipitation;
        this.windSpeed = windSpeed;
        this.weatherCode = weatherCode;
        this.isDay = isDay;
        this.updatedAt = Math.max(0L, updatedAt);
        this.stale = stale;
        this.error = error;
        this.loading = loading;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static WeatherState empty(String city, String country, double latitude,
                                     double longitude) {
        return new WeatherState(city, country, latitude, longitude,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                -1, true, 0L, true, false, false);
    }

    public boolean hasData() {
        return updatedAt > 0L && !Double.isNaN(temperature) && !Double.isInfinite(temperature);
    }

    public WeatherState withStatus(boolean stale, boolean error, boolean loading,
                                   String message) {
        return new WeatherState(city, country, latitude, longitude, temperature,
                apparentTemperature, precipitation, windSpeed, weatherCode, isDay,
                updatedAt, stale, error, loading, message);
    }
}
