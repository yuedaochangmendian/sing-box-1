import 'package:flutter/material.dart';

import '../bridge/skybox_bridge.dart';
import '../state/app_state.dart';
import '../theme/sky_theme.dart';
import '../util/format.dart';
import 'dialogs/city_picker.dart';
import 'dialogs/profile_editor.dart';
import 'home_page.dart';
import 'widgets/glass.dart';

/// 配置 / 活动 / 设置. Opened over the sky screen, closed with the ✕ or the
/// back gesture, exactly like the previous native panel.
class PanelPage extends StatefulWidget {
  const PanelPage({super.key, required this.state, required this.initialTab});

  final AppState state;
  final int initialTab;

  @override
  State<PanelPage> createState() => _PanelPageState();
}

class _PanelPageState extends State<PanelPage> {
  late int _tab;

  @override
  void initState() {
    super.initState();
    _tab = widget.initialTab;
    widget.state.addListener(_onChanged);
  }

  @override
  void dispose() {
    widget.state.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  AppState get app => widget.state;
  SkyTheme get theme => SkyTheme.of(app.weather);

  @override
  Widget build(BuildContext context) {
    final theme = this.theme;
    return PopScope(
      canPop: true,
      child: SkyScaffold(
        background: SizedBox.expand(
          child: DecoratedBox(decoration: BoxDecoration(gradient: theme.background)),
        ),
        body: Stack(
          children: <Widget>[
            SafeArea(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(20, 52, 20, 118),
                child: switch (_tab) {
                  0 => _Profiles(app: app, theme: theme),
                  1 => _Activity(app: app, theme: theme),
                  _ => _Settings(app: app, theme: theme),
                },
              ),
            ),
            Positioned(
              top: 14,
              right: 18,
              child: SkyGlassCircle(
                icon: const Icon(Icons.close, size: 17),
                size: 40,
                tint: theme.cardColor,
                dark: !theme.isDay,
                foreground: theme.iconColor,
                semanticLabel: '关闭配置面板',
                onPressed: () => Navigator.of(context).maybePop(),
              ),
            ),
            Positioned(
              left: 16,
              right: 16,
              bottom: 10,
              child: SkyGlassDock(
                tint: theme.dockColor,
                dark: !theme.isDay,
                selectedIndex: _tab,
                onSelected: (index) => setState(() => _tab = index),
                items: const <SkyDockItem>[
                  SkyDockItem(label: '配置', icon: Icons.folder_outlined),
                  SkyDockItem(label: '活动', icon: Icons.show_chart),
                  SkyDockItem(label: '设置', icon: Icons.settings_outlined),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Heading extends StatelessWidget {
  const _Heading({required this.title, required this.subtitle, required this.theme});

  final String title;
  final String subtitle;
  final SkyTheme theme;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            title,
            style: TextStyle(
              fontSize: 32,
              fontWeight: FontWeight.w300,
              color: theme.textColor,
            ),
          ),
          const SizedBox(height: 7),
          Padding(
            padding: const EdgeInsets.only(bottom: 24),
            child: Text(
              subtitle,
              style: TextStyle(fontSize: 14, color: theme.secondaryTextColor),
            ),
          ),
        ],
      );
}

class _Profiles extends StatelessWidget {
  const _Profiles({required this.app, required this.theme});

  final AppState app;
  final SkyTheme theme;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        _Heading(
          title: '配置',
          subtitle: '你的 sing-box JSON，保存在本机私有目录。',
          theme: theme,
        ),
        Row(
          children: <Widget>[
            _Action(label: '导入 JSON', theme: theme,
                onTap: () => app.importFromSystem()),
            _Action(
              label: '新建配置',
              theme: theme,
              onTap: () => openProfileEditor(
                context,
                app,
                id: null,
                name: '新配置',
                config: '{\n  "log": {\n    "level": "info"\n  },\n'
                    '  "inbounds": [],\n  "outbounds": []\n}',
              ),
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(0, 10, 0, 22),
          child: Text(
            '新建模板仅为编辑起点，不含节点或可用 VPN 入站。保存会调用内核检查；'
            '连接仍须具备有效的 TUN 配置。运行中修改配置需停止后重新连接。',
            style: TextStyle(fontSize: 13, color: theme.secondaryTextColor),
          ),
        ),
        if (app.profiles.isEmpty)
          _Card(
            theme: theme,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text('还没有配置',
                    style: TextStyle(fontSize: 23, color: theme.textColor)),
                const SizedBox(height: 10),
                Text(
                  '导入已有的 sing-box JSON，或新建后填写自己的配置。这里不会提供虚假代理节点。',
                  style: TextStyle(fontSize: 15, color: theme.secondaryTextColor),
                ),
                const SizedBox(height: 10),
                _Action(label: '从文件导入', theme: theme,
                    onTap: () => app.importFromSystem()),
              ],
            ),
          ),
        for (final profile in app.profiles)
          Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: _Card(
              theme: theme,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text(profile.name,
                      style: TextStyle(fontSize: 20, color: theme.textColor)),
                  const SizedBox(height: 6),
                  Text(
                    profile.id == app.activeProfileId ? '● 已选用' : '未选用',
                    style: TextStyle(fontSize: 12, color: theme.secondaryTextColor),
                  ),
                  const SizedBox(height: 13),
                  Wrap(
                    spacing: 8,
                    children: <Widget>[
                      _Action(
                        label: profile.id == app.activeProfileId ? '当前配置' : '选用',
                        theme: theme,
                        enabled: profile.id != app.activeProfileId,
                        onTap: () async {
                          if (app.running) {
                            showMessage(context, '请先停止连接再修改配置');
                            return;
                          }
                          await app.selectProfile(profile.id);
                        },
                      ),
                      _Action(
                        label: '编辑',
                        theme: theme,
                        onTap: () async {
                          if (app.running) {
                            showMessage(context, '请先停止连接再修改配置');
                            return;
                          }
                          final config = await SkyboxBridge().readProfile(profile.id);
                          if (!context.mounted) return;
                          openProfileEditor(context, app,
                              id: profile.id, name: profile.name, config: config);
                        },
                      ),
                      _Action(
                        label: '删除',
                        theme: theme,
                        onTap: () => _confirmDelete(context, app, profile),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }

  Future<void> _confirmDelete(
    BuildContext context,
    AppState app,
    ProfileSummary profile,
  ) async {
    final confirmed = await showSkyConfirm(
      context: context,
      title: '删除配置？',
      message: '将从本机删除「${profile.name}」。此操作不可撤销。',
      confirmLabel: '删除',
      cancelLabel: '取消',
      destructive: true,
    );
    if (confirmed != true) return;
    await app.deleteProfile(profile.id);
  }
}

class _Activity extends StatelessWidget {
  const _Activity({required this.app, required this.theme});

  final AppState app;
  final SkyTheme theme;

  @override
  Widget build(BuildContext context) {
    final running = app.running;
    final status = app.status;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        _Heading(
          title: '活动',
          subtitle: '实时速率与服务日志，不生成模拟数据。',
          theme: theme,
        ),
        _Card(
          theme: theme,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text('${running ? '● ' : '○ '}${status.state}',
                  style: TextStyle(fontSize: 23, color: theme.textColor)),
              const SizedBox(height: 12),
              Text(
                '↓ ${formatBytes(status.downlinkTotal)}\n↑ ${formatBytes(status.uplinkTotal)}',
                style: TextStyle(fontSize: 17, color: theme.textColor),
              ),
              const SizedBox(height: 6),
              Text(
                running
                    ? '下载 ${formatRate(status.downlink)}  ·  上传 ${formatRate(status.uplink)}'
                    : app.busy
                        ? '正在检查配置…'
                        : '当前配置：${app.activeProfileName.isEmpty ? '未选择' : app.activeProfileName}',
                style: TextStyle(fontSize: 14, color: theme.secondaryTextColor),
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        _Card(
          theme: theme,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Row(
                children: <Widget>[
                  Expanded(
                    child: Text('服务日志',
                        style: TextStyle(fontSize: 20, color: theme.textColor)),
                  ),
                  _Action(label: '刷新', theme: theme, onTap: () => app.refreshLogs()),
                ],
              ),
              const SizedBox(height: 14),
              SelectableText(
                app.logs.isEmpty ? '暂无日志' : app.logs,
                style: TextStyle(
                  fontSize: 12,
                  fontFamily: 'monospace',
                  color: theme.secondaryTextColor,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        SelectableText(
          app.status.error.isEmpty ? '暂无服务错误' : app.status.error,
          style: TextStyle(fontSize: 14, color: theme.textColor),
        ),
      ],
    );
  }
}

class _Settings extends StatelessWidget {
  const _Settings({required this.app, required this.theme});

  final AppState app;
  final SkyTheme theme;

  @override
  Widget build(BuildContext context) {
    final error = app.status.error;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        _Heading(title: '设置', subtitle: '内核、VPN 授权与本机信息。', theme: theme),
        _Card(
          theme: theme,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text('sing-box 内核',
                  style: TextStyle(fontSize: 21, color: theme.textColor)),
              const SizedBox(height: 10),
              SelectableText(
                app.coreVersion,
                style: TextStyle(fontSize: 15, color: theme.secondaryTextColor),
              ),
              const SizedBox(height: 16),
              Text('arm64-v8a · Flutter 界面',
                  style: TextStyle(fontSize: 13, color: theme.secondaryTextColor)),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _Card(
          theme: theme,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text('VPN 授权',
                  style: TextStyle(fontSize: 21, color: theme.textColor)),
              const SizedBox(height: 10),
              Text(
                '连接前由 Android 显示系统 VPN 授权。撤销授权可前往系统 VPN 设置。',
                style: TextStyle(fontSize: 14, color: theme.secondaryTextColor),
              ),
              const SizedBox(height: 8),
              _Action(
                label: '打开系统 VPN 设置',
                theme: theme,
                onTap: () => SkyboxBridge().openVpnSettings(),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _Card(
          theme: theme,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text('配置与隐私',
                  style: TextStyle(fontSize: 21, color: theme.textColor)),
              const SizedBox(height: 10),
              Text(
                '配置保存在应用 files/profiles 私有目录。不会主动导出或上传配置。'
                '日志可能包含地址与连接信息，分享前请自行检查。删除应用会清除本机配置。',
                style: TextStyle(fontSize: 14, color: theme.secondaryTextColor),
              ),
              const SizedBox(height: 8),
              _Action(label: '管理配置', theme: theme, onTap: () => Navigator.pop(context)),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _Card(
          theme: theme,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text('开源许可',
                  style: TextStyle(fontSize: 21, color: theme.textColor)),
              const SizedBox(height: 10),
              Text(
                'sing-box 使用 GNU General Public License v3.0 或更新版本'
                '（GPL-3.0-or-later）。源码与完整许可见项目仓库。',
                style: TextStyle(fontSize: 14, color: theme.secondaryTextColor),
              ),
              const SizedBox(height: 8),
              _Action(
                label: '查看项目与许可',
                theme: theme,
                onTap: () => _openProject(context),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _Card(
          theme: theme,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text('天气与地区',
                  style: TextStyle(fontSize: 21, color: theme.textColor)),
              const SizedBox(height: 10),
              Text(
                '天气、小时预报与空气质量来自 Open-Meteo（HTTPS，无需 API Key）。'
                '连接 VPN 时首页地区自动跟随节点出口 IP 所在地（ipwho.is / ipapi.co），'
                '手动选择城市后以手动为准；同一坐标最短 15 分钟刷新。'
                '天气主题只影响配色与图标，不代表 VPN 质量。',
                style: TextStyle(fontSize: 14, color: theme.secondaryTextColor),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: <Widget>[
                  _Action(
                    label: '刷新天气',
                    theme: theme,
                    onTap: () => app.weatherService.fetch(force: true),
                  ),
                  _Action(
                    label: '更换城市',
                    theme: theme,
                    onTap: () => openCityPicker(context, app),
                  ),
                  _Action(
                    label: '重新检测节点地区',
                    theme: theme,
                    onTap: () => app.refreshNodeRegion(manual: true),
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _Card(
          theme: theme,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text('最近错误',
                  style: TextStyle(fontSize: 21, color: theme.textColor)),
              const SizedBox(height: 10),
              SelectableText(
                error.isEmpty ? '暂无服务错误' : error,
                style: TextStyle(fontSize: 14, color: theme.secondaryTextColor),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Future<void> _openProject(BuildContext context) async {
    final uri = Uri.parse(
      'https://github.com/yuedaochangmendian/sing-box-1/tree/feature/skybox-android-arm64',
    );
    showMessage(context, uri.toString());
  }
}

class _Card extends StatelessWidget {
  const _Card({required this.theme, required this.child});

  final SkyTheme theme;
  final Widget child;

  @override
  Widget build(BuildContext context) => SkyGlassCard(
        tint: theme.cardColor,
        dark: !theme.isDay,
        alpha: 0.42,
        child: child,
      );
}

class _Action extends StatelessWidget {
  const _Action({
    required this.label,
    required this.theme,
    required this.onTap,
    this.enabled = true,
  });

  final String label;
  final SkyTheme theme;
  final VoidCallback onTap;
  final bool enabled;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(right: 8, bottom: 4),
        child: SkyGlassPill(
          label: label,
          tint: theme.cardColor,
          dark: !theme.isDay,
          foreground: theme.textColor,
          height: 44,
          onTap: enabled ? onTap : null,
        ),
      );
}
