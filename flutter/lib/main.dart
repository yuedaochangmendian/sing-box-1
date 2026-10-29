import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:g1455/g1455.dart';

import 'state/app_state.dart';
import 'theme/sky_theme.dart';
import 'ui/home_page.dart';

/// Bundled Chinese-capable face (see the `fonts` section of pubspec.yaml).
/// Using an explicit family keeps text off the device's default face, which some
/// OEM substitutions render with stray underline-like marks under glyph runs.
const String kAppFontFamily = 'NotoSansSC';

/// Material's theme for that family, with every style carrying an explicit "no
/// decoration" so no text can inherit an underline from an ancestor style.
final TextTheme kAppTextTheme = ThemeData(fontFamily: kAppFontFamily)
    .textTheme
    .apply(decoration: TextDecoration.none);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Compiles the glass shaders before the first frame, so the opening screen
  // already shows refraction instead of a flat tint that snaps in a beat later.
  await GlassHost.precache();
  runApp(const SkyBoxApp());
}

class SkyBoxApp extends StatefulWidget {
  const SkyBoxApp({super.key});

  @override
  State<SkyBoxApp> createState() => _SkyBoxAppState();
}

class _SkyBoxAppState extends State<SkyBoxApp> with WidgetsBindingObserver {
  late final AppState _state = AppState();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _state.start();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _state.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The weather refresh is cheap and throttled by the service itself.
    if (state == AppLifecycleState.resumed) {
      _state.weatherService.fetch(force: false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = SkyTheme.of(_state.weather);
    return MaterialApp(
      title: 'SkyBox',
      theme: ThemeData(
        useMaterial3: true,
        fontFamily: kAppFontFamily,
        textTheme: kAppTextTheme,
        brightness: theme.isDay ? Brightness.light : Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: theme.iconColor,
          brightness: theme.isDay ? Brightness.light : Brightness.dark,
        ),
      ),
      // The host lives above the navigator, so dialogs and sheets shown from
      // anywhere are under it too and their glass has something to sample.
      builder: (context, child) => GlassHost(
        // The sky gradient decides the appearance, not the platform's switch:
        // a phone in light mode still gets night glass over a night sky.
        appearance: theme.isDay ? Brightness.light : Brightness.dark,
        child: AnnotatedRegion<SystemUiOverlayStyle>(
          // Translucent system bars: the sky gradient owns the whole screen.
          value: theme.isDay ? SystemUiOverlayStyle.dark : SystemUiOverlayStyle.light,
          child: child ?? const SizedBox.shrink(),
        ),
      ),
      home: ListenableBuilder(
        listenable: _state,
        builder: (context, _) => HomePage(state: _state),
      ),
    );
  }
}
