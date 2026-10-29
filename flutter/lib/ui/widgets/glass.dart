import 'dart:async';

import 'package:flutter/material.dart';
import 'package:g1455/g1455.dart';

/// Radii shared by every glass surface, mirroring the previous native build.
class GlassRadii {
  static const double card = 22;
  static const double control = 20;
  static const double dock = 40;
  static const double field = 14;
  static const double sheet = 28;
}

/// Material presets.
///
/// g1455's optics are measured rather than dialled: a finish is a blur sigma, a
/// tint laid over the refracted sample (`mix(base, rgb, a)`), a rim colour, and
/// the shape of the bend at the edge ([GlassOptics] — reach, peak displacement
/// and falloff). So each preset derives from the calibrated `.regular` branch
/// through [GlassFinish.copyWith] and keeps its [GlassFinish.name], which is
/// what keys the surface into the package's measured damage tables; only the
/// tint (our sky palette) and the edge geometry are ours.
class GlassKit {
  /// The branch of Apple's `.regular` this appearance wears. Naming the
  /// calibrated constant rather than rebuilding it keeps the measured tables.
  static GlassFinish _base(bool dark) =>
      dark ? GlassFinish.regularDark : GlassFinish.regularLight;

  /// Cards: thick enough to read as glass, calm enough for body text.
  static GlassFinish card({
    required Color tint,
    required bool dark,
    double alpha = 0.34,
  }) =>
      _base(dark).copyWith(
        blurSigmaLogical: 6.5,
        tint: tint.withValues(alpha: alpha),
        rim: dark ? const Color(0x1AFFFFFF) : const Color(0x33FFFFFF),
        // A slightly deeper bevel than the 21px default so a large card reads
        // as a thick slab rather than a film.
        optics: const GlassOptics(thickness: 24, strength: -52),
      );

  /// Secondary pills and the dock: clearer, with a stronger edge.
  static GlassFinish clear({
    required Color tint,
    required bool dark,
    double alpha = 0.30,
  }) =>
      _base(dark).copyWith(
        blurSigmaLogical: 8,
        tint: tint.withValues(alpha: alpha),
        rim: dark ? const Color(0x24FFFFFF) : const Color(0x40FFFFFF),
        optics: const GlassOptics(),
      );

  /// The primary action. Opaque enough that white glyphs keep their contrast.
  static GlassFinish tinted({
    required Color accent,
    required bool dark,
  }) =>
      _base(dark).copyWith(
        blurSigmaLogical: 4,
        tint: accent.withValues(alpha: dark ? 0.90 : 0.95),
        rim: dark ? const Color(0x33FFFFFF) : const Color(0x4DFFFFFF),
        optics: const GlassOptics(thickness: 22, strength: -46),
      );
}

/// Frosted surface used by every content card.
class SkyGlassCard extends StatelessWidget {
  const SkyGlassCard({
    super.key,
    required this.child,
    required this.tint,
    required this.dark,
    this.padding = const EdgeInsets.all(16),
    this.radius = GlassRadii.card,
    this.alpha = 0.34,
    this.width,
  });

  final Widget child;
  final Color tint;
  final bool dark;
  final EdgeInsetsGeometry padding;
  final double radius;
  final double alpha;
  final double? width;

  @override
  Widget build(BuildContext context) {
    final Widget surface = GlassSurface(
      borderRadius: BorderRadius.circular(radius),
      finish: GlassKit.card(tint: tint, dark: dark, alpha: alpha),
      child: Padding(padding: padding, child: child),
    );
    return width == null ? surface : SizedBox(width: width, child: surface);
  }
}

/// Capsule control: CLEAR for secondary actions, TINTED for the primary one.
class SkyGlassPill extends StatelessWidget {
  const SkyGlassPill({
    super.key,
    required this.label,
    required this.onTap,
    required this.tint,
    required this.dark,
    this.icon,
    this.foreground,
    this.radius = GlassRadii.control,
    this.height = 40,
    this.padding = const EdgeInsets.symmetric(horizontal: 16),
  });

