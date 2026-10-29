// Weather data model. All temperatures are Celsius, precipitation mm, wind km/h.
// Missing measurements stay NaN rather than becoming 0, so the UI can tell
// "no reading" apart from "measured zero".

class DailyForecast {
  const DailyForecast({
    required this.label,
    required this.weatherCode,
    required this.max,
    required this.min,
  });

  final String label;
  final int weatherCode;
  final double max;
  final double min;

  bool get isValid => max.isFinite && min.isFinite && max >= min;
}

class HourForecast {
  const HourForecast({
    required this.label,
    required this.weatherCode,
    required this.temperature,
  });

  final String label;
  final int weatherCode;
  final double temperature;

  bool get isValid => temperature.isFinite;
}

class WeatherCity {
  const WeatherCity({
    required this.displayName,
    required this.country,
    required this.latitude,
    required this.longitude,
    required this.timezone,
  });

  final String displayName;
  final String country;
  final double latitude;
  final double longitude;
  final String timezone;

  /// The provider accepts anything on land; an out-of-range coordinate simply
  /// fails the request, which the service reports as an error.
  bool get hasCoordinates =>
      latitude.isFinite &&
      longitude.isFinite &&
      latitude.abs() <= 90 &&
      longitude.abs() <= 180;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'name': displayName,
        'country': country,
        'latitude': latitude,
        'longitude': longitude,
        'timezone': timezone,
      };

  static WeatherCity fromJson(Map<String, dynamic> json) => WeatherCity(
        displayName: json['name'] as String? ?? '',
        country: json['country'] as String? ?? '',
        latitude: _asDouble(json['latitude']),
        longitude: _asDouble(json['longitude']),
        timezone: json['timezone'] as String? ?? 'auto',
      );

  static double _asDouble(Object? value) {
    if (value is num) return value.toDouble();
    if (value is String) return double.tryParse(value) ?? double.nan;
    return double.nan;
  }
}

class WeatherState {
  const WeatherState({
    required this.city,
    required this.country,
    required this.latitude,
    required this.longitude,
    required this.temperature,
    required this.apparentTemperature,
    required this.precipitation,
    required this.windSpeed,
    required this.weatherCode,
    required this.isDay,
    required this.updatedAt,
    required this.stale,
    required this.error,
    required this.loading,
    this.timezone = 'auto',
    this.errorMessage = '',
    this.daily = const <DailyForecast>[],
    this.precipitation15 = const <double>[],
    this.aqi = double.nan,
    this.aqiLabel = '',
    this.hourly = const <HourForecast>[],
  });

  final String city;
  final String country;
  final double latitude;
  final double longitude;
  final double temperature;
  final double apparentTemperature;
  final double precipitation;
  final double windSpeed;
  final int weatherCode;
  final bool isDay;
  final int updatedAt;
  final bool stale;
  final bool error;
  final bool loading;
  final String timezone;
  final String errorMessage;
  final List<DailyForecast> daily;
  final List<double> precipitation15;
  final double aqi;
  final String aqiLabel;
  final List<HourForecast> hourly;

  bool get hasData =>
      updatedAt > 0 && temperature.isFinite && !temperature.isInfinite;

  double get todayMax => daily.isEmpty ? double.nan : daily.first.max;
  double get todayMin => daily.isEmpty ? double.nan : daily.first.min;

  /// Below zero is never a real reading; the charts treat it as missing.
  double get usableAqi => aqi.isFinite && aqi >= 0 ? aqi : double.nan;

  WeatherState copyWithStatus({
    required bool stale,
    required bool error,
    required bool loading,
    required String message,
  }) =>
      WeatherState(
        city: city,
        country: country,
        latitude: latitude,
        longitude: longitude,
        temperature: temperature,
        apparentTemperature: apparentTemperature,
        precipitation: precipitation,
        windSpeed: windSpeed,
        weatherCode: weatherCode,
        isDay: isDay,
        updatedAt: updatedAt,
        stale: stale,
        error: error,
        loading: loading,
        timezone: timezone,
        errorMessage: message,
        daily: daily,
        precipitation15: precipitation15,
        aqi: aqi,
        aqiLabel: aqiLabel,
        hourly: hourly,
      );

  WeatherState relabelled(WeatherCity target) => WeatherState(
        city: target.displayName,
        country: target.country,
        latitude: latitude,
        longitude: longitude,
        temperature: temperature,
        apparentTemperature: apparentTemperature,
        precipitation: precipitation,
        windSpeed: windSpeed,
        weatherCode: weatherCode,
        isDay: isDay,
        updatedAt: updatedAt,
        stale: stale,
        error: error,
        loading: loading,
        timezone: timezone,
        errorMessage: errorMessage,
        daily: daily,
        precipitation15: precipitation15,
        aqi: aqi,
        aqiLabel: aqiLabel,
        hourly: hourly,
      );

  static WeatherState empty(WeatherCity city) => WeatherState(
        city: city.displayName,
        country: city.country,
        latitude: city.latitude,
        longitude: city.longitude,
        temperature: double.nan,
        apparentTemperature: double.nan,
        precipitation: double.nan,
        windSpeed: double.nan,
        weatherCode: -1,
        isDay: true,
        updatedAt: 0,
        stale: true,
        error: false,
        loading: false,
      );

  /// The status line only changes with the reading itself, never with an error.
  bool isAged(Duration threshold, int now) =>
      updatedAt <= 0 || now - updatedAt >= threshold.inMilliseconds;
}
