import 'package:flutter/material.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import '../../models/weather.dart';
import '../../state/app_state.dart';
import '../../theme/sky_theme.dart';
import '../../util/format.dart';
import '../widgets/glass.dart';
import '../widgets/temperature_bar.dart';
import '../widgets/weather_icon.dart';

/// Weather detail sheet: hourly curve, the four-day range, the 15-minute
/// nowcast, air quality and the current tunnel latency.
Future<void> openWeatherDetail(
  BuildContext context,
  AppState app, {
  bool latencyFocus = false,
}) async {
  final theme = SkyTheme.of(app.weather);
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: const Color(0x33000000),
    builder: (sheetContext) => DraggableScrollableSheet(
      initialChildSize: 0.72,
      minChildSize: 0.4,
      maxChildSize: 0.94,
      expand: false,
      builder: (context, controller) => GlassSheet(
        child: _WeatherDetailBody(
          app: app,
          theme: theme,
          controller: controller,
          latencyFocus: latencyFocus,
        ),
      ),
    ),
  );
}

class _WeatherDetailBody extends StatelessWidget {
  const _WeatherDetailBody({
    required this.app,
    required this.theme,
    required this.controller,
    required this.latencyFocus,
  });

  final AppState app;
  final SkyTheme theme;
  final ScrollController controller;
  final bool latencyFocus;

