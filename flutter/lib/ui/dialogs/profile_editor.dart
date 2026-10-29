import 'package:flutter/material.dart';

import '../../state/app_state.dart';
import '../../theme/sky_theme.dart';
import '../home_page.dart';
import '../widgets/glass.dart';

/// Raw JSON editor. Saving runs the kernel check on the host; a rejected
/// configuration never leaves the editor.
Future<void> openProfileEditor(
  BuildContext context,
  AppState app, {
  required String? id,
  required String name,
  required String config,
}) async {
  final theme = SkyTheme.of(app.weather);
  await Navigator.of(context).push<void>(
    MaterialPageRoute<void>(
      fullscreenDialog: true,
      builder: (_) => _ProfileEditorPage(
        app: app,
        theme: theme,
        id: id,
        initialName: name,
        initialConfig: config,
      ),
    ),
  );
}

class _ProfileEditorPage extends StatefulWidget {
  const _ProfileEditorPage({
    required this.app,
    required this.theme,
    required this.id,
    required this.initialName,
    required this.initialConfig,
  });

  final AppState app;
  final SkyTheme theme;
  final String? id;
  final String initialName;
  final String initialConfig;

  @override
  State<_ProfileEditorPage> createState() => _ProfileEditorPageState();
}

class _ProfileEditorPageState extends State<_ProfileEditorPage> {
  late final TextEditingController _name =
      TextEditingController(text: widget.initialName);
  late final TextEditingController _config =
      TextEditingController(text: widget.initialConfig);
  String? _error;
  bool _saving = false;

  @override
  void dispose() {
    _name.dispose();
    _config.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_saving) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    await widget.app.saveProfile(
      id: widget.id,
      name: _name.text,
      config: _config.text,
    );
    if (!mounted) return;
    final error = widget.app.transientError;
    setState(() => _saving = false);
    if (error != null) {
      widget.app.clearTransientError();
      setState(() => _error = error);
      return;
    }
    Navigator.of(context).pop();
    showMessage(context, '配置已保存');
  }

  @override
  Widget build(BuildContext context) {
    final theme = widget.theme;
    return SkyScaffold(
      background: SizedBox.expand(
        child: DecoratedBox(decoration: BoxDecoration(gradient: theme.background)),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      widget.id == null ? '新建配置' : '编辑配置',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                        color: theme.textColor,
                      ),
                    ),
                  ),
                  SkyGlassCircle(
                    icon: const Icon(Icons.close, size: 17),
                    size: 40,
                    tint: theme.cardColor,
                    dark: !theme.isDay,
                    foreground: theme.iconColor,
                    semanticLabel: '关闭编辑器',
                    onPressed: () => Navigator.of(context).maybePop(),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              SkyGlassField(
                controller: _name,
                placeholder: '配置名称',
                textStyle: TextStyle(color: theme.textColor),
                placeholderStyle: TextStyle(color: theme.secondaryTextColor),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: SkyGlassField(
                  controller: _config,
                  placeholder: 'sing-box JSON',
                  tint: theme.cardColor,
                  dark: !theme.isDay,
                  radius: 18,
                  padding: const EdgeInsets.all(12),
                  expands: true,
                  textAlignVertical: TextAlignVertical.top,
                  textStyle: TextStyle(
                    fontSize: 12,
                    fontFamily: 'monospace',
                    color: theme.textColor,
                  ),
                  placeholderStyle: TextStyle(color: theme.secondaryTextColor),
                ),
              ),
              if (_error != null) ...<Widget>[
                const SizedBox(height: 10),
                SelectableText(
                  _error!,
                  style: TextStyle(fontSize: 13, color: theme.textColor),
                ),
              ],
              const SizedBox(height: 12),
              Row(
                children: <Widget>[
                  SkyGlassPill(
                    label: _saving ? '检查中…' : '保存',
                    tint: theme.accent,
                    dark: !theme.isDay,
                    foreground: Colors.white,
                    height: 44,
                    onTap: _saving ? null : _save,
                  ),
                  const SizedBox(width: 8),
                  SkyGlassPill(
                    label: '取消',
                    tint: theme.cardColor,
                    dark: !theme.isDay,
                    foreground: theme.textColor,
                    height: 44,
                    onTap: () => Navigator.of(context).maybePop(),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
