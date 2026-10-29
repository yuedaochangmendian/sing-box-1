import 'package:flutter/material.dart';

/// One forecast day as a horizontal range bar: the track spans the week's
/// overall minimum and maximum, the filled segment is this day's own range and
/// the dot is today's current reading.
class TemperatureRangeBar extends StatelessWidget {
  const TemperatureRangeBar({
    super.key,
    required this.min,
    required this.max,
    required this.lowest,
    required this.highest,
    required this.current,
    required this.fillColor,
    required this.trackColor,
    required this.dotColor,
    this.height = 6,
  });

  final double min;
  final double max;
  final double lowest;
  final double highest;
  final double current;
  final Color fillColor;
  final Color trackColor;
  final Color dotColor;
  final double height;

  @override
  Widget build(BuildContext context) => SizedBox(
        height: height + 8,
        width: double.infinity,
        child: CustomPaint(
          painter: _RangePainter(
            min: min,
            max: max,
            lowest: lowest,
            highest: highest,
            current: current,
            fillColor: fillColor,
            trackColor: trackColor,
            dotColor: dotColor,
            height: height,
          ),
        ),
      );
}

class _RangePainter extends CustomPainter {
  const _RangePainter({
    required this.min,
    required this.max,
    required this.lowest,
    required this.highest,
    required this.current,
    required this.fillColor,
    required this.trackColor,
    required this.dotColor,
    required this.height,
  });

  final double min;
  final double max;
  final double lowest;
  final double highest;
  final double current;
  final Color fillColor;
  final Color trackColor;
  final Color dotColor;
  final double height;

  @override
  void paint(Canvas canvas, Size size) {
    final span = highest - lowest;
    final top = (size.height - height) / 2;
    final track = RRect.fromRectAndRadius(
      Rect.fromLTWH(0, top, size.width, height),
      Radius.circular(height / 2),
    );
    canvas.drawRRect(track, Paint()..color = trackColor);

    if (!min.isFinite || !max.isFinite || !lowest.isFinite || !highest.isFinite) return;
    final unit = span <= 0 ? 0.0 : size.width / span;
    final start = span <= 0 ? 0.0 : (min - lowest) * unit;
    final end = span <= 0 ? size.width : (max - lowest) * unit;
    final fill = RRect.fromRectAndRadius(
      Rect.fromLTWH(start.clamp(0.0, size.width), top,
          (end - start).clamp(2.0, size.width), height),
      Radius.circular(height / 2),
    );
    canvas.drawRRect(fill, Paint()..color = fillColor);

    if (!current.isFinite || span <= 0) return;
    final x = ((current - lowest) * unit).clamp(0.0, size.width);
    canvas.drawCircle(
      Offset(x, top + height / 2),
      height * 0.85,
      Paint()..color = dotColor,
    );
  }

  @override
  bool shouldRepaint(covariant _RangePainter oldDelegate) =>
      oldDelegate.min != min ||
      oldDelegate.max != max ||
      oldDelegate.lowest != lowest ||
      oldDelegate.highest != highest ||
      oldDelegate.current != current;
}