  @override
  Widget build(BuildContext context) {
    final weather = app.weather;
    final textColor = theme.textColor;
    final soft = theme.secondaryTextColor;
    return ListView(
      controller: controller,
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
      children: <Widget>[
        Center(
          child: Container(
            width: 36,
            height: 4,
            decoration: BoxDecoration(
              color: textColor.withAlpha(0x40),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
        ),
        const SizedBox(height: 16),
        Row(
          children: <Widget>[
            WeatherIcon(kind: theme.icon, color: theme.iconColor, size: 34),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                weather?.city ?? '未知地区',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: textColor),
              ),
            ),
            Text(
              weather == null || !weather.hasData
                  ? '—'
                  : '${roundCelsius(weather.temperature)}°',
              style: TextStyle(fontSize: 28, fontWeight: FontWeight.w200, color: textColor),
            ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          weather == null ? '天气暂不可用' : '${theme.nameZh} · ${theme.nameEn}',
          style: TextStyle(fontSize: 14, color: soft),
        ),
        const SizedBox(height: 18),
        _Section(
          theme: theme,
          title: '实时',
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: <Widget>[
              _Metric(theme: theme, label: '体感', value: _celsius(weather?.apparentTemperature)),
              _Metric(theme: theme, label: '降水', value: _mm(weather?.precipitation)),
              _Metric(theme: theme, label: '风速', value: _kmh(weather?.windSpeed)),
              _Metric(
                theme: theme,
                label: '空气质量',
                value: weather == null
                    ? '—'
                    : weather.usableAqi.isNaN
                        ? '—'
                        : '${weather.usableAqi.round()} ${weather.aqiLabel}',
              ),
              _Metric(
                theme: theme,
                label: '隧道延迟',
                value: app.latencyProbe.milliseconds >= 0
                    ? '${app.latencyProbe.milliseconds} ms'
                    : app.running
                        ? '未测'
                        : '未连接',
                highlight: latencyFocus,
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        if (weather != null && weather.hourly.isNotEmpty)
          _Section(
            theme: theme,
            title: '未来 24 小时',
            child: SizedBox(
              height: 74,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: weather.hourly.length,
                separatorBuilder: (_, _) => const SizedBox(width: 12),
                itemBuilder: (context, index) {
                  final hour = weather.hourly[index];
                  return SizedBox(
                    width: 46,
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: <Widget>[
                        Text(hour.label, style: TextStyle(fontSize: 11, color: soft)),
                        const SizedBox(height: 4),
                        WeatherIcon(
                          kind: SkyTheme.from(code: hour.weatherCode, isDay: weather.isDay).icon,
                          color: theme.iconColor,
                          size: 20,
                        ),
                        const SizedBox(height: 4),
                        Text('${hour.temperature.round()}°',
                            style: TextStyle(fontSize: 13, color: textColor)),
                      ],
                    ),
                  );
                },
              ),
            ),
          ),
        if (weather != null && weather.daily.isNotEmpty) ...<Widget>[
          const SizedBox(height: 14),
          _Section(
            theme: theme,
            title: '未来几天',
            child: Column(
              children: <Widget>[
                for (final day in weather.daily)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: Row(
                      children: <Widget>[
                        SizedBox(
                          width: 52,
                          child: Text(day.label, style: TextStyle(fontSize: 13, color: soft)),
                        ),
                        WeatherIcon(
                          kind: SkyTheme.from(code: day.weatherCode, isDay: weather.isDay).icon,
                          color: theme.iconColor,
                          size: 18,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: TemperatureRangeBar(
                            min: day.min,
                            max: day.max,
                            lowest: _lowest(weather.daily),
                            highest: _highest(weather.daily),
                            current: weather.temperature,
                            fillColor: theme.iconColor.withAlpha(0xB0),
                            trackColor: textColor.withAlpha(0x18),
                            dotColor: textColor,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text('${day.max.round()}°/${day.min.round()}°',
                            style: TextStyle(fontSize: 12, color: textColor)),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ],
        if (weather != null && weather.precipitation15.isNotEmpty) ...<Widget>[
          const SizedBox(height: 14),
          _Section(
            theme: theme,
            title: '未来 2 小时降水（mm / 15 分钟）',
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: <Widget>[
                for (final value in weather.precipitation15)
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 2),
                      child: Column(
                        children: <Widget>[
                          Text(
                            value <= 0 ? '' : value.toStringAsFixed(1),
                            style: TextStyle(fontSize: 10, color: soft),
                          ),
                          const SizedBox(height: 3),
                          Container(
                            height: (value * 22).clamp(2.0, 44.0),
                            decoration: BoxDecoration(
                              color: theme.iconColor.withAlpha(0xA0),
                              borderRadius: BorderRadius.circular(3),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ],
    );
  }

  static String _celsius(double? value) =>
      value == null || !value.isFinite ? '—' : '${value.round()}°';

  static String _mm(double? value) =>
      value == null || !value.isFinite ? '—' : '${value.toStringAsFixed(1)} mm';

  static String _kmh(double? value) =>
      value == null || !value.isFinite ? '—' : '${value.toStringAsFixed(0)} km/h';

  static double _lowest(List<DailyForecast> days) {
    var result = double.infinity;
    for (final day in days) {
      if (day.min.isFinite && day.min < result) result = day.min;
    }
    return result;
  }

  static double _highest(List<DailyForecast> days) {
    var result = double.negativeInfinity;
    for (final day in days) {
      if (day.max.isFinite && day.max > result) result = day.max;
    }
    return result;
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.theme, required this.title, required this.child});

  final SkyTheme theme;
  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            title,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.bold,
              color: theme.textColor,
            ),
          ),
          const SizedBox(height: 8),
          child,
        ],
      );
}

class _Metric extends StatelessWidget {
  const _Metric({
    required this.theme,
    required this.label,
    required this.value,
    this.highlight = false,
  });

  final SkyTheme theme;
  final String label;
  final String value;
  final bool highlight;

  @override
  Widget build(BuildContext context) => SkyGlassCard(
        tint: theme.cardColor,
        dark: !theme.isDay,
        alpha: highlight ? 0.6 : 0.34,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        radius: 14,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text(label, style: TextStyle(fontSize: 11, color: theme.secondaryTextColor)),
            const SizedBox(height: 2),
            Text(value, style: TextStyle(fontSize: 14, color: theme.textColor)),
          ],
        ),
      );
}
