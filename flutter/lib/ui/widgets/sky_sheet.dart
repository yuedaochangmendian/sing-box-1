import 'package:flutter/material.dart';

import '../../theme/sky_theme.dart';
import 'glass.dart';

/// A single entry in a [showSkySheet] sheet.
class SkySheetAction {
  const SkySheetAction({
    required this.label,
    required this.icon,
    required this.onPressed,
    this.destructive = false,
  });

  final String label;
  final Widget icon;
  final VoidCallback onPressed;
  final bool destructive;
}

/// A glass bottom sheet we fully own, so taps always reach the rows. Replaces the
/// library's action sheet, whose nested glass layers interfered with hit testing.
Future<T?> showSkySheet<T>({
  required BuildContext context,
  required List<SkySheetAction> actions,
  required String cancelLabel,
  required SkyTheme theme,
}) {
  return showModalBottomSheet<T>(
    context: context,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black54,
    isScrollControlled: true,
    builder: (sheetContext) => _SkySheetContent(
      sheetContext: sheetContext,
      actions: actions,
      cancelLabel: cancelLabel,
      theme: theme,
    ),
  );
}

class _SkySheetContent extends StatelessWidget {
  const _SkySheetContent({
    required this.sheetContext,
    required this.actions,
    required this.cancelLabel,
    required this.theme,
  });

  final BuildContext sheetContext;
  final List<SkySheetAction> actions;
  final String cancelLabel;
  final SkyTheme theme;

  @override
  Widget build(BuildContext context) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 18),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              SkyGlassCard(
                tint: theme.cardColor,
                dark: !theme.isDay,
                alpha: 0.46,
                padding: EdgeInsets.zero,
                child: Column(
                  children: <Widget>[
                    for (var i = 0; i < actions.length; i++) ...<Widget>[
                      if (i > 0)
                        Divider(height: 1, color: theme.textColor.withAlpha(0x1F)),
                      _SkySheetRow(
                        action: actions[i],
                        sheetContext: sheetContext,
                        theme: theme,
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 10),
              SkyGlassCard(
                tint: theme.cardColor,
                dark: !theme.isDay,
                alpha: 0.46,
                padding: EdgeInsets.zero,
                child: _SkySheetRow(
                  action: SkySheetAction(
                    label: cancelLabel,
                    icon: const Icon(Icons.close),
                    onPressed: () {},
                  ),
                  sheetContext: sheetContext,
                  theme: theme,
                  bold: true,
                  isCancel: true,
                ),
              ),
            ],
          ),
        ),
      );
}

class _SkySheetRow extends StatelessWidget {
  const _SkySheetRow({
    required this.action,
    required this.sheetContext,
    required this.theme,
    this.bold = false,
    this.isCancel = false,
  });

  final SkySheetAction action;
  final BuildContext sheetContext;
  final SkyTheme theme;
  final bool bold;
  final bool isCancel;

  @override
  Widget build(BuildContext context) {
    final color = action.destructive
        ? (theme.isDay ? const Color(0xFFC0392B) : const Color(0xFFFF6B5E))
        : theme.textColor;
    return Semantics(
      button: true,
      label: action.label,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () {
          Navigator.of(sheetContext).pop();
          if (!isCancel) action.onPressed();
        },
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 15, horizontal: 14),
          child: Row(
            children: <Widget>[
              IconTheme(
                data: IconThemeData(color: color, size: 21),
                child: action.icon,
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Text(
                  action.label,
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: bold ? FontWeight.w600 : FontWeight.normal,
                    color: color,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
