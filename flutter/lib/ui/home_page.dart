import 'package:flutter/material.dart';

import '../bridge/skybox_bridge.dart';
import '../models/weather.dart';
import '../state/app_state.dart';
import '../theme/sky_theme.dart';
import '../util/format.dart';
import 'panel_page.dart';
import 'widgets/glass.dart';
import 'widgets/speed_chart.dart';
import 'widgets/sky_sheet.dart';
import 'dialogs/city_picker.dart';
import 'dialogs/profile_editor.dart';
import 'dialogs/weather_detail.dart';

/// Non-blocking message; the previous build used Android toasts.
void showMessage(BuildContext context, String message) {
  SkyToast.show(
    context,
    message: message,
    duration: const Duration(seconds: 2),
  );
}

/// The sky screen: weather for the node region on top, the live connection
/// underneath. Everything is glass over the day's own gradient.
class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.state});

  final AppState state;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final Set<String> _expandedGroups = <String>{};

  @override
  void initState() {
    super.initState();
    widget.state.addListener(_onChanged);
  }

  @override
  void dispose() {
    widget.state.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    final imported = widget.state.importedConfig;
    if (imported != null && mounted) {
      widget.state.clearImported();
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        openProfileEditor(context, widget.state,
            id: null, name: imported.name, config: imported.config);
      });
    }
    if (mounted) setState(() {});
  }

  AppState get app => widget.state;

  SkyTheme get theme => SkyTheme.of(app.weather);

  String get regionName {
    final node = app.nodeLocation.last;
    if (node != null && node.isValid && app.nodeLocation.fromNode) {
      return node.displayName;
    }
    return app.weatherService.city.displayName;
  }

  String get regionSubtitle {
    final location = app.nodeLocation;
    if (location.last?.isValid == true && location.fromNode) {
      return app.weatherService.isManual
          ? '节点地区 · 手动城市优先，点击更换'
          : '节点地区 · 点击更换城市';
    }
    return '连接后自动获取节点地区 · 或点击选择城市';
  }

  @override
  Widget build(BuildContext context) {
    final theme = this.theme;
    return SkyScaffold(
      background: _SkyBackdrop(theme: theme),
      body: Stack(
        children: <Widget>[
          SafeArea(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(16, 22, 16, 118),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              _CityHeader(
                name: regionName,
                subtitle: regionSubtitle,
                theme: theme,
                onPickCity: () => openCityPicker(context, app),
              ),
              const SizedBox(height: 12),
              _TemperatureReading(
                state: app,
                theme: theme,
                onShowDetail: () => openWeatherDetail(context, app),
              ),
              const SizedBox(height: 14),
              HomePills(app: app, theme: theme),
              const SizedBox(height: 72),
              _SpeedCard(app: app, theme: theme),
              const SizedBox(height: 14),
              _GroupsCard(
                app: app,
                theme: theme,
                expanded: _expandedGroups,
                onToggle: (tag) => setState(() {
                  if (_expandedGroups.contains(tag)) {
                    _expandedGroups.remove(tag);
                  } else {
                    _expandedGroups.add(tag);
                  }
                }),
              ),
                ],
              ),
            ),
          ),
          HomeControls(app: app, theme: theme),
        ],
      ),
    );
  }
}

class _SkyBackdrop extends StatelessWidget {
  const _SkyBackdrop({required this.theme});

  final SkyTheme theme;

  @override
  Widget build(BuildContext context) => SizedBox.expand(
        child: DecoratedBox(
          decoration: BoxDecoration(gradient: theme.background),
        ),
      );
}

class _CityHeader extends StatelessWidget {
  const _CityHeader({
    required this.name,
    required this.subtitle,
    required this.theme,
    required this.onPickCity,
  });

  final String name;
  final String subtitle;
  final SkyTheme theme;
  final VoidCallback onPickCity;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 25,
              fontWeight: FontWeight.bold,
              color: theme.textColor,
            ),
          ),
          const SizedBox(height: 7),
          Semantics(
            button: true,
            label: '选择天气城市',
            child: GestureDetector(
              onTap: onPickCity,
              child: Text(
                subtitle,
                style: TextStyle(
                  fontSize: 13,
                  color: theme.secondaryTextColor.withAlpha(0xD0),
                ),
              ),
            ),
          ),
        ],
      );
}

class _TemperatureReading extends StatelessWidget {
  const _TemperatureReading({
    required this.state,
    required this.theme,
    required this.onShowDetail,
  });

