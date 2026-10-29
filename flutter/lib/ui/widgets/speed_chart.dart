import 'package:flutter/material.dart';

/// Download throughput for the last [slotCount] one-second buckets, drawn as a
/// line chart. Fixed slot count on purpose: every slot always means the same
/// amount of time, so the axis labels stay honest. Samples are right-aligned so
/// a freshly connected tunnel shows a short curve on the right instead of a
/// misleading full-width one.
class SpeedChart extends StatelessWidget {
  const SpeedChart({
    super.key,
    required this.samples,
    required this.slotCount,
    required this.lineColor,
    required this.gridColor,
    this.height = 104,
  });

  final List<int> samples;
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
            samples: samples,
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

class _SpeedChartPainter extends CustomPainter {
  const _SpeedChartPainter({
    required this.samples,
    required this.slotCount,
    required this.lineColor,
    required this.gridColor,
  });

  final List<int> samples;
  final int slotCount;
  final Color lineColor;
  final Color gridColor;

  @override
  void paint(Canvas canvas, Size size) {
    final grid = Paint()
      ..color = gridColor
      ..strokeWidth = 1;
    for (var i = 0; i <= 2; i++) {
      final y = size.height * i / 2;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), grid);
    }

    final count = samples.length;
    if (count == 0 || slotCount <= 0) return;

    var peak = 0;
    for (final value in samples) {
      if (value > peak) peak = value;
    }
    if (peak <= 0) return;

    const labelReserve = 14.0;
    final plotHeight = size.height - labelReserve;
    final slotWidth = size.width / slotCount;
    // Right-align the samples; a young tunnel has fewer buckets than slots.
    final offset = slotCount - count;

    final points = <Offset>[];
    for (var i = 0; i < count; i++) {
      final value = samples[i];
      final ratio = value / peak;
      final x = (offset + i) * slotWidth + slotWidth / 2;
      final y = plotHeight - plotHeight * 0.92 * ratio.clamp(0.0, 1.0);
      points.add(Offset(x, y));
    }

    // Subtle area fill under the line.
    final fill = Paint()
      ..color = lineColor.withAlpha(0x1F)
      ..style = PaintingStyle.fill;
    final area = Path()
      ..moveTo(points.first.dx, plotHeight)
      ..addPolygon(points, false)
      ..lineTo(points.last.dx, plotHeight)
      ..close();
    canvas.drawPath(area, fill);

    // The polyline itself.
    final line = Paint()
      ..color = lineColor
      ..strokeWidth = 2
      ..strokeJoin = StrokeJoin.round
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    final path = Path()..moveTo(points.first.dx, points.first.dy);
    for (var i = 1; i < points.length; i++) {
      path.lineTo(points[i].dx, points[i].dy);
    }
    canvas.drawPath(path, line);

    // Vertex dots + compact speed labels, skipping labels that would overlap.
    final dot = Paint()..color = lineColor;
    final labelStyle = TextStyle(
      fontSize: 8,
      color: lineColor,
      fontWeight: FontWeight.w600,
    );
    var lastLabelX = double.negativeInfinity;
    for (var i = 0; i < points.length; i++) {
      final p = points[i];
      canvas.drawCircle(p, 2.2, dot);
      final label = _shortRate(samples[i]);
      final span = TextSpan(text: label, style: labelStyle);
      final tp = TextPainter(
        text: span,
        textDirection: TextDirection.ltr,
        textAlign: TextAlign.center,
      )..layout();
      final labelX = (p.dx - tp.width / 2).clamp(0.0, size.width - tp.width);
      if (labelX - lastLabelX >= tp.width + 2) {
        final labelY = (p.dy - tp.height - 3).clamp(0.0, plotHeight);
        tp.paint(canvas, Offset(labelX, labelY));
        lastLabelX = labelX;
      }
    }
  }

  @override
  bool shouldRepaint(covariant _SpeedChartPainter oldDelegate) =>
      oldDelegate.samples != samples ||
      oldDelegate.slotCount != slotCount ||
      oldDelegate.lineColor != lineColor ||
      oldDelegate.gridColor != gridColor;
}
