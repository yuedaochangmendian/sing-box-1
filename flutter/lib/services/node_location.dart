import 'dart:convert';

import 'package:http/http.dart' as http;

/// Exit-IP geolocation. Runs inside the tunnel when the VPN is up, which is
/// exactly what makes it the node region rather than the device region.
class NodeLocation {
  NodeLocation({http.Client? client}) : _client = client ?? http.Client();

  static const Duration timeout = Duration(seconds: 8);

  final http.Client _client;

  Location? last;
  String lastError = '';
  int lastAttemptAt = 0;
  bool probing = false;

  /// True when [last] describes the tunnel route (as opposed to the device's
  /// own route), so a reading is never attached to the wrong path.
  bool fromNode = false;

  /// Set when the tunnel went up or down while a request was in flight; the
  /// answer then describes the other route and must be dropped.
  bool routeChanged = false;

  Future<void> probe({required bool running, void Function()? complete}) async {
    if (probing) {
      complete?.call();
      return;
    }
    probing = true;
    routeChanged = false;
    lastAttemptAt = DateTime.now().millisecondsSinceEpoch;
    final wasRunning = running;
    try {
      final result = await _request();
      if (result == null) {
        lastError = '未获得有效的节点地区';
      } else {
        last = result;
        fromNode = wasRunning;
        lastError = '';
      }
      // The tunnel state changed mid-request: the answer describes the other route.
      if (wasRunning != running) {
        routeChanged = true;
        last = null;
      }
    } catch (error) {
      lastError = error.toString().replaceFirst('Exception: ', '');
    } finally {
      probing = false;
      complete?.call();
    }
  }

  Future<Location?> _request() async {
    final ipwho = await _tryIpwho();
    if (ipwho != null) return ipwho;
    return _tryIpapi();
  }

  Future<Location?> _tryIpwho() async {
    try {
      final response = await _client
          .get(Uri.https('ipwho.is', '/'))
          .timeout(timeout);
      if (response.statusCode != 200) return null;
      final json = jsonDecode(response.body) as Map<String, dynamic>;
      if (json['success'] == false) return null;
      final timezone = json['timezone'];
      final location = Location(
        ip: json['ip'] as String? ?? '',
        country: json['country'] as String? ?? '',
        city: json['city'] as String? ?? '',
        latitude: _number(json['latitude']),
        longitude: _number(json['longitude']),
        timezone: timezone is Map ? timezone['id'] as String? ?? '' : '',
      );
      return location.isValid ? location : null;
    } catch (_) {
      return null;
    }
  }

  Future<Location?> _tryIpapi() async {
    try {
      final response = await _client
          .get(Uri.https('ipapi.co', '/json/'))
          .timeout(timeout);
      if (response.statusCode != 200) return null;
      final json = jsonDecode(response.body) as Map<String, dynamic>;
      final location = Location(
        ip: json['ip'] as String? ?? '',
        country: json['country_name'] as String? ?? '',
        city: json['city'] as String? ?? '',
        latitude: _number(json['latitude']),
        longitude: _number(json['longitude']),
        timezone: json['timezone'] as String? ?? '',
      );
      return location.isValid ? location : null;
    } catch (_) {
      return null;
    }
  }

  void close() => _client.close();

  static double _number(Object? value) {
    if (value is num) return value.toDouble();
    if (value is String) return double.tryParse(value) ?? double.nan;
    return double.nan;
  }
}

class Location {
  const Location({
    required this.ip,
    required this.country,
    required this.city,
    required this.latitude,
    required this.longitude,
    required this.timezone,
  });

  final String ip;
  final String country;
  final String city;
  final double latitude;
  final double longitude;
  final String timezone;

  bool get isValid =>
      latitude.isFinite &&
      longitude.isFinite &&
      latitude.abs() <= 90 &&
      longitude.abs() <= 180 &&
      (city.isNotEmpty || country.isNotEmpty);

  String get displayName {
    if (city.isNotEmpty && country.isNotEmpty) return '$city · $country';
    if (city.isNotEmpty) return city;
    if (country.isNotEmpty) return country;
    return '节点地区';
  }
}
