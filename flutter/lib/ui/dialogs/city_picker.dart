import 'package:flutter/material.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import '../../models/weather.dart';
import '../../state/app_state.dart';
import '../../theme/sky_theme.dart';
import '../widgets/glass.dart';

/// City search. Latest query wins; the sheet keeps the last successful list so
/// a failed retry does not blank the results.
Future<void> openCityPicker(BuildContext context, AppState app) async {
  final theme = SkyTheme.of(app.weather);
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: const Color(0x33000000),
    builder: (sheetContext) => Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(sheetContext).viewInsets.bottom,
      ),
      child: GlassSheet(
        child: _CityPickerBody(app: app, theme: theme),
      ),
    ),
  );
}

class _CityPickerBody extends StatefulWidget {
  const _CityPickerBody({required this.app, required this.theme});

  final AppState app;
  final SkyTheme theme;

  @override
  State<_CityPickerBody> createState() => _CityPickerBodyState();
}

class _CityPickerBodyState extends State<_CityPickerBody> {
  final TextEditingController _controller = TextEditingController();
  List<WeatherCity> _results = const <WeatherCity>[];
  String? _status;

  @override
  void initState() {
    super.initState();
    widget.app.weatherService.onCities = (cities) {
      if (!mounted) return;
      setState(() {
        _results = cities;
        _status = cities.isEmpty ? '没有匹配的城市' : null;
      });
    };
    widget.app.weatherService.onError = (message, {required citySearch}) {
      if (!mounted || !citySearch) return;
      setState(() => _status = message);
    };
  }

  @override
  void dispose() {
    widget.app.weatherService.onCities = null;
    widget.app.weatherService.onError = null;
    _controller.dispose();
    super.dispose();
  }

  Future<void> _search(String value) async {
    await widget.app.weatherService.searchCity(value);
  }

  @override
  Widget build(BuildContext context) {
    final theme = widget.theme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Center(
            child: Container(
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: theme.textColor.withAlpha(0x40),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 16),
          Text('选择城市',
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: theme.textColor)),
          const SizedBox(height: 12),
          GlassTextField(
            controller: _controller,
            placeholder: '输入城市名称',
            onChanged: _search,
            textStyle: TextStyle(color: theme.textColor),
            placeholderStyle: TextStyle(color: theme.secondaryTextColor),
          ),
          if (_status != null) ...<Widget>[
            const SizedBox(height: 10),
            Text(_status!, style: TextStyle(fontSize: 13, color: theme.secondaryTextColor)),
          ],
          const SizedBox(height: 12),
          Flexible(
            child: ListView.builder(
              shrinkWrap: true,
              itemCount: _results.length,
              itemBuilder: (context, index) {
                final city = _results[index];
                return Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: SkyGlassPill(
                    label: city.country.isEmpty
                        ? city.displayName
                        : '${city.displayName} · ${city.country}',
                    tint: theme.cardColor,
                    dark: !theme.isDay,
                    foreground: theme.textColor,
                    radius: 14,
                    height: 44,
                    onTap: () async {
                      await widget.app.weatherService.setCity(city);
                      if (context.mounted) Navigator.of(context).pop();
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
