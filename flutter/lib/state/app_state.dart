import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../bridge/skybox_bridge.dart';
import '../models/weather.dart';
import '../services/latency_probe.dart';
import '../services/node_location.dart';
import '../services/weather_service.dart';

/// The whole application state. The UI only reads from here and calls the
/// methods; every refresh path (1-second ticker, group stream, weather
/// callback) funnels through [notifyListeners].
class AppState extends ChangeNotifier {
  AppState({SkyboxBridge? bridge, WeatherService? weather, NodeLocation? location, LatencyProbe? latency})
      : _bridge = bridge ?? SkyboxBridge(),
        _weather = weather ?? WeatherService(),
        _location = location ?? NodeLocation(),
        _latency = latency ?? LatencyProbe();

  static const Duration tickInterval = Duration(seconds: 1);
  static const Duration latencyInterval = Duration(seconds: 30);
  static const Duration nodeRegionInterval = Duration(minutes: 5);

  final SkyboxBridge _bridge;
  final WeatherService _weather;
  final NodeLocation _location;
  final LatencyProbe _latency;

  Timer? _ticker;
  int _tick = 0;

  /// Process-wide "tunnel was up" edge, so merely reopening the app while the
  /// tunnel is already up does not re-run the region detection.
  bool _lastRunningObserved = false;

  VpnStatus status = VpnStatus.idle;
  List<VpnGroup> liveGroups = const <VpnGroup>[];
  List<VpnGroup> staticGroups = const <VpnGroup>[];
  List<int> speedHistory = const <int>[];
  List<int> uploadHistory = const <int>[];
  int sampleIntervalMs = 10000;
  int sampleCapacity = 60;
  String logs = '';
  String coreVersion = '正在读取…';
  List<ProfileSummary> profiles = const <ProfileSummary>[];
  String activeProfileId = '';
  String activeProfileName = '';

  WeatherState? weather;
  bool weatherLoading = false;

  bool busy = false;
  String? transientError;

  WeatherService get weatherService => _weather;
  NodeLocation get nodeLocation => _location;
  LatencyProbe get latencyProbe => _latency;

  bool get running => status.running;

  /// Live groups win; while the core is off the config's own selector/urltest
  /// groups stand in, so the list is never empty for no good reason.
  List<VpnGroup> get groups => liveGroups.isEmpty ? staticGroups : liveGroups;

  Future<void> start() async {
    await _weather.restore();
    _weather.onWeather = (state) {
      weather = state;
      weatherLoading = state.loading;
      notifyListeners();
    };
    _weather.onError = (message, {required citySearch}) {
      if (!citySearch) transientError = message;
      notifyListeners();
    };
    await refreshProfiles();
    await refreshCore();
    unawaited(_weather.fetch(force: false));
    _ticker?.cancel();
    _ticker = Timer.periodic(tickInterval, (_) => _tickOnce());
  }

  @override
  void dispose() {
    _ticker?.cancel();
    _weather.close();
    _location.close();
    _latency.close();
    super.dispose();
  }

  Future<void> refreshCore() async {
    coreVersion = await _bridge.coreVersion();
    sampleIntervalMs = await _bridge.sampleIntervalMs();
    sampleCapacity = await _bridge.sampleCapacity();
    notifyListeners();
  }

  Future<void> refreshProfiles() async {
    profiles = await _bridge.profiles();
    activeProfileId = await _bridge.activeId();
    activeProfileName = await _bridge.activeName();
    await _refreshStaticGroups();
    notifyListeners();
  }

  Future<void> _refreshStaticGroups() async {
    if (activeProfileId.isEmpty) {
      staticGroups = const <VpnGroup>[];
      return;
    }
    final config = await _bridge.readProfile(activeProfileId);
    staticGroups = parseStaticGroups(config);
  }

  /// Selector/urltest groups straight from the active config, shown while the
  /// core is off. Kept here rather than in the host: it is pure JSON.
  static List<VpnGroup> parseStaticGroups(String config) {
    if (config.trim().isEmpty) return const <VpnGroup>[];
    final dynamic document = jsonDecode(config);
    if (document is! Map<String, dynamic>) return const <VpnGroup>[];
    final outbounds = document['outbounds'];
    if (outbounds is! List) return const <VpnGroup>[];
    final result = <VpnGroup>[];
    for (final entry in outbounds) {
      if (entry is! Map<String, dynamic>) continue;
      final type = entry['type'] as String? ?? '';
      if (type != 'selector' && type != 'urltest') continue;
      final tag = entry['tag'] as String? ?? '';
      if (tag.isEmpty) continue;
      final members = <VpnGroupItem>[];
      final list = entry['outbounds'];
      if (list is List) {
        for (final member in list) {
          if (member is String && member.isNotEmpty) {
            members.add(VpnGroupItem(tag: member, type: '', delay: 0));
          }
        }
      }
      result.add(VpnGroup(
        tag: tag,
        type: type,
        selectable: type == 'selector',
        selected: '',
        items: members,
      ));
    }
    return result;
  }

