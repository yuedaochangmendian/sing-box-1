import 'package:flutter/material.dart';

import '../models/weather.dart';
import '../util/format.dart';

/// Icon kinds drawn by [WeatherIcon]; selected from the WMO code the same way
/// the previous native theme chose its vector drawables.
enum WeatherIconKind { sun, moon, partlyCloudy, cloudNight, cloud, fog, rain, snow, thunderstorm, unknown }

/// WMO weather names and the original sky palettes.
class SkyTheme {
  const SkyTheme({
    required this.type,
    required this.isDay,
    required this.nameZh,
    required this.nameEn,
    required this.icon,
    required this.gradient,
  });

  final SkyType type;
  final bool isDay;
  final String nameZh;
  final String nameEn;
  final WeatherIconKind icon;
  final List<Color> gradient;

  Color get top => gradient[0];
  Color get middle => gradient[1];
  Color get bottom => gradient[2];
  Color get surfaceColor => bottom;

  Color get cardColor => mixColor(
        bottom,
        isDay ? const Color(0xFFFFFFFF) : const Color(0xFF091322),
        isDay ? 0.70 : 0.25,
      );

  Color get dockColor => mixColor(
        bottom,
        isDay ? const Color(0xFFFFFFFF) : const Color(0xFF091322),
        isDay ? 0.88 : 0.45,
      );

  Color get textColor =>
      isDay ? const Color(0xFF142B43) : const Color(0xFFF4F8FF);

  Color get secondaryTextColor =>
      isDay ? const Color(0xFF354C63) : const Color(0xFFD0DBEB);

  Color get iconColor =>
      isDay ? const Color(0xFF174268) : const Color(0xFFE5F2FF);

  /// The accent used for the primary tinted pill.
  Color get accent =>
      isDay ? const Color(0xFF2C6BEA) : const Color(0xFF5B8DEF);

