import 'package:flutter/material.dart';

/// Download throughput for the last [slotCount] × 10 s buckets.
///
/// Fixed slot count on purpose: the axis labels (10分钟前 / 5分钟前 / 现在) only
/// stay honest when every slot always means the same amount of time. Samples
/// are right-aligned so the newest bucket sits on the "现在" edge, which means a
/// freshly connected tunnel shows a short curve on the right instead of a
/// misleading full-width one.
class SpeedChart extends StatelessWidget {
  const SpeedChart({
    super.key,
    required this.samples,
    required this.slotCount,
    required this.separatorSlots,
    required this.barColor,
    required this.barLowColor,
    required this.gridColor,
    this.height = 92,
  });

  final List<int> samples;
  final int slotCount;
  final int separatorSlots;
  final Color barColor;
  final Color barLowColor;
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
            separatorSlots: separatorSlots,
            barColor: barColor,
            barLowColor: barLowColor,
            gridColor: gridColor,
          ),
        ),
      );
}

class _SpeedChartPainter extends CustomPainter {
  const _SpeedChartPainter({
    required this.samples,
    required this.slotCount,
    required this.separatorSlots,
    required this.barColor,
    required this.barLowColor,
    required this.gridColor,
  });

  final List<int> samples;
  final int slotCount;
  final int separatorSlots;
  final Color barColor;
  final Color barLowColor;
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

    final slotWidth = size.width / slotCount;
    final barWidth = (slotWidth * 0.62).clamp(1.0, 10.0);
    // Right-align the samples; a young tunnel has fewer buckets than slots.
    final offset = slotCount - count;
    final paint = Paint()..isAntiAlias = true;

    for (var i = 0; i < count; i++) {
      final value = samples[i];
      final ratio = value / peak;
      final barHeight = (size.height * 0.9 * ratio).clamp(1.0, size.height * 0.9);
      final x = (offset + i) * slotWidth + (slotWidth - barWidth) / 2;
      final top = size.height - barHeight;
      final rect = RRect.fromRectAndRadius(
        Rect.fromLTWH(x, top, barWidth, barHeight),
        Radius.circular(barWidth / 2),
      );
      // The lower third of the range reads as "slow" and gets the muted colour.
      paint.color = ratio < 0.34 ? barLowColor : barColor;
      canvas.drawRRect(rect, paint);
    }

    if (separatorSlots <= 0 || separatorSlots >= slotCount) return;
    final marker = Paint()
      ..color = gridColor
      ..strokeWidth = 1;
    final x = (slotCount - separatorSlots) * slotWidth;
    canvas.drawLine(Offset(x, 0), Offset(x, size.height), marker);
  }

  @override
  bool shouldRepaint(covariant _SpeedChartPainter oldDelegate) =>
      oldDelegate.samples != samples ||
      oldDelegate.slotCount != slotCount ||
      oldDelegate.barColor != barColor ||
      oldDelegate.gridColor != gridColor;
}
