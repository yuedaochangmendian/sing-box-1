import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../models/weather.dart';

/// Weather, air quality and city search over Open-Meteo (HTTPS, no API key).
///
/// Ported from the previous Kotlin service and kept behaviour-compatible:
///   * a 15-minute per-coordinate request-start limit that no force can bypass;
///   * an old city's response can never overwrite a newer selection;
///   * a failed request never discards the last successful reading;
///   * air quality is optional and its failure never hides the weather.
class WeatherService {
  WeatherService({http.Client? client}) : _client = client ?? http.Client();

  static const Duration minRefreshInterval = Duration(minutes: 15);
  static const Duration staleAfter = Duration(minutes: 15);
  static const int maxResponseBytes = 256 * 1024;
  static const Duration timeout = Duration(seconds: 8);
  static const int maxQueryLength = 120;

  static const String _attemptPrefix = 'attempt_';

  final http.Client _client;
  final Map<String, int> _lastAttempts = <String, int>{};
  final Map<String, WeatherState> _snapshots = <String, WeatherState>{};

  /// Bumped for every request; a stale generation discards late responses.
  int _generation = 0;

  WeatherCity _city = const WeatherCity(
    displayName: '台北',
    country: 'TW',
    latitude: 25.0330,
    longitude: 121.5654,
    timezone: 'auto',
  );

  WeatherState _state = WeatherState.empty(
    const WeatherCity(
      displayName: '台北',
      country: 'TW',
      latitude: 25.0330,
      longitude: 121.5654,
      timezone: 'auto',
    ),
  );

  bool _fetching = false;
  bool _manual = false;
  bool _closed = false;
  SharedPreferences? _prefs;

  void Function(WeatherState state)? onWeather;
  void Function(List<WeatherCity> cities)? onCities;
  void Function(String message, {required bool citySearch})? onError;

  WeatherCity get city => _city;
  WeatherState get state => _aged(_state);
  bool get isManual => _manual;

  Future<void> restore() async {
    _prefs = await SharedPreferences.getInstance();
    _city = _readCity(_prefs!);
    _manual = _prefs!.getBool('manual') ?? false;
    _restoreAttempts(_prefs!);
    final restored = _readSnapshot(_prefs!);
    if (restored != null) {
      _snapshots[_key(restored.latitude, restored.longitude)] = restored;
      if (_key(restored.latitude, restored.longitude) ==
          _key(_city.latitude, _city.longitude)) {
        _state = restored.relabelled(_city);
      }
      _emit(_state);
    }
  }

  Future<void> close() async {
    _closed = true;
    _client.close();
  }

  /// Persists a selection and fetches it. [manual] pins the city so the
  /// node-region follower stops overriding it.
  Future<void> setCity(WeatherCity selected, {bool manual = true}) async {
    if (_closed) return;
    _generation++;
    _fetching = false;
    _city = selected;
    _manual = manual;
    await _saveCity(selected);
    final cached = _snapshots[_key(selected.latitude, selected.longitude)];
    _state = cached == null
        ? WeatherState.empty(selected)
        : cached.relabelled(selected);
    _emit(_state);
    await fetch(force: true);
  }

  Future<void> fetch({required bool force}) async {
    if (_closed || _fetching) return;
    final generation = ++_generation;
    _state = _aged(_state);
    final now = DateTime.now().millisecondsSinceEpoch;
    final last = _lastAttempt(_key(_city.latitude, _city.longitude), now);
    final throttled =
        last != null && now >= last && now - last < minRefreshInterval.inMilliseconds;
    if (throttled || (!force && _state.hasData && !_state.stale && !_state.error)) {
      _emit(_state);
      return;
    }
    final target = _city;
    await _recordAttempt(_key(target.latitude, target.longitude), now);
    _fetching = true;
    _state = _state.copyWithStatus(
      stale: _state.stale,
      error: false,
      loading: true,
      message: '',
    );
    _emit(_state);
    final reading = await _loadWeather(generation, target);
    if (_closed || generation != _generation) return;
    _fetching = false;
    if (reading == null) {
      _state = _state.copyWithStatus(
        stale: true,
        error: true,
        loading: false,
        message: _lastFailure,
      );
      _emit(_state);
      onError?.call(_lastFailure, citySearch: false);
      return;
    }
    _city = WeatherCity(
      displayName: target.displayName,
      country: target.country,
      latitude: target.latitude,
      longitude: target.longitude,
      timezone: reading.timezone,
    );
    await _saveCity(_city);
    _state = reading;
    _snapshots[_key(target.latitude, target.longitude)] = reading;
    await _saveSnapshot(reading);
    _emit(reading);
  }

