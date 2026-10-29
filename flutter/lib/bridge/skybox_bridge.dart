import 'package:flutter/services.dart';

/// One outbound group as reported by the core.
class VpnGroup {
  const VpnGroup({
    required this.tag,
    required this.type,
    required this.selectable,
    required this.selected,
    required this.items,
  });

  final String tag;
  final String type;
  final bool selectable;
  final String selected;
  final List<VpnGroupItem> items;

  factory VpnGroup.fromMap(Map<dynamic, dynamic> map) => VpnGroup(
        tag: map['tag'] as String? ?? '',
        type: map['type'] as String? ?? '',
        selectable: map['selectable'] == true,
        selected: map['selected'] as String? ?? '',
        items: ((map['items'] as List?) ?? const <dynamic>[])
            .whereType<Map<dynamic, dynamic>>()
            .map(VpnGroupItem.fromMap)
            .toList(),
      );
}

class VpnGroupItem {
  const VpnGroupItem({required this.tag, required this.type, required this.delay});

  final String tag;
  final String type;
  final int delay;

  factory VpnGroupItem.fromMap(Map<dynamic, dynamic> map) => VpnGroupItem(
        tag: map['tag'] as String? ?? '',
        type: map['type'] as String? ?? '',
        delay: map['delay'] is num ? (map['delay'] as num).toInt() : 0,
      );
}

/// Live connection snapshot; mirrors the fields the service publishes.
class VpnStatus {
  const VpnStatus({
    required this.running,
    required this.state,
    required this.error,
    required this.downlink,
    required this.uplink,
    required this.downlinkTotal,
    required this.uplinkTotal,
    required this.groupsRevision,
  });

  final bool running;
  final String state;
  final String error;
  final int downlink;
  final int uplink;
  final int downlinkTotal;
  final int uplinkTotal;
  final int groupsRevision;

  factory VpnStatus.fromMap(Map<dynamic, dynamic> map) => VpnStatus(
        running: map['running'] == true,
        state: map['state'] as String? ?? '',
        error: map['error'] as String? ?? '',
        downlink: _int(map['downlink']),
        uplink: _int(map['uplink']),
        downlinkTotal: _int(map['downlinkTotal']),
        uplinkTotal: _int(map['uplinkTotal']),
        groupsRevision: _int(map['groupsRevision']),
      );

  static const VpnStatus idle = VpnStatus(
    running: false,
    state: '已断开',
    error: '',
    downlink: 0,
    uplink: 0,
    downlinkTotal: 0,
    uplinkTotal: 0,
    groupsRevision: 0,
  );

  static int _int(Object? value) => value is num ? value.toInt() : 0;
}

class ProfileSummary {
  const ProfileSummary({required this.id, required this.name, required this.modified});

  final String id;
  final String name;
  final int modified;

  factory ProfileSummary.fromMap(Map<dynamic, dynamic> map) => ProfileSummary(
        id: map['id'] as String? ?? '',
        name: map['name'] as String? ?? '',
        modified: map['modified'] is num ? (map['modified'] as num).toInt() : 0,
      );
}

/// Everything the Flutter side needs from the Android host. The kernel itself
/// (a gomobile archive), the VpnService and the profile files stay there.
class SkyboxBridge {
  SkyboxBridge() : _channel = const MethodChannel('skybox/core');

  final MethodChannel _channel;

  Future<VpnStatus> status() async {
    final map = await _channel.invokeMapMethod<String, dynamic>('status');
    return map == null ? VpnStatus.idle : VpnStatus.fromMap(map);
  }

  Future<String> logs() async => await _channel.invokeMethod<String>('logs') ?? '';

  Future<List<int>> speedHistory() async {
    final values = await _channel.invokeListMethod<dynamic>('speedHistory');
    return (values ?? const <dynamic>[])
        .map((value) => value is num ? value.toInt() : 0)
        .toList();
  }

  Future<List<int>> uploadHistory() async {
    final values = await _channel.invokeListMethod<dynamic>('uploadHistory');
    return (values ?? const <dynamic>[])
        .map((value) => value is num ? value.toInt() : 0)
        .toList();
  }

  Future<int> sampleIntervalMs() async =>
      await _channel.invokeMethod<int>('sampleIntervalMs') ?? 10000;

  Future<int> sampleCapacity() async =>
      await _channel.invokeMethod<int>('sampleCapacity') ?? 60;

  Future<String> coreVersion() async =>
      await _channel.invokeMethod<String>('coreVersion') ?? '正在读取…';

  Future<List<VpnGroup>> groups() async {
    final values = await _channel.invokeListMethod<dynamic>('groups');
    return (values ?? const <dynamic>[])
        .whereType<Map<dynamic, dynamic>>()
        .map(VpnGroup.fromMap)
        .toList();
  }

  Future<List<ProfileSummary>> profiles() async {
    final values = await _channel.invokeListMethod<dynamic>('profiles');
    return (values ?? const <dynamic>[])
        .whereType<Map<dynamic, dynamic>>()
        .map(ProfileSummary.fromMap)
        .toList();
  }

  Future<String> activeId() async =>
      await _channel.invokeMethod<String>('activeId') ?? '';

  Future<String> activeName() async =>
      await _channel.invokeMethod<String>('activeName') ?? '';

  Future<String> activeConfig() async =>
      await _channel.invokeMethod<String>('activeConfig') ?? '';

  Future<String> readProfile(String id) async =>
      await _channel.invokeMethod<String>('readProfile', id) ?? '';

  Future<String> saveProfile({String? id, String? name, required String config}) =>
      _channel.invokeMethod<String>('saveProfile', <String, String?>{
        'id': id,
        'name': name,
        'config': config,
      }).then((value) => value ?? '');

  Future<void> deleteProfile(String id) =>
      _channel.invokeMethod<void>('deleteProfile', id);

  Future<void> selectProfile(String id) =>
      _channel.invokeMethod<void>('selectProfile', id);

  Future<void> validate(String config) =>
      _channel.invokeMethod<void>('validate', config);

  Future<void> selectOutbound(String group, String outbound) =>
      _channel.invokeMethod<void>('selectOutbound', <String, String>{
        'group': group,
        'outbound': outbound,
      });

  Future<void> urlTest(String tag) => _channel.invokeMethod<void>('urlTest', tag);

  Future<bool> vpnPrepared() async =>
      await _channel.invokeMethod<bool>('vpnPrepared') ?? false;

  Future<void> start() => _channel.invokeMethod<void>('start');

  Future<void> stop() => _channel.invokeMethod<void>('stop');

  Future<void> openVpnSettings() =>
      _channel.invokeMethod<void>('openVpnSettings');

  /// Returns null when the picker was dismissed.
  Future<({String name, String config})?> pickConfig() async {
    final map = await _channel.invokeMapMethod<String, dynamic>('pickConfig');
    if (map == null) return null;
    return (name: map['name'] as String? ?? '导入配置',
        config: map['config'] as String? ?? '');
  }
}

/// Translates a platform error into something worth showing.
String bridgeMessage(Object error) {
  if (error is PlatformException) {
    switch (error.code) {
      case 'no_config':
        return error.message ?? '请先导入并选择 sing-box JSON 配置';
      case 'no_tun':
        return error.message ?? 'Android VPN 配置必须包含 tun 入站';
      case 'denied':
        return error.message ?? '未授予 VPN 权限，连接未启动';
      default:
        return error.message ?? error.code;
    }
  }
  return error.toString();
}