  final String label;
  final VoidCallback? onTap;
  final Color tint;
  final bool dark;
  final Widget? icon;
  final Color? foreground;
  final double radius;
  final double height;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    final textColor =
        foreground ?? (dark ? Colors.white : const Color(0xFF142B43));
    return GlassButton(
      onPressed: onTap,
      borderRadius: BorderRadius.circular(radius),
      // The control owns the tap target; the row below owns the content inset.
      padding: EdgeInsets.zero,
      minSize: Size(0, height),
      finish: GlassKit.clear(tint: tint, dark: dark),
      child: Padding(
        padding: padding,
        child: Row(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            if (icon != null) ...<Widget>[
              IconTheme(
                data: IconThemeData(color: textColor, size: 15),
                child: icon!,
              ),
              const SizedBox(width: 7),
            ],
            Flexible(
              child: Text(
                label,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: textColor,
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Circular floating button, the 54dp circles from the reference layout.
class SkyGlassCircle extends StatelessWidget {
  const SkyGlassCircle({
    super.key,
    required this.icon,
    required this.onPressed,
    required this.tint,
    required this.dark,
    this.size = 54,
    this.foreground,
    this.semanticLabel,
  });

  final Widget icon;
  final VoidCallback onPressed;
  final Color tint;
  final bool dark;
  final double size;
  final Color? foreground;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) => Semantics(
        button: true,
        label: semanticLabel,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: onPressed,
          child: SizedBox(
            width: size,
            height: size,
            child: GlassSurface(
              borderRadius: BorderRadius.circular(size / 2),
              finish: GlassKit.clear(tint: tint, dark: dark, alpha: 0.34),
              // A control's disc carries no label of its own: the floor that
              // dims glass for a label's sake would grey it over the backdrop.
              labelled: false,
              child: Center(
                child: IconTheme(
                  data: IconThemeData(
                    color: foreground ??
                        (dark ? Colors.white : const Color(0xFF142B43)),
                    size: size * 0.42,
                  ),
                  child: icon,
                ),
              ),
            ),
          ),
        ),
      );
}

/// Bottom dock: one capsule holding three items.
class SkyGlassDock extends StatelessWidget {
  const SkyGlassDock({
    super.key,
    required this.items,
    required this.selectedIndex,
    required this.onSelected,
    required this.tint,
    required this.dark,
  });

  final List<SkyDockItem> items;
  final int selectedIndex;
  final ValueChanged<int> onSelected;
  final Color tint;
  final bool dark;

  @override
  Widget build(BuildContext context) => GlassSurface(
        borderRadius: BorderRadius.circular(GlassRadii.dock),
        finish: GlassKit.clear(tint: tint, dark: dark, alpha: 0.34),
        child: Padding(
          padding: const EdgeInsets.all(4),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: <Widget>[
              for (var i = 0; i < items.length; i++)
                Expanded(
                  child: _DockSlot(
                    item: items[i],
                    selected: i == selectedIndex,
                    onTap: () => onSelected(i),
                  ),
                ),
            ],
          ),
        ),
      );
}

class SkyDockItem {
  const SkyDockItem({required this.label, required this.icon});

  final String label;
  final IconData icon;
}

class _DockSlot extends StatelessWidget {
  const _DockSlot({
    required this.item,
    required this.selected,
    required this.onTap,
  });

  final SkyDockItem item;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = selected
        ? theme.colorScheme.primary
        : theme.colorScheme.onSurface.withAlpha(0xCC);
    return Semantics(
      selected: selected,
      button: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Container(
          height: 52,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(30),
            color: selected ? color.withAlpha(0x2E) : Colors.transparent,
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: <Widget>[
              Icon(item.icon, size: 22, color: color),
              const SizedBox(height: 2),
              Text(
                item.label,
                style: TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.bold,
                  color: color,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Screen shell: a full-bleed [background] with [body] laid over it.
///
/// g1455's `GlassScaffold` is built around bars and a scroll edge and takes no
/// background, so the plain "backdrop under content" case lives here instead —
/// it is a stack, and nothing about it needs capturing on its own.
class SkyScaffold extends StatelessWidget {
  const SkyScaffold({
    super.key,
    required this.background,
    required this.body,
  });

  final Widget background;
  final Widget body;

  @override
  Widget build(BuildContext context) => Stack(
        children: <Widget>[
          Positioned.fill(child: background),
          Positioned.fill(child: body),
        ],
      );
}

/// Text field on glass.
///
/// g1455's own field draws its label in the finish's label colour and takes no
/// text style, which a JSON editor and a hint in the sky palette both need to
/// override — so this is a plain [TextField] on a [GlassSurface].
class SkyGlassField extends StatelessWidget {
  const SkyGlassField({
    super.key,
    this.controller,
    this.focusNode,
    this.placeholder,
    this.onChanged,
    this.onSubmitted,
    this.textStyle,
    this.placeholderStyle,
    this.cursorColor,
    this.tint,
    this.dark = true,
    this.radius = GlassRadii.field,
    this.height = 48,
    this.padding = const EdgeInsets.symmetric(horizontal: 14),
    this.expands = false,
    this.maxLines = 1,
    this.minLines,
    this.textAlignVertical,
    this.keyboardType,
    this.autofocus = false,
    this.enabled = true,
  });

  final TextEditingController? controller;
  final FocusNode? focusNode;
  final String? placeholder;
  final ValueChanged<String>? onChanged;
  final ValueChanged<String>? onSubmitted;
  final TextStyle? textStyle;
  final TextStyle? placeholderStyle;
  final Color? cursorColor;
  final Color? tint;
  final bool dark;
  final double radius;

  /// Fixed height for a single-line field. Ignored when [expands] is true, so
  /// the field can take whatever its parent gives it.
  final double height;
  final EdgeInsetsGeometry padding;
  final bool expands;
  final int? maxLines;
  final int? minLines;
  final TextAlignVertical? textAlignVertical;
  final TextInputType? keyboardType;
  final bool autofocus;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final Widget field = TextField(
      controller: controller,
      focusNode: focusNode,
      expands: expands,
      maxLines: expands ? null : maxLines,
      minLines: expands ? null : minLines,
      textAlignVertical: textAlignVertical,
      keyboardType: keyboardType,
      autofocus: autofocus,
      enabled: enabled,
      cursorColor: cursorColor,
      style: textStyle,
      onChanged: onChanged,
      onSubmitted: onSubmitted,
      decoration: InputDecoration(
        isCollapsed: true,
        border: InputBorder.none,
        enabledBorder: InputBorder.none,
        focusedBorder: InputBorder.none,
        disabledBorder: InputBorder.none,
        hintText: placeholder,
        hintStyle: placeholderStyle,
        contentPadding: EdgeInsets.zero,
      ),
    );

    return GlassSurface(
      borderRadius: BorderRadius.circular(radius),
      finish: GlassKit.card(tint: tint ?? Colors.white, dark: dark, alpha: 0.30),
      // The text colours are ours, not the finish's, so the label floor must not
      // dim the glass for a label colour this surface does not use.
      labelled: false,
      child: Padding(
        padding: padding,
        child: expands
            ? field
            : SizedBox(height: height, child: Center(child: field)),
      ),
    );
  }
}

/// The glass body of a bottom sheet: square at the bottom edge, rounded on top.
class SkySheetSurface extends StatelessWidget {
  const SkySheetSurface({
    super.key,
    required this.child,
    required this.tint,
    required this.dark,
    this.padding = const EdgeInsets.fromLTRB(20, 10, 20, 24),
  });

  final Widget child;
  final Color tint;
  final bool dark;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) => GlassSurface(
        borderRadius: const BorderRadius.vertical(
          top: Radius.circular(GlassRadii.sheet),
        ),
        finish: GlassKit.card(tint: tint, dark: dark, alpha: 0.46),
        child: Padding(padding: padding, child: child),
      );
}

/// Non-blocking message on glass. The previous build used Android toasts.
class SkyToast {
  const SkyToast._();

  static void show(
    BuildContext context, {
    required String message,
    Duration duration = const Duration(seconds: 2),
  }) {
    final OverlayState overlay = Overlay.of(context, rootOverlay: true);
    late final OverlayEntry entry;
    var removed = false;

    void remove() {
      if (removed) return;
      removed = true;
      if (entry.mounted) entry.remove();
    }

    entry = OverlayEntry(
      builder: (BuildContext context) {
        final ThemeData theme = Theme.of(context);
        final bool dark = theme.brightness == Brightness.dark;
        return Positioned(
          left: 28,
          right: 28,
          bottom: 132,
          child: IgnorePointer(
            child: Center(
              child: GlassSurface(
                borderRadius: BorderRadius.circular(18),
                finish: GlassKit.card(
                  tint: theme.colorScheme.surface,
                  dark: dark,
                  alpha: 0.42,
                ),
                child: Padding(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
                  child: Text(
                    message,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 14,
                      color: theme.colorScheme.onSurface,
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );

    overlay.insert(entry);
    Timer(duration, remove);
  }
}

/// Two-button glass confirmation. Completes with `true` when the confirming
/// action is taken, `false` on cancel, and null when the dim is dismissed.
///
/// g1455's alert draws its own label and button colours from the finish, which
/// is exactly what a dialog wants — so this only names the copy.
Future<bool?> showSkyConfirm({
  required BuildContext context,
  required String title,
  required String message,
  required String confirmLabel,
  required String cancelLabel,
  bool destructive = false,
}) {
  return showGlassDialog<bool>(
    context: context,
    builder: (BuildContext context) => GlassAlert(
      title: Text(title),
      message: Text(message),
      actions: <GlassAlertAction>[
        GlassAlertAction(
          label: cancelLabel,
          isDefault: !destructive,
          onPressed: () => Navigator.pop(context, false),
        ),
        GlassAlertAction(
          label: confirmLabel,
          isDestructive: destructive,
          onPressed: () => Navigator.pop(context, true),
        ),
      ],
    ),
  );
}