  /// Latest query wins. Empty input cancels any pending search.
  Future<void> searchCity(String query) async {
    if (_closed) return;
    final text = query.trim();
    if (text.isEmpty) {
      onCities?.call(const <WeatherCity>[]);
      return;
    }
    if (text.length > maxQueryLength) {
      onError?.call('城市名称最多 $maxQueryLength 个字符', citySearch: true);
      return;
    }
    try {
      var found = await _searchOnce(text);
      // The geocoding index stores full administrative names (台北市, not 台北),
      // so retry once with the common 市 suffix before reporting nothing.
      if (found.isEmpty && text.length >= 2 && !text.endsWith('市')) {
        found = await _searchOnce('$text市');
      }
      if (_closed) return;
      onCities?.call(found);
    } catch (error) {
      if (_closed) return;
      onError?.call(_message(error), citySearch: true);
    }
  }

  Future<List<WeatherCity>> _searchOnce(String text) async {
    final uri = Uri.https('geocoding-api.open-meteo.com', '/v1/search', <String, String>{
      'name': text,
      'count': '10',
      'language': 'zh',
      'format': 'json',
    });
    final document = await _getJson(uri);
    final results = document['results'];
    if (results is! List) return const <WeatherCity>[];
    final found = <WeatherCity>[];
    for (final entry in results) {
      if (entry is! Map<String, dynamic>) continue;
      try {
        var name = (entry['name'] as String? ?? '').trim();
        final region = entry['admin1'] as String? ?? '';
        if (region.isNotEmpty && region != name) name = '$name · $region';
        final city = WeatherCity(
          displayName: name,
          country: entry['country'] as String? ?? '',
          latitude: _number(entry['latitude']),
          longitude: _number(entry['longitude']),
          timezone: entry['timezone'] as String? ?? 'auto',
        );
        if (city.displayName.isNotEmpty && city.hasCoordinates) found.add(city);
      } catch (_) {
        // One malformed candidate must not hide the other valid cities.
      }
    }
    return found;
  }

  Future<WeatherState?> _loadWeather(int generation, WeatherCity target) async {
    try {
      final uri =
          Uri.https('api.open-meteo.com', '/v1/forecast', <String, String>{
        'latitude': '${target.latitude}',
        'longitude': '${target.longitude}',
        'current':
            'temperature_2m,apparent_temperature,precipitation,wind_speed_10m,weather_code,is_day',
        'daily': 'weather_code,temperature_2m_max,temperature_2m_min',
        'forecast_days': '4',
        'hourly': 'temperature_2m,weather_code',
        'forecast_hours': '24',
        'minutely_15': 'precipitation',
        'forecast_minutely_15': '8',
        'temperature_unit': 'celsius',
        'wind_speed_unit': 'kmh',
        'precipitation_unit': 'mm',
        'timezone': target.timezone,
      });
      final document = await _getJson(uri);
      final current = document['current'];
      if (current is! Map<String, dynamic>) throw const FormatException('天气字段缺失');
      final code = _number(current['weather_code']);
      final day = _number(current['is_day']);
      final rain = _number(current['precipitation']);
      final wind = _number(current['wind_speed_10m']);
      if (code != code.roundToDouble() || code < 0 || code > 99) {
        throw const FormatException('天气字段无效');
      }
      if (day != 0 && day != 1) throw const FormatException('天气字段无效');
      if (rain < 0 || wind < 0) throw const FormatException('天气字段无效');

      final aqi = await _loadAirQuality(target);
      return WeatherState(
        city: target.displayName,
        country: target.country,
        latitude: target.latitude,
        longitude: target.longitude,
        temperature: _number(current['temperature_2m']),
        apparentTemperature: _number(current['apparent_temperature']),
        precipitation: rain,
        windSpeed: wind,
        weatherCode: code.toInt(),
        isDay: day == 1,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
        stale: false,
        error: false,
        loading: false,
        daily: _parseDaily(document),
        precipitation15: _parseNowcast(document),
        hourly: _parseHourly(document),
        aqi: aqi.$1,
        aqiLabel: aqi.$2,
        timezone: document['timezone'] as String? ?? target.timezone,
      );
    } catch (error) {
      _lastFailure = _message(error);
      return null;
    }
  }

