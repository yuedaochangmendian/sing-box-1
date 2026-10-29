import 'package:http/http.dart' as http;

/// Tunnel latency, measured the same way as before: a 204 from Google's
/// generate_204 endpoint, timed end to end. It runs through the VPN, so the
/// number describes the tunnel rather than the raw network.
class LatencyProbe {
  LatencyProbe({http.Client? client}) : _client = client ?? http.Client();

  static const Duration timeout = Duration(seconds: 8);

  final http.Client _client;

  int milliseconds = -1;
  int fastest = -1;
  int slowest = -1;
  bool testing = false;
  String failure = '';

  Future<void> test({void Function()? complete}) async {
    if (testing) {
      complete?.call();
      return;
    }
    testing = true;
    try {
      final stopwatch = Stopwatch()..start();
      final response = await _client
          .get(Uri.https('www.gstatic.com', '/generate_204'))
          .timeout(timeout);
      stopwatch.stop();
      if (response.statusCode != 204) {
        throw Exception('HTTP ${response.statusCode}');
      }
      milliseconds = stopwatch.elapsedMilliseconds;
      fastest = fastest < 0 ? milliseconds : (milliseconds < fastest ? milliseconds : fastest);
      slowest = milliseconds > slowest ? milliseconds : slowest;
      failure = '';
    } catch (error) {
      milliseconds = -1;
      failure = error.toString().replaceFirst('Exception: ', '');
    } finally {
      testing = false;
      complete?.call();
    }
  }

  void close() => _client.close();
}
