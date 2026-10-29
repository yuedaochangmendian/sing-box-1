import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Two throughput bands (download on top with value labels, upload below) for
/// the last [slotCount] one-second buckets, drawn in the "sky chart" style: a
/// smooth spline curve, white ring dots at each vertex, a short dashed lead-in
/// before the first point, and the rate labeled above each download vertex.
/// Each band scales to its own peak so a small upload stream stays visible.
/// Samples are right-aligned so a freshly connected tunnel shows a short curve
/// on the right instead of a misleading full-width one.
class SpeedChart extends StatelessWidget {
  const SpeedChart({
    super.key,
    required this.downSamples,
    required this.upSamples,
    required this.slotCount,
    required this.lineColor,
    required this.gridColor,
    this.height = 156,
  });

  final List<int> downSamples;
  final List<int> upSamples;
  final int slotCount;
  final Color lineColor;
  final Color gridColor;
  final double height;

  @override
  Widget build(BuildContext context) => SizedBox(
        height: height,
        width: double.infinity,
        child: CustomPaint(
          painter: _SpeedChartPainter(
            downSamples: downSamples,
            upSamples: upSamples,
            slotCount: slotCount,
            lineColor: lineColor,
            gridColor: gridColor,
          ),
        ),
      );
}

String _shortRate(int value) {
  if (value <= 0) return '0';
  if (value < 1024) return '${value}B';
  if (value < 1024 * 1024) return '${(value / 1024).round()}K';
  if (value < 1024 * 1024 * 1024) {
    return '${(value / (1024 * 1024)).toStringAsFixed(1)}M';
  }
  return '${(value / (1024 * 1024 * 1024)).toStringAsFixed(1)}G';
}

/// Catmull-Rom spline through [pts] expressed as cubic Beziers, so the curve is
/// smooth and rounded at the vertices (the flowing "sky chart" look) instead of
/// the sharp polyline of a plain line chart.
Path _smoothPath(List<Offset> pts) {
  final path = Path();
  if (pts.isEmpty) return path;
  if (pts.length == 1) {
    path.moveTo(pts[0].dx, pts[0].dy);
    return path;
  }
  path.moveTo(pts[0].dx, pts[0].dy);
  for (var i = 0; i < pts.length - 1; i++) {
    final p0 = pts[i == 0 ? 0 : i - 1];
    final p1 = pts[i];
    final p2 = pts[i + 1];
    final p3 = pts[i + 2 < pts.length ? i + 2 : pts.length - 1];
    final c1 = Offset(p1.dx + (p2.dx - p0.dx) / 6, p1.dy + (p2.dy - p0.dy) / 6);
    final c2 = Offset(p2.dx - (p3.dx - p1.dx) / 6, p2.dy - (p3.dy - p1.dy) / 6);
    path.cubicTo(c1.dx, c1.dy, c2.dx, c2.dy, p2.dx, p2.dy);
  }
  return path;
}

class _SpeedChartPainter extends CustomPainter {
  const _SpeedChartPainter({
    required this.downSamples,
    required this.upSamples,
    required this.slotCount,
    required this.lineColor,
    required this.gridColor,
  });

  final List<int> downSamples;
  final List<int> upSamples;
  final int slotCount;
  final Color lineColor;
  final Color gridColor;

  @override
  void paint(Canvas canvas, Size size) {
    const gap = 12.0;
    final topH = (size.height - gap) * 0.58;
    final bottomH = (size.height - gap) * 0.42;
    _drawBand(
      canvas,
      rect: Rect.fromLTWH(0, 0, size.width, topH),
      samples: downSamples,
      showLabels: true,
      color: lineColor,
    );
    _drawBand(
      canvas,
      rect: Rect.fromLTWH(0, topH + gap, size.width, bottomH),
      samples: upSamples,
      showLabels: false,
      color: lineColor.withAlpha(0xB0),
    );
  }