  /// Optional US AQI lookup; its failure never turns into a weather failure.
  Future<(double, String)> _loadAirQuality(WeatherCity target) async {
    try {
      final uri = Uri.https(
        'air-quality-api.open-meteo.com',
        '/v1/air-quality',
        <String, String>{
          'latitude': '${target.latitude}',
          'longitude': '${target.longitude}',
          'current': 'us_aqi',
          'timezone': target.timezone,
        },
      );
      final document = await _getJson(uri);
      final current = document['current'];
      final value = current is Map ? _number(current['us_aqi']) : double.nan;
      if (!value.isFinite || value < 0) return (double.nan, '');
      return (value, aqiBand(value));
    } catch (_) {
      return (double.nan, '');
    }
  }

  String _lastFailure = '天气加载失败';

  Future<Map<String, dynamic>> _getJson(Uri uri) async {
    final response = await _client
        .get(uri, headers: <String, String>{'Accept': 'application/json'})
        .timeout(timeout);
    if (response.statusCode != 200) {
      throw FormatException('天气服务 HTTP ${response.statusCode}');
    }
    if (response.bodyBytes.lengthInBytes > maxResponseBytes) {
      throw const FormatException('天气响应超过 256 KB 限制');
    }
    final decoded = jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('天气响应格式错误');
    }
    if (decoded['error'] == true) {
      throw const FormatException('天气服务暂时无法处理请求');
    }
    return decoded;
  }

  List<DailyForecast> _parseDaily(Map<String, dynamic> document) {
    final daily = document['daily'];
    if (daily is! Map<String, dynamic>) return const <DailyForecast>[];
    final times = daily['time'];
    final codes = daily['weather_code'];
    final maxima = daily['temperature_2m_max'];
    final minima = daily['temperature_2m_min'];
    if (times is! List || codes is! List || maxima is! List || minima is! List) {
      return const <DailyForecast>[];
    }
    final count = math.min(times.length,
        math.min(codes.length, math.min(maxima.length, math.min(minima.length, 4))));
    final result = <DailyForecast>[];
    for (var i = 0; i < count; i++) {
      final max = _number(maxima[i]);
      final min = _number(minima[i]);
      if (!max.isFinite || !min.isFinite || max < min) continue;
      result.add(DailyForecast(
        label: _dayLabel(times[i] as String? ?? '', i),
        weatherCode: _code(codes[i]),
        max: max,
        min: min,
      ));
    }
    return result;
  }

  List<double> _parseNowcast(Map<String, dynamic> document) {
    final section = document['minutely_15'];
    if (section is! Map<String, dynamic>) return const <double>[];
    final values = section['precipitation'];
    if (values is! List) return const <double>[];
    return values
        .map((value) {
          final parsed = _number(value);
          return parsed.isFinite && parsed > 0 ? parsed : 0.0;
        })
        .toList(growable: false);
  }

  List<HourForecast> _parseHourly(Map<String, dynamic> document) {
    final section = document['hourly'];
    if (section is! Map<String, dynamic>) return const <HourForecast>[];
    final times = section['time'];
    final temperatures = section['temperature_2m'];
    final codes = section['weather_code'];
    if (times is! List || temperatures is! List) return const <HourForecast>[];
    final count = math.min(times.length, math.min(temperatures.length, 24));
    final result = <HourForecast>[];
    for (var i = 0; i < count; i++) {
      final temperature = _number(temperatures[i]);
      if (!temperature.isFinite) continue;
      result.add(HourForecast(
        label: _hourLabel(times[i] as String? ?? ''),
        weatherCode: codes is List && i < codes.length ? _code(codes[i]) : -1,
        temperature: temperature,
      ));
    }
    return result;
  }

  WeatherState _aged(WeatherState state) {
    if (state.updatedAt <= 0) return state;
    final stale = state.isAged(staleAfter, DateTime.now().millisecondsSinceEpoch);
    if (stale == state.stale) return state;
    return state.copyWithStatus(
      stale: stale,
      error: state.error,
      loading: state.loading,
      message: state.errorMessage,
    );
  }

  void _emit(WeatherState state) => onWeather?.call(state);

  String _key(double latitude, double longitude) =>
      '${latitude.toStringAsFixed(4)},${longitude.toStringAsFixed(4)}';

  int? _lastAttempt(String key, int now) {
    final recorded = _lastAttempts[key];
    if (recorded == null) return null;
    // A timestamp far ahead means the clock jumped backwards; drop it so the
    // user is not blocked until the clock catches up.
    if (recorded < 0 || recorded - now > 60000) {
      _lastAttempts.remove(key);
      _prefs?.remove('$_attemptPrefix$key');
      return null;
    }
    return recorded;
  }

  Future<void> _recordAttempt(String key, int now) async {
    _lastAttempts[key] = now;
    final prefs = _prefs;
    if (prefs == null) return;
    await prefs.setInt('$_attemptPrefix$key', now);
    final obsolete = _lastAttempts.entries
        .where((entry) => entry.key != key && now - entry.value >= minRefreshInterval.inMilliseconds)
        .map((entry) => entry.key)
        .toList();
    for (final name in obsolete) {
      _lastAttempts.remove(name);
      await prefs.remove('$_attemptPrefix$name');
    }
  }

  void _restoreAttempts(SharedPreferences prefs) {
    final now = DateTime.now().millisecondsSinceEpoch;
    for (final name in prefs.getKeys().where((k) => k.startsWith(_attemptPrefix))) {
      final recorded = prefs.getInt(name);
      final key = name.substring(_attemptPrefix.length);
      if (recorded == null ||
          key.isEmpty ||
          now - recorded >= minRefreshInterval.inMilliseconds) {
        prefs.remove(name);
        continue;
      }
      _lastAttempts[key] = recorded;
    }
  }

  WeatherCity _readCity(SharedPreferences prefs) {
    final city = WeatherCity(
      displayName: prefs.getString('city') ?? '台北',
      country: prefs.getString('country') ?? 'TW',
      latitude: double.tryParse(prefs.getString('latitude') ?? '') ?? 25.0330,
      longitude: double.tryParse(prefs.getString('longitude') ?? '') ?? 121.5654,
      timezone: prefs.getString('timezone') ?? 'auto',
    );
    return city.hasCoordinates ? city : _city;
  }

  Future<void> _saveCity(WeatherCity value) async {
    final prefs = _prefs;
    if (prefs == null) return;
    await prefs.setString('city', value.displayName);
    await prefs.setString('country', value.country);
    await prefs.setString('latitude', '${value.latitude}');
    await prefs.setString('longitude', '${value.longitude}');
    await prefs.setString('timezone', value.timezone);
  }

  Future<void> _saveSnapshot(WeatherState value) async {
    final prefs = _prefs;
    if (prefs == null) return;
    await prefs.setString('snapshot', jsonEncode(<String, dynamic>{
      'city': value.city,
      'country': value.country,
      'latitude': value.latitude,
      'longitude': value.longitude,
      'temperature': value.temperature,
      'apparent': value.apparentTemperature,
      'precipitation': value.precipitation,
      'wind': value.windSpeed,
      'code': value.weatherCode,
      'isDay': value.isDay,
      'updatedAt': value.updatedAt,
      'aqi': value.aqi,
      'aqiLabel': value.aqiLabel,
      'daily': value.daily
          .map((day) => <String, dynamic>{
                'label': day.label,
                'code': day.weatherCode,
                'max': day.max,
                'min': day.min,
              })
          .toList(),
      'hourly': value.hourly
          .map((hour) => <String, dynamic>{
                'label': hour.label,
                'code': hour.weatherCode,
                't': hour.temperature,
              })
          .toList(),
      'nowcast': value.precipitation15,
      'timezone': value.timezone,
    }));
  }

  WeatherState? _readSnapshot(SharedPreferences prefs) {
    final raw = prefs.getString('snapshot');
    if (raw == null) return null;
    try {
      final json = jsonDecode(raw) as Map<String, dynamic>;
      final isDay = json['isDay'] == true;
      final code = json['code'] is num ? (json['code'] as num).toInt() : -1;
      return WeatherState(
        city: json['city'] as String? ?? '',
        country: json['country'] as String? ?? '',
        latitude: _number(json['latitude']),
        longitude: _number(json['longitude']),
        temperature: _number(json['temperature']),
        apparentTemperature: _number(json['apparent']),
        precipitation: _number(json['precipitation']),
        windSpeed: _number(json['wind']),
        weatherCode: code,
        isDay: isDay,
        updatedAt: json['updatedAt'] is num ? (json['updatedAt'] as num).toInt() : 0,
        stale: true,
        error: false,
        loading: false,
        aqi: _number(json['aqi']),
        aqiLabel: json['aqiLabel'] as String? ?? '',
        daily: ((json['daily'] as List?) ?? const <dynamic>[])
            .whereType<Map<String, dynamic>>()
            .map((day) => DailyForecast(
                  label: day['label'] as String? ?? '',
                  weatherCode: day['code'] is num ? (day['code'] as num).toInt() : -1,
                  max: _number(day['max']),
                  min: _number(day['min']),
                ))
            .toList(),
        hourly: ((json['hourly'] as List?) ?? const <dynamic>[])
            .whereType<Map<String, dynamic>>()
            .map((hour) => HourForecast(
                  label: hour['label'] as String? ?? '',
                  weatherCode: hour['code'] is num ? (hour['code'] as num).toInt() : -1,
                  temperature: _number(hour['t']),
                ))
            .toList(),
        precipitation15: ((json['nowcast'] as List?) ?? const <dynamic>[])
            .map((value) => _number(value))
            .toList(),
        timezone: json['timezone'] as String? ?? 'auto',
      );
    } catch (_) {
      return null;
    }
  }

  static double _number(Object? value) {
    if (value is num) return value.toDouble();
    if (value is String) return double.tryParse(value) ?? double.nan;
    return double.nan;
  }

  static int _code(Object? value) {
    if (value is num) return value.toInt();
    return -1;
  }

  static String _dayLabel(String iso, int index) {
    if (index == 0) return '今天';
    if (index == 1) return '明天';
    final parts = iso.split('-');
    if (parts.length != 3) return iso;
    final date = DateTime.tryParse(iso);
    if (date == null) return iso;
    return '周${'一二三四五六日'[date.weekday - 1]}';
  }

  static String _hourLabel(String iso) {
    final marker = iso.indexOf('T');
    if (marker < 0 || marker + 3 > iso.length) return iso;
    return '${iso.substring(marker + 1, marker + 3)}时';
  }

  static String _message(Object error) {
    final text = error.toString();
    final cleaned = text.replaceFirst(RegExp(r'^Exception: '), '');
    return cleaned.isEmpty ? '天气加载失败' : cleaned;
  }
}

/// US AQI bands, matching the labels the previous native build showed.
String aqiBand(double value) {
  if (value <= 50) return '优';
  if (value <= 100) return '良';
  if (value <= 150) return '轻度污染';
  if (value <= 200) return '中度污染';
  if (value <= 300) return '重度污染';
  return '严重污染';
}
