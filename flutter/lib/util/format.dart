import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Human-readable byte counts and rates, matching the previous native labels.
String formatBytes(int value) {
  if (value < 0) return '—';
  if (value < 1024) return '$value B';
  if (value < 1024 * 1024) return '${(value / 1024).toStringAsFixed(1)} KB';
  if (value < 1024 * 1024 * 1024) {
    return '${(value / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
  return '${(value / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
}

String formatRate(int value) => value < 0 ? '—' : '${formatBytes(value)}/s';

/// Weighted mix of two colours; used for the card and dock surfaces.
Color mixColor(Color from, Color to, double amount) {
  int channel(double a, double b) => (a * (1 - amount) + b * amount).round();
  return Color.fromARGB(
    255,
    channel(from.r * 255.0, to.r * 255.0),
    channel(from.g * 255.0, to.g * 255.0),
    channel(from.b * 255.0, to.b * 255.0),
  );
}

/// Rounds to the nearest integer and drops "-0".
String roundCelsius(double value) =>
    value.isNaN || value.isInfinite ? '—' : '${value.round()}';

double safeMax(List<double> values) {
  var result = double.nan;
  for (final value in values) {
    if (!value.isFinite) continue;
    if (result.isNaN || value > result) result = value;
  }
  return result;
}

double safeMin(List<double> values) {
  var result = double.nan;
  for (final value in values) {
    if (!value.isFinite) continue;
    if (result.isNaN || value < result) result = value;
  }
  return result;
}

/// True only for values that survived the provider's null/missing markers.
bool isUsable(double value) => value.isFinite;

double lerpDoubleValue(double a, double b, double t) => a + (b - a) * t;

double clampDoubleValue(double value, double min, double max) =>
    math.min(max, math.max(min, value));
