import 'package:flutter/material.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

/// Radii shared by every glass surface, mirroring the previous native build.
class GlassRadii {
  static const double card = 22;
  static const double control = 20;
  static const double dock = 40;
}

/// Material presets. The library's shader does the refraction; these only set
/// the optical constants — thickness drives the bevel (and therefore how far
/// the backdrop is pulled in at the rim), [chromaticAberration] the rainbow,
/// and [whitenStrength] the light-mode veil that keeps dark glyphs protected.
class GlassKit {
  /// Cards: thick enough to read as glass, calm enough for body text.
  static LiquidGlassSettings card({required Color tint, required bool dark, double alpha = 0.34}) =>
      LiquidGlassSettings(
        thickness: 26,
        blur: 13,
        glassColor: tint.withAlpha((alpha * 255).round()),
        saturation: 1.35,
        chromaticAberration: 0.014,
        refractiveIndex: 1.24,
        lightIntensity: 0.55,
        ambientStrength: 0.06,
        whitenStrength: dark ? 0.05 : 0.20,
        whitenGated: true,
        rimShade: dark ? 0.10 : 0.06,
        rimLight: dark ? 0.10 : 0.16,
      );

  /// Secondary pills and the dock: clearer, with a stronger edge.
  static LiquidGlassSettings clear({required Color tint, required bool dark, double alpha = 0.30}) =>
      LiquidGlassSettings(
        thickness: 22,
        blur: 15,
        glassColor: tint.withAlpha((alpha * 255).round()),
        saturation: 1.45,
        chromaticAberration: 0.020,
        refractiveIndex: 1.28,
        lightIntensity: 0.62,
        ambientStrength: 0.08,
        whitenStrength: dark ? 0.06 : 0.26,
        whitenGated: true,
        rimShade: dark ? 0.12 : 0.07,
        rimLight: dark ? 0.14 : 0.22,
      );

  /// The primary action. Opaque enough that white glyphs keep their contrast.
  static LiquidGlassSettings tinted({required Color accent, required bool dark}) =>
      LiquidGlassSettings(
        thickness: 22,
        blur: 8,
        glassColor: accent.withAlpha(dark ? 0xE6 : 0xF2),
        saturation: 1.60,
        chromaticAberration: 0.018,
        refractiveIndex: 1.30,
        lightIntensity: 0.70,
        ambientStrength: 0.10,
        whitenStrength: 0.0,
        whitenGated: false,
        rimShade: 0.10,
        rimLight: 0.26,
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
  Widget build(BuildContext context) => GlassContainer.card(
        width: width,
        padding: padding,
        shape: LiquidRoundedSuperellipse(borderRadius: radius),
        settings: GlassKit.card(tint: tint, dark: dark, alpha: alpha),
        child: child,
      );
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
    final textColor = foreground ?? (dark ? Colors.white : const Color(0xFF142B43));
    return GlassButton.custom(
      onTap: onTap ?? () {},
      enabled: onTap != null,
      height: height,
      shape: LiquidRoundedSuperellipse(borderRadius: radius),
      settings: GlassKit.clear(tint: tint, dark: dark),
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
  Widget build(BuildContext context) => GlassIconButton(
        icon: IconTheme(
          data: IconThemeData(
            color: foreground ?? (dark ? Colors.white : const Color(0xFF142B43)),
            size: size * 0.42,
          ),
          child: icon,
        ),
        onPressed: onPressed,
        size: size,
        semanticLabel: semanticLabel,
        settings: GlassKit.clear(tint: tint, dark: dark, alpha: 0.34),
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
  Widget build(BuildContext context) => GlassContainer(
        padding: const EdgeInsets.all(4),
        shape: const LiquidRoundedSuperellipse(borderRadius: GlassRadii.dock),
        settings: GlassKit.clear(tint: tint, dark: dark, alpha: 0.34),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: <Widget>[
            for (var i = 0; i < items.length; i++)
              Expanded(child: _DockSlot(
                item: items[i],
                selected: i == selectedIndex,
                onTap: () => onSelected(i),
              )),
          ],
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
    final color = selected ? theme.colorScheme.primary : theme.colorScheme.onSurface.withAlpha(0xCC);
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
