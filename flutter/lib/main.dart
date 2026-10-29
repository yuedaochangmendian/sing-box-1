import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import 'state/app_state.dart';
import 'theme/sky_theme.dart';
import 'ui/home_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Warms the glass shaders so the first frame already shows real refraction.
  await LiquidGlassWidgets.initialize();
  runApp(LiquidGlassWidgets.wrap(
    child: const SkyBoxApp(),
    // MaterialApp users must hand the glass cascade Material's own brightness,
    // or shadows and outlines vanish in dark mode.
    brightnessResolver: Theme.maybeBrightnessOf,
    adaptiveQuality: true,
  ));
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
        brightness: theme.isDay ? Brightness.light : Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: theme.iconColor,
          brightness: theme.isDay ? Brightness.light : Brightness.dark,
        ),
      ),
      // Translucent system bars: the sky gradient owns the whole screen.
      builder: (context, child) => AnnotatedRegion<SystemUiOverlayStyle>(
        value: theme.isDay ? SystemUiOverlayStyle.dark : SystemUiOverlayStyle.light,
        child: child ?? const SizedBox.shrink(),
      ),
      home: ListenableBuilder(
        listenable: _state,
        builder: (context, _) => HomePage(state: _state),
      ),
    );
  }
}