  LinearGradient get background => LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: gradient,
      );

  static SkyTheme from({required int code, required bool isDay}) {
    final SkyType type;
    final WeatherIconKind icon;
    final String zh;
    final String en;
    switch (code) {
      case 0:
        type = SkyType.clear;
        icon = isDay ? WeatherIconKind.sun : WeatherIconKind.moon;
        zh = isDay ? '晴' : '晴朗夜空';
        en = isDay ? 'Clear sky' : 'Clear night';
      case 1:
        type = SkyType.partlyCloudy;
        icon = isDay ? WeatherIconKind.partlyCloudy : WeatherIconKind.cloudNight;
        zh = '晴间少云';
        en = 'Mainly clear';
      case 2:
        type = SkyType.partlyCloudy;
        icon = isDay ? WeatherIconKind.partlyCloudy : WeatherIconKind.cloudNight;
        zh = '多云';
        en = 'Partly cloudy';
      case 3:
        type = SkyType.cloudy;
        icon = WeatherIconKind.cloud;
        zh = '阴';
        en = 'Overcast';
      case 45:
      case 48:
        type = SkyType.fog;
        icon = WeatherIconKind.fog;
        zh = code == 48 ? '雾凇' : '雾';
        en = code == 48 ? 'Depositing rime fog' : 'Fog';
      case 51:
      case 53:
      case 55:
      case 56:
      case 57:
        type = SkyType.drizzle;
        icon = WeatherIconKind.rain;
        if (code == 56 || code == 57) {
          zh = code == 56 ? '轻冻毛毛雨' : '强冻毛毛雨';
          en = code == 56 ? 'Light freezing drizzle' : 'Dense freezing drizzle';
        } else {
          zh = switch (code) { 51 => '轻毛毛雨', 53 => '中等毛毛雨', _ => '强毛毛雨' };
          en = switch (code) {
            51 => 'Light drizzle',
            53 => 'Moderate drizzle',
            _ => 'Dense drizzle'
          };
        }
      case 61:
      case 63:
      case 65:
      case 66:
      case 67:
      case 80:
      case 81:
      case 82:
        type = SkyType.rain;
        icon = WeatherIconKind.rain;
        (zh, en) = switch (code) {
          61 => ('小雨', 'Slight rain'),
          63 => ('中雨', 'Moderate rain'),
          65 => ('大雨', 'Heavy rain'),
          66 => ('轻冻雨', 'Light freezing rain'),
          67 => ('强冻雨', 'Heavy freezing rain'),
          80 => ('小阵雨', 'Slight rain showers'),
          81 => ('中等阵雨', 'Moderate rain showers'),
          _ => ('强阵雨', 'Violent rain showers'),
        };
      case 71:
      case 73:
      case 75:
      case 77:
      case 85:
      case 86:
        type = SkyType.snow;
        icon = WeatherIconKind.snow;
        (zh, en) = switch (code) {
          71 => ('小雪', 'Slight snowfall'),
          73 => ('中雪', 'Moderate snowfall'),
          75 => ('大雪', 'Heavy snowfall'),
          77 => ('米雪', 'Snow grains'),
          85 => ('小阵雪', 'Slight snow showers'),
          _ => ('强阵雪', 'Heavy snow showers'),
        };
      case 95:
      case 96:
      case 99:
        type = SkyType.thunderstorm;
        icon = WeatherIconKind.thunderstorm;
        zh = switch (code) {
          95 => '雷暴',
          96 => '雷暴伴小冰雹',
          _ => '雷暴伴大冰雹',
        };
        en = switch (code) {
          95 => 'Thunderstorm',
          96 => 'Thunderstorm with slight hail',
          _ => 'Thunderstorm with heavy hail',
        };
      default:
        type = SkyType.unknown;
        icon = WeatherIconKind.unknown;
        zh = '天气未知';
        en = 'Weather unavailable';
    }
    return SkyTheme(
      type: type,
      isDay: isDay,
      nameZh: zh,
      nameEn: en,
      icon: icon,
      gradient: _palette(type, isDay),
    );
  }

  /// Unknown or missing measurements keep the neutral palette instead of
  /// pretending the sky is clear.
  static SkyTheme of(WeatherState? state) => from(
        code: state?.weatherCode ?? -1,
        isDay: state?.isDay ?? true,
      );

  static List<Color> _palette(SkyType type, bool day) {
    if (!day) {
      return switch (type) {
        SkyType.clear => const [
            Color(0xFF0A1330),
            Color(0xFF1A2A4E),
            Color(0xFF2A3C5E),
          ],
        SkyType.partlyCloudy => const [
            Color(0xFF0F1A30),
            Color(0xFF1F2F4A),
            Color(0xFF31435D),
          ],
        SkyType.cloudy => const [
            Color(0xFF181F2D),
            Color(0xFF283140),
            Color(0xFF38434F),
          ],
        SkyType.fog => const [
            Color(0xFF1D2830),
            Color(0xFF2D3C46),
            Color(0xFF3F4F59),
          ],
        SkyType.drizzle || SkyType.rain => const [
            Color(0xFF0E1826),
            Color(0xFF1B2A3E),
            Color(0xFF2A3B50),
          ],
        SkyType.snow => const [
            Color(0xFF19222F),
            Color(0xFF2A3749),
            Color(0xFF3C4C60),
          ],
        SkyType.thunderstorm => const [
            Color(0xFF141127),
            Color(0xFF241F35),
            Color(0xFF35304A),
          ],
        SkyType.unknown => const [
            Color(0xFF151E2B),
            Color(0xFF24313F),
            Color(0xFF34424F),
          ],
      };
    }
    return switch (type) {
      SkyType.clear => const [
          Color(0xFF82C4F4),
          Color(0xFFB9DEFA),
          Color(0xFFF1E8D7),
        ],
      SkyType.partlyCloudy => const [
          Color(0xFF99C9E8),
          Color(0xFFC9E2EF),
          Color(0xFFEDF0EA),
        ],
      SkyType.cloudy => const [
          Color(0xFFB3C4D4),
          Color(0xFFD0DCE5),
          Color(0xFFEBEFF3),
        ],
      SkyType.fog => const [
          Color(0xFFBACCCF),
          Color(0xFFD6E1DF),
          Color(0xFFF0F2EC),
        ],
      SkyType.drizzle || SkyType.rain => const [
          Color(0xFF8EB6D0),
          Color(0xFFBCD4E3),
          Color(0xFFE1EBF1),
        ],
      SkyType.snow => const [
          Color(0xFFB5CFED),
          Color(0xFFD8E7F6),
          Color(0xFFF5F8FC),
        ],
      SkyType.thunderstorm => const [
          Color(0xFFADA7C8),
          Color(0xFFC8C4DD),
          Color(0xFFE6E4F0),
        ],
      SkyType.unknown => const [
          Color(0xFFB3C8DD),
          Color(0xFFD3E0EB),
          Color(0xFFEEF3F7),
        ],
    };
  }
}

enum SkyType {
  clear,
  partlyCloudy,
  cloudy,
  fog,
  drizzle,
  rain,
  snow,
  thunderstorm,
  unknown,
}