  final AppState state;
  final SkyTheme theme;
  final VoidCallback onShowDetail;

  @override
  Widget build(BuildContext context) {
    final weather = state.weather;
    final has = weather != null && weather.hasData;
    final condition = has
        ? _conditionLine(weather, theme)
        : (weather?.loading == true ? '天气加载中…' : '天气暂不可用');
    return Semantics(
      button: true,
      label: '查看天气详情',
      child: GestureDetector(
        onTap: onShowDetail,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  has ? roundCelsius(weather.temperature) : '—',
                  style: TextStyle(
                    fontSize: 104,
                    fontWeight: FontWeight.w200,
                    height: 1,
                    color: theme.textColor,
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    '°',
                    style: TextStyle(fontSize: 36, color: theme.textColor),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 2),
            Text(
              condition,
              style: TextStyle(
                fontSize: 16,
                color: theme.textColor.withAlpha(0xE6),
              ),
            ),
            if (_message(weather) != null) ...<Widget>[
              const SizedBox(height: 6),
              GestureDetector(
                onTap: () => state.weatherService.fetch(force: true),
                child: Text(
                  _message(weather) ?? '',
                  style: TextStyle(fontSize: 12, color: theme.textColor.withAlpha(0xCC)),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _conditionLine(WeatherState weather, SkyTheme theme) {
    final max = weather.todayMax;
    final min = weather.todayMin;
    if (max.isFinite && min.isFinite) {
      return '${theme.nameZh}  最高 ${max.round()}°  最低 ${min.round()}°';
    }
    return theme.nameZh;
  }

  String? _message(WeatherState? weather) {
    if (weather == null) return null;
    if (weather.error && weather.errorMessage.isNotEmpty) return weather.errorMessage;
    if (weather.stale) return '数据已过期 · 点击刷新';
    return null;
  }
}

class _SpeedCard extends StatelessWidget {
  const _SpeedCard({required this.app, required this.theme});

  final AppState app;
  final SkyTheme theme;

  @override
  Widget build(BuildContext context) {
    final running = app.running;
    final sentence = running
        ? '当前下载 ${formatRate(app.status.downlink)} · 上传 ${formatRate(app.status.uplink)}'
        : '未连接 · 连接后显示实时网速';
    final textColor = theme.textColor;
    return SkyGlassCard(
      tint: mixColor(theme.cardColor, theme.top, 0.30),
      dark: !theme.isDay,
      alpha: 0.36,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(sentence, style: TextStyle(fontSize: 15, color: textColor)),
          const SizedBox(height: 12),
          SpeedChart(
            downSamples: running ? app.speedHistory : const <int>[],
            upSamples: running ? app.uploadHistory : const <int>[],
            slotCount: app.sampleCapacity,
            lineColor: const Color(0xF2BEE5FF),
            gridColor: textColor.withAlpha(0x26),
            height: 156,
          ),
          const SizedBox(height: 6),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: <Widget>[
              Text(
                '15 秒前',
                style: TextStyle(
                  fontSize: 11,
                  color: theme.secondaryTextColor.withAlpha(0xCC),
                ),
              ),
              Text(
                '现在',
                style: TextStyle(
                  fontSize: 11,
                  color: theme.secondaryTextColor.withAlpha(0xCC),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _GroupsCard extends StatelessWidget {
  const _GroupsCard({
    required this.app,
    required this.theme,
    required this.expanded,
    required this.onToggle,
  });

  final AppState app;
  final SkyTheme theme;
  final Set<String> expanded;
  final ValueChanged<String> onToggle;

  @override
  Widget build(BuildContext context) {
    final groups = app.groups;
    final live = app.liveGroups;
    if (groups.isEmpty) return const SizedBox.shrink();
    return SkyGlassCard(
      tint: theme.cardColor,
      dark: !theme.isDay,
      alpha: 0.30,
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
      child: Column(
        children: <Widget>[
          if (groups.isNotEmpty && live.isEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(0, 8, 0, 4),
              child: Text(
                app.running
                    ? '策略组状态同步中 · 显示配置中的策略组'
                    : '未连接 · 显示配置中的策略组，连接后可切换与测速',
                style: TextStyle(fontSize: 12, color: theme.secondaryTextColor),
              ),
            ),
          for (var i = 0; i < groups.length; i++) ...<Widget>[
            if (i > 0)
              Divider(height: 1, color: theme.textColor.withAlpha(0x1F)),
            _GroupRow(
              group: groups[i],
              theme: theme,
              live: live.isNotEmpty,
              expanded: expanded.contains(groups[i].tag),
              onToggle: () => onToggle(groups[i].tag),
              onSelect: (item) async {
                if (!app.running) {
                  showMessage(context, '连接后可切换出口');
                  return;
                }
                if (!groups[i].selectable) {
                  showMessage(context, '该策略组不可手动选择');
                  return;
                }
                await app.selectOutbound(groups[i].tag, item.tag);
                final error = app.transientError;
                if (error != null && context.mounted) {
                  showMessage(context, error);
                  app.clearTransientError();
                }
              },
              onUrlTest: () async {
                await app.urlTestGroup(groups[i]);
                if (context.mounted) showMessage(context, '正在测速…');
              },
            ),
          ],
        ],
      ),
    );
  }
}

class _GroupRow extends StatelessWidget {
  const _GroupRow({
    required this.group,
    required this.theme,
    required this.live,
    required this.expanded,
    required this.onToggle,
    required this.onSelect,
    required this.onUrlTest,
  });

  final VpnGroup group;
  final SkyTheme theme;
  final bool live;
  final bool expanded;
  final VoidCallback onToggle;
  final ValueChanged<VpnGroupItem> onSelect;
  final VoidCallback onUrlTest;

  @override
  Widget build(BuildContext context) {
    final selected = group.selected.isEmpty
        ? (group.items.isEmpty ? '' : group.items.first.tag)
        : group.selected;
    final count = group.items.isEmpty ? '' : ' · ${group.items.length} 节点';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Semantics(
          button: true,
          label: group.tag,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onToggle,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 11),
              child: Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      group.tag,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.bold,
                        color: theme.textColor,
                      ),
                    ),
                  ),
                  Text(
                    '$selected$count',
                    style: TextStyle(fontSize: 12, color: theme.secondaryTextColor),
                  ),
                  const SizedBox(width: 8),
                  SizedBox(
                    width: 18,
                    child: Text(
                      expanded ? '▾' : '▸',
                      style: TextStyle(fontSize: 13, color: theme.secondaryTextColor),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        if (expanded) ...<Widget>[
          if (live && group.selectable && group.items.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: SkyGlassPill(
                label: '测速',
                tint: theme.cardColor,
                dark: !theme.isDay,
                foreground: theme.textColor,
                onTap: onUrlTest,
              ),
            ),
          for (final item in group.items)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Semantics(
                button: true,
                label: item.tag,
                child: GestureDetector(
                  onTap: () => onSelect(item),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
                    decoration: BoxDecoration(
                      color: theme.textColor.withAlpha(0x14),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Row(
                      children: <Widget>[
                        Expanded(
                          child: Text(
                            item.tag,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(fontSize: 14, color: theme.textColor),
                          ),
                        ),
                        Text(
                          _delayLabel(item.delay),
                          style: TextStyle(fontSize: 12, color: theme.secondaryTextColor),
                        ),
                        if (live && group.selected == item.tag) ...<Widget>[
                          const SizedBox(width: 8),
                          Text(
                            '✓',
                            style: TextStyle(fontSize: 13, color: theme.iconColor),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
        ],
      ],
    );
  }

  String _delayLabel(int delay) {
    if (delay > 0) return '$delay ms';
    if (delay < 0) return '超时';
    return '未测';
  }
}

/// The two floating circles plus the control pills, anchored above the dock.
class HomeControls extends StatelessWidget {
  const HomeControls({super.key, required this.app, required this.theme});

  final AppState app;
  final SkyTheme theme;

  @override
  Widget build(BuildContext context) => Stack(
        children: <Widget>[
          Positioned(
            left: 20,
            bottom: 22,
            child: SkyGlassCircle(
              icon: const Icon(Icons.dashboard_outlined),
              tint: theme.cardColor,
              dark: !theme.isDay,
              foreground: theme.iconColor,
              semanticLabel: '打开菜单',
              onPressed: () => _openMenu(context),
            ),
          ),
          Positioned(
            right: 20,
            bottom: 22,
            child: SkyGlassCircle(
              icon: const Icon(Icons.add),
              tint: theme.cardColor,
              dark: !theme.isDay,
              foreground: theme.iconColor,
              semanticLabel: '添加配置',
              onPressed: () => _openAdd(context),
            ),
          ),
        ],
      );

  void _openMenu(BuildContext context) {
    final app = this.app;
    final theme = this.theme;
    showSkySheet<void>(
      context: context,
      cancelLabel: '取消',
      theme: theme,
      actions: <SkySheetAction>[
        SkySheetAction(
          label: '配置管理',
          icon: const Icon(Icons.folder_outlined),
          onPressed: () => _openPanel(context, app, 0),
        ),
        SkySheetAction(
          label: '活动日志',
          icon: const Icon(Icons.show_chart),
          onPressed: () => _openPanel(context, app, 1),
        ),
        SkySheetAction(
          label: '设置',
          icon: const Icon(Icons.settings_outlined),
          onPressed: () => _openPanel(context, app, 2),
        ),
        SkySheetAction(
          label: '天气详情',
          icon: const Icon(Icons.cloud_outlined),
          onPressed: () => openWeatherDetail(context, app),
        ),
        SkySheetAction(
          label: '刷新节点地区',
          icon: const Icon(Icons.my_location),
          onPressed: () async {
            await app.refreshNodeRegion(manual: true);
            if (context.mounted) {
              final error = app.transientError;
              showMessage(context, error ?? '节点地区已更新');
              if (error != null) app.clearTransientError();
            }
          },
        ),
      ],
    );
  }

  void _openAdd(BuildContext context) {
    final app = this.app;
    final theme = this.theme;
    showSkySheet<void>(
      context: context,
      cancelLabel: '取消',
      theme: theme,
      actions: <SkySheetAction>[
        SkySheetAction(
          label: '从文件导入 JSON',
          icon: const Icon(Icons.file_open_outlined),
          onPressed: () async {
            await app.importFromSystem();
            if (context.mounted && app.importedConfig == null) {
              showMessage(context, '未选择文件');
            }
          },
        ),
        SkySheetAction(
          label: '新建空白配置',
          icon: const Icon(Icons.note_add_outlined),
          onPressed: () {
            _openPanel(context, app, 0);
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (!context.mounted) return;
              openProfileEditor(
                context,
                app,
                id: null,
                name: '新配置',
                config: '{\n  "log": {\n    "level": "info"\n  },\n'
                    '  "inbounds": [],\n  "outbounds": []\n}',
              );
            });
          },
        ),
      ],
    );
  }

  void _openPanel(BuildContext context, AppState app, int tab) {
    Navigator.of(context).push<void>(
      MaterialPageRoute<void>(
        builder: (_) => PanelPage(state: app, initialTab: tab),
      ),
    );
  }
}

/// The latency pill and the start/stop pill, shown under the temperature.
class HomePills extends StatelessWidget {
  const HomePills({super.key, required this.app, required this.theme});

  final AppState app;
  final SkyTheme theme;

  @override
  Widget build(BuildContext context) {
    final latency = app.latencyProbe;
    final label = !app.running
        ? '延迟 —'
        : latency.testing
            ? '延迟 …'
            : latency.milliseconds >= 0
                ? '延迟 ${latency.milliseconds} ms'
                : '延迟 失败';
    final running = app.running;
    final startLabel = app.busy ? '检查中…' : running ? '停止' : '启动';
    return Row(
      children: <Widget>[
        SkyGlassPill(
          label: label,
          tint: theme.cardColor,
          dark: !theme.isDay,
          foreground: theme.textColor,
          icon: const Icon(Icons.speed),
          onTap: () async {
            if (!app.running) {
              showMessage(context, '请先开启 VPN 连接');
              return;
            }
            await latency.test();
            if (context.mounted) openWeatherDetail(context, app, latencyFocus: true);
          },
        ),
        const SizedBox(width: 10),
        SkyGlassPill(
          label: startLabel,
          tint: theme.accent,
          dark: !theme.isDay,
          foreground: Colors.white,
          icon: Icon(running ? Icons.stop : Icons.play_arrow),
          onTap: () async {
            if (running) {
              await app.disconnect();
              if (context.mounted) showMessage(context, '已请求停止');
              return;
            }
            await app.connect();
            final error = app.transientError;
            if (error != null && context.mounted) {
              showMessage(context, error);
              app.clearTransientError();
            } else if (context.mounted) {
              showMessage(context, '已请求启动，请等待服务状态更新');
            }
          },
        ),
      ],
    );
  }
}
