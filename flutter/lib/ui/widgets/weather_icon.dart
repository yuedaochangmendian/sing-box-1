import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../theme/sky_theme.dart';

/// Weather glyph drawn with paths instead of bitmap assets, so it takes the
/// palette's own colour and scales to any size without extra resources.
class WeatherIcon extends StatelessWidget {
  const WeatherIcon({
    super.key,
    required this.kind,
    required this.color,
    this.size = 28,
  });

  final WeatherIconKind kind;
  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) => SizedBox(
        width: size,
        height: size,
        child: CustomPaint(
          painter: _WeatherIconPainter(kind: kind, color: color),
        ),
      );
}

class _WeatherIconPainter extends CustomPainter {
  const _WeatherIconPainter({required this.kind, required this.color});

  final WeatherIconKind kind;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.fill
      ..isAntiAlias = true;
    final stroke = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = size.width * 0.055
      ..strokeCap = StrokeCap.round
      ..isAntiAlias = true;
    final w = size.width;
    final h = size.height;

    switch (kind) {
      case WeatherIconKind.sun:
        canvas.drawCircle(Offset(w * 0.5, h * 0.5), w * 0.26, paint);
        for (var i = 0; i < 8; i++) {
          final angle = i * 3.141592653589793 / 4;
          final inner = w * 0.36;
          final outer = w * 0.47;
          canvas.drawLine(
            Offset(w / 2 + inner * math.cos(angle), h / 2 + inner * math.sin(angle)),
            Offset(w / 2 + outer * math.cos(angle), h / 2 + outer * math.sin(angle)),
            stroke,
          );
        }
      case WeatherIconKind.moon:
        final path = Path()
          ..moveTo(w * 0.62, h * 0.16)
          ..arcTo(
            Rect.fromCircle(center: Offset(w * 0.46, h * 0.5), radius: w * 0.36),
            -1.0,
            5.2,
            false,
          )
          ..arcTo(
            Rect.fromCircle(center: Offset(w * 0.62, h * 0.30), radius: w * 0.30),
            1.7,
            -4.4,
            false,
          )
          ..close();
        canvas.drawPath(path, paint);
      case WeatherIconKind.cloud:
        _cloud(canvas, size, paint, top: h * 0.40);
      case WeatherIconKind.partlyCloudy:
        canvas.drawCircle(Offset(w * 0.33, h * 0.34), w * 0.19, paint);
        _cloud(canvas, size, paint, top: h * 0.46, left: w * 0.38, scale: 0.92);
      case WeatherIconKind.cloudNight:
        final path = Path()
          ..moveTo(w * 0.34, h * 0.10)
          ..arcTo(
            Rect.fromCircle(center: Offset(w * 0.24, h * 0.32), radius: w * 0.24),
            -1.0,
            5.2,
            false,
          )
          ..arcTo(
            Rect.fromCircle(center: Offset(w * 0.36, h * 0.18), radius: w * 0.20),
            1.7,
            -4.4,
            false,
          )
          ..close();
        canvas.drawPath(path, paint);
        _cloud(canvas, size, paint, top: h * 0.48, left: w * 0.34, scale: 0.92);
      case WeatherIconKind.fog:
        _cloud(canvas, size, paint, top: h * 0.30, scale: 0.86);
        for (var i = 0; i < 3; i++) {
          final y = h * (0.66 + i * 0.12);
          canvas.drawLine(
            Offset(w * (0.20 + (i == 1 ? 0.06 : 0)), y),
            Offset(w * (i == 1 ? 0.74 : 0.80), y),
            stroke,
          );
        }
      case WeatherIconKind.rain:
        _cloud(canvas, size, paint, top: h * 0.26, scale: 0.86);
        for (var i = 0; i < 3; i++) {
          final x = w * (0.30 + i * 0.20);
          canvas.drawLine(
            Offset(x, h * 0.62),
            Offset(x - w * 0.05, h * 0.86),
            stroke,
          );
        }
      case WeatherIconKind.snow:
        _cloud(canvas, size, paint, top: h * 0.26, scale: 0.86);
        for (var i = 0; i < 3; i++) {
          final center = Offset(w * (0.28 + i * 0.22), h * 0.76);
          canvas.drawCircle(center, w * 0.055, paint);
        }
      case WeatherIconKind.thunderstorm:
        _cloud(canvas, size, paint, top: h * 0.24, scale: 0.84);
        final bolt = Path()
          ..moveTo(w * 0.54, h * 0.58)
          ..lineTo(w * 0.40, h * 0.78)
          ..lineTo(w * 0.52, h * 0.78)
          ..lineTo(w * 0.44, h * 0.96)
          ..lineTo(w * 0.64, h * 0.70)
          ..lineTo(w * 0.51, h * 0.70)
          ..close();
        canvas.drawPath(bolt, paint);
      case WeatherIconKind.unknown:
        canvas.drawCircle(Offset(w * 0.5, h * 0.5), w * 0.30, stroke);
        canvas.drawLine(
          Offset(w * 0.30, h * 0.30),
          Offset(w * 0.70, h * 0.70),
          stroke,
        );
    }
  }

  void _cloud(
    Canvas canvas,
    Size size,
    Paint paint, {
    required double top,
    double left = 0,
    double scale = 1,
  }) {
    final w = size.width * scale;
    final h = size.height * scale;
    final x = left == 0 ? size.width * 0.5 : left + w * 0.28;
    final center = Offset(x, top + h * 0.16);
    canvas.drawCircle(center, w * 0.20, paint);
    canvas.drawCircle(center.translate(-w * 0.20, h * 0.06), w * 0.15, paint);
    canvas.drawCircle(center.translate(w * 0.21, h * 0.05), w * 0.14, paint);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(x - w * 0.34, top + h * 0.04, w * 0.68, h * 0.28),
        Radius.circular(w * 0.14),
      ),
      paint,
    );
  }

  @override
  bool shouldRepaint(covariant _WeatherIconPainter oldDelegate) =>
      oldDelegate.kind != kind || oldDelegate.color != color;
}