  void _drawBand(
    Canvas canvas, {
    required Rect rect,
    required List<int> samples,
    required bool showLabels,
    required Color color,
  }) {
    // Faint grid: top and bottom of the band.
    final grid = Paint()
      ..color = gridColor
      ..strokeWidth = 1;
    for (var i = 0; i <= 1; i++) {
      final y = rect.top + rect.height * i;
      canvas.drawLine(Offset(rect.left, y), Offset(rect.right, y), grid);
    }

    final count = samples.length;
    if (count == 0 || slotCount <= 0) return;

    var peak = 0;
    for (final value in samples) {
      if (value > peak) peak = value;
    }
    if (peak <= 0) return;

    final labelReserve = showLabels ? 14.0 : 0.0;
    final plotTop = rect.top + labelReserve;
    final plotHeight = rect.height - labelReserve;
    final slotWidth = rect.width / slotCount;
    // Right-align the samples; a young tunnel has fewer buckets than slots.
    final offset = slotCount - count;

    final points = <Offset>[];
    for (var i = 0; i < count; i++) {
      final ratio = samples[i] / peak;
      final x = rect.left + (offset + i) * slotWidth + slotWidth / 2;
      final y = plotTop + plotHeight - plotHeight * 0.92 * ratio.clamp(0.0, 1.0);
      points.add(Offset(x, y));
    }

    // Smooth flowing curve through the vertices.
    final curve = _smoothPath(points);

    // Very light area fill under the curve.
    if (points.length > 1) {
      final fill = Paint()
        ..color = color.withAlpha(0x12)
        ..style = PaintingStyle.fill;
      final area = Path.from(curve)
        ..lineTo(points.last.dx, plotTop + plotHeight)
        ..lineTo(points.first.dx, plotTop + plotHeight)
        ..close();
      canvas.drawPath(area, fill);
    }

    // The line itself: rounded, semi-transparent.
    final line = Paint()
      ..color = color
      ..strokeWidth = 2.5
      ..strokeJoin = StrokeJoin.round
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    canvas.drawPath(curve, line);

    // Dashed lead-in before the first vertex (the reference's fade-in edge).
    final first = points.first;
    final lead = math.min(14.0, first.dx - rect.left - 3);
    if (lead > 3) {
      final dash = Paint()
        ..color = color.withAlpha(0x80)
        ..strokeWidth = 2.5
        ..strokeCap = StrokeCap.round
        ..style = PaintingStyle.stroke;
      const dashLen = 4.0, gapLen = 3.5;
      var x = first.dx - lead;
      while (x < first.dx - 1) {
        final endX = math.min(x + dashLen, first.dx);
        canvas.drawLine(Offset(x, first.dy), Offset(endX, first.dy), dash);
        x += dashLen + gapLen;
      }
    }

    // White ring dots at each vertex + (on the download band) the rate label.
    final dotRing = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2;
    final dotFill = Paint()..color = Colors.white;
    final labelStyle = TextStyle(
      fontSize: 8,
      color: color,
      fontWeight: FontWeight.w600,
    );
    var lastLabelX = double.negativeInfinity;
    for (var i = 0; i < points.length; i++) {
      final p = points[i];
      canvas.drawCircle(p, 2.6, dotFill);
      canvas.drawCircle(p, 2.6, dotRing);
      if (!showLabels) continue;
      final label = _shortRate(samples[i]);
      final tp = TextPainter(
        text: TextSpan(text: label, style: labelStyle),
        textDirection: TextDirection.ltr,
        textAlign: TextAlign.center,
      )..layout();
      final labelX = (p.dx - tp.width / 2).clamp(rect.left, rect.right - tp.width);
      if (labelX - lastLabelX >= tp.width + 2) {
        final labelY = (p.dy - tp.height - 3).clamp(rect.top, plotTop + plotHeight);
        tp.paint(canvas, Offset(labelX, labelY));
        lastLabelX = labelX;
      }
    }
  }

  @override
  bool shouldRepaint(covariant _SpeedChartPainter oldDelegate) =>
      oldDelegate.downSamples != downSamples ||
      oldDelegate.upSamples != upSamples ||
      oldDelegate.slotCount != slotCount ||
      oldDelegate.lineColor != lineColor ||
      oldDelegate.gridColor != gridColor;
}