  Future<void> _tickOnce() async {
    _tick++;
    final previous = status;
    status = await _bridge.status();
    speedHistory = running ? await _bridge.speedHistory() : const <int>[];
    uploadHistory = running ? await _bridge.uploadHistory() : const <int>[];
    await _keepReadingsFresh();
    if (status.groupsRevision != previous.groupsRevision || running != previous.running) {
      liveGroups = running ? await _bridge.groups() : const <VpnGroup>[];
    }
    notifyListeners();
  }

  /// One latency probe and one node-region probe on their own cadences, plus an
  /// immediate pair the moment the tunnel comes up.
  Future<void> _keepReadingsFresh() async {
    final running = status.running;
    if (running && !_lastRunningObserved) {
      unawaited(_latency.test());
      unawaited(refreshNodeRegion(manual: false));
    }
    _lastRunningObserved = running;
    if (!running) return;
    if (_tick % latencyInterval.inSeconds == 0 && !_latency.testing) {
      unawaited(_latency.test(complete: notifyListeners));
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    if (!_location.probing && now - _location.lastAttemptAt > nodeRegionInterval.inMilliseconds) {
      unawaited(refreshNodeRegion(manual: false));
    }
  }

  /// manual=true always reports a failure; otherwise the throttle decides.
  Future<void> refreshNodeRegion({required bool manual}) async {
    if (_location.probing) return;
    await _location.probe(running: running);
    if (_location.routeChanged) {
      if (manual) transientError = '网络刚切换，稍后重新检测节点地区';
      notifyListeners();
      return;
    }
    final node = _location.last;
    if (node == null || !node.isValid) {
      if (manual) transientError = '节点地区检测失败：${_location.lastError}';
      notifyListeners();
      return;
    }
    // Last line of defence: never attach a reading to the wrong route.
    if (_location.fromNode != running) return;
    if (!_weather.isManual) {
      final name = node.city.isNotEmpty ? node.city : node.country;
      final city = WeatherCity(
        displayName: name.isEmpty ? '节点地区' : name,
        country: node.country,
        latitude: node.latitude,
        longitude: node.longitude,
        timezone: node.timezone.isEmpty ? 'auto' : node.timezone,
      );
      await _weather.setCity(city, manual: false);
    }
    notifyListeners();
  }

  Future<void> refreshLogs() async {
    logs = await _bridge.logs();
    notifyListeners();
  }

  Future<void> connect() async {
    if (busy || running) return;
    busy = true;
    notifyListeners();
    try {
      await _bridge.start();
    } catch (error) {
      transientError = bridgeMessage(error);
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  Future<void> disconnect() async {
    try {
      await _bridge.stop();
    } catch (error) {
      transientError = bridgeMessage(error);
      notifyListeners();
    }
  }

  Future<void> selectOutbound(String group, String outbound) async {
    try {
      await _bridge.selectOutbound(group, outbound);
    } catch (error) {
      transientError = bridgeMessage(error);
      notifyListeners();
    }
  }

  Future<void> urlTestGroup(VpnGroup group) async {
    for (final item in group.items) {
      await _bridge.urlTest(item.tag);
    }
  }

  Future<void> saveProfile({String? id, String? name, required String config}) async {
    try {
      await _bridge.saveProfile(id: id, name: name, config: config);
      await refreshProfiles();
    } catch (error) {
      transientError = bridgeMessage(error);
      notifyListeners();
    }
  }

  Future<void> deleteProfile(String id) async {
    try {
      await _bridge.deleteProfile(id);
      await refreshProfiles();
    } catch (error) {
      transientError = bridgeMessage(error);
      notifyListeners();
    }
  }

  Future<void> selectProfile(String id) async {
    try {
      await _bridge.selectProfile(id);
      await refreshProfiles();
    } catch (error) {
      transientError = bridgeMessage(error);
      notifyListeners();
    }
  }

  Future<void> importFromSystem() async {
    final picked = await _bridge.pickConfig();
    if (picked == null) return;
    transientError = null;
    importedConfig = picked;
    notifyListeners();
  }

  /// Set for one frame by [importFromSystem]; the editor consumes and clears it.
  ({String name, String config})? importedConfig;

  void clearTransientError() {
    transientError = null;
    notifyListeners();
  }

  void clearImported() {
    importedConfig = null;
  }
}
