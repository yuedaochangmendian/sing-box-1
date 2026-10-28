package io.github.yuedaochangmendian.skybox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.nekohasekai.libbox.Libbox;

/** Native, dependency-free sky dashboard. All connection information comes from VpnController. */
public final class MainActivity extends Activity {
    private static final int IMPORT_JSON = 110;
    private static final int VPN_PERMISSION = 111;
    private static final int WHITE = Color.WHITE;
    private static final int SOFT = 0xDFFFFFFF;
    private static final int T_PILL = 46;
    private static final int T_DOCK_SLOT = 52;
    private static final int T_DOCK_PAD = 4;
    private static final int T_DOCK_MARGIN = 10;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout page;
    private LinearLayout dock;
    private TextView profileLabel, nodeTitle, stateLabel, description, totalsLabel, routeLabel;
    private TextView errorLabel, logsLabel, versionLabel, latencyLabel, latencyHint;
    private TextView[] historyLabels;
    private WeatherIconView[] historyIcons;
    private WeatherService weatherService;
    private WeatherState weatherState;
    private WeatherTheme theme = WeatherTheme.from(null);
    private String themeKey = "";
    private String lastWeatherStamp = "";
    private TextView cityLabel, temperatureLabel, conditionsLabel, weatherDetails, weatherUpdated;
    private WeatherIconView weatherIcon;
    private AlertDialog cityDialog;
    private LinearLayout cityResults;
    private EditText cityInput;
    private TextView citySearchStatus;
    private final java.util.ArrayList<View> themedCards = new java.util.ArrayList<>();
    private final java.util.ArrayList<View> themedPills = new java.util.ArrayList<>();
    private final ImageView[] tabIcons = new ImageView[4];
    private Switch connectionSwitch;
    private TextView[] tabLabels = new TextView[4];
    private View[] tabPills = new View[4];
    private int selectedTab;
    private boolean renderingSwitch;
    private boolean busy;
    private boolean permissionPending;
    private String pendingConfig;
    private String pendingId;
    private String coreVersion = "正在读取…";
    private boolean resumed;
    private String lastLogs;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(0xFF72B3E9);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        selectedTab = savedInstanceState == null ? 0 : savedInstanceState.getInt("tab", 0);
        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3383D4, 0xFF5F9FE0, 0xFF8EC3EC}));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            }
            root.setPadding(left, top, right, bottom);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        weatherService = new WeatherService(this, new WeatherService.Callback() {
            public void onWeather(WeatherState state) {
                if (isFinishing() || isDestroyed()) return;
                weatherState = state;
                applyWeather();
            }
            public void onCities(List<WeatherService.City> cities) { showCityResults(cities); }
            public void onError(String message, boolean citySearch) {
                if (isFinishing() || isDestroyed()) return;
                if (citySearch && cityDialog != null && cityDialog.isShowing()) citySearchStatus.setText(message);
            }
        });
        weatherState = weatherService.getState();
        theme = WeatherTheme.from(weatherState);
        themeKey = themeKey(theme);
        WeatherTheme.apply(this, root, weatherState);
        buildPage();
        worker.execute(() -> {
            try {
                CoreRuntime.ensureInit(getApplicationContext());
                coreVersion = Libbox.version();
            } catch (Exception | LinkageError e) {
                coreVersion = "不可用：" + safeError(e);
            }
            handler.post(() -> {
                if (!isFinishing() && !isDestroyed() && versionLabel != null) versionLabel.setText(coreVersion);
            });
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", selectedTab);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        if (weatherService != null) weatherService.fetch(false);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (cityDialog != null && cityDialog.isShowing()) cityDialog.dismiss();
        if (weatherService != null) weatherService.close();
        worker.shutdown();
        super.onDestroy();
    }

    private void buildPage() {
        profileLabel = nodeTitle = stateLabel = description = totalsLabel = routeLabel = null;
        errorLabel = logsLabel = versionLabel = latencyLabel = latencyHint = null;
        cityLabel = temperatureLabel = conditionsLabel = weatherDetails = weatherUpdated = null;
        weatherIcon = null;
        connectionSwitch = null;
        historyLabels = null;
        historyIcons = null;
        java.util.Arrays.fill(tabIcons, null);
        java.util.Arrays.fill(tabLabels, null);
        java.util.Arrays.fill(tabPills, null);
        themedCards.clear();
        themedPills.clear();
        root.removeAllViews();
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        page = column();
        page.setPadding(dp(20), dp(16), dp(20), dp(118));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        if (selectedTab == 0) buildOverview();
        else if (selectedTab == 1) buildProfiles();
        else if (selectedTab == 2) buildActivity();
        else buildSettings();
        buildDock();
        refreshStatus();
    }

    private void buildOverview() {
        LinearLayout top = row();
        cityLabel = text(weatherCityName() + "  ﹀", 16, WHITE);
        cityLabel.setSingleLine(true);
        cityLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cityLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cityLabel.setPadding(dp(16), dp(13), dp(12), dp(13));
        cityLabel.setBackground(round(0x24FFFFFF, 32));
        cityLabel.setContentDescription("选择天气城市");
        cityLabel.setOnClickListener(v -> showCityPicker());
        themedPills.add(cityLabel);
        top.addView(cityLabel, new LinearLayout.LayoutParams(0, T_PILL, 1));
        connectionSwitch = new Switch(this);
        connectionSwitch.setText("Off  ");
        connectionSwitch.setTextColor(WHITE);
        connectionSwitch.setTextSize(17);
        connectionSwitch.setThumbTintList(android.content.res.ColorStateList.valueOf(WHITE));
        connectionSwitch.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFFFC229, 0x66FFFFFF}));
        connectionSwitch.setPadding(dp(13), 0, dp(10), 0);
        connectionSwitch.setBackground(round(0x24FFFFFF, 32));
        connectionSwitch.setContentDescription("启动或停止 VPN 连接");
        connectionSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (renderingSwitch) return;
            if (checked) beginStart(); else stopConnection();
            refreshStatus();
        });
        themedPills.add(connectionSwitch);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, T_PILL);
        toggleParams.leftMargin = dp(12);
        top.addView(connectionSwitch, toggleParams);
        page.addView(top);
        space(page, 18);
        LinearLayout weather = row();
        weather.setGravity(Gravity.CENTER);
        weatherIcon = new WeatherIconView(this);
        weatherIcon.setContentDescription("当前天气");
        weather.addView(weatherIcon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout weatherText = column();
        weatherText.setPadding(dp(14), 0, 0, 0);
        temperatureLabel = text("—°", 46, WHITE);
        temperatureLabel.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        temperatureLabel.setIncludeFontPadding(false);
        weatherText.addView(temperatureLabel, full());
        conditionsLabel = text("天气加载中…", 15, SOFT);
        weatherText.addView(conditionsLabel, full());
        weather.addView(weatherText, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(weather, full());
        weatherDetails = text("体感 — · 降水 — · 风 —", 12, SOFT);
        weatherDetails.setGravity(Gravity.CENTER);
        weatherDetails.setPadding(0, dp(4), 0, 0);
        page.addView(weatherDetails, full());
        weatherUpdated = text("", 11, 0xBBFFFFFF);
        weatherUpdated.setGravity(Gravity.CENTER);
        weatherUpdated.setPadding(0, dp(3), 0, 0);
        weatherUpdated.setOnClickListener(v -> refreshWeather(true));
        page.addView(weatherUpdated, full());
        space(page, 16);
        LinearLayout reading = row();
        reading.setGravity(Gravity.CENTER);
        latencyLabel = text("—", 68, WHITE);
        latencyLabel.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        latencyLabel.setIncludeFontPadding(false);
        latencyLabel.setContentDescription("点击测试当前 VPN 路由的 HTTP 延迟");
        latencyLabel.setOnClickListener(v -> {
            if (!VpnController.running) { toast("请先开启 VPN 连接"); return; }
            LatencyProbe.test(() -> handler.post(() -> {
                if (!isFinishing() && !isDestroyed()) refreshStatus();
            }));
            refreshStatus();
        });
        reading.addView(latencyLabel, new LinearLayout.LayoutParams(-2, dp(88)));
        TextView unit = text("ms", 17, SOFT);
        LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(-2, -2);
        unitParams.gravity = Gravity.TOP;
        unitParams.topMargin = dp(13);
        unitParams.leftMargin = dp(6);
        reading.addView(unit, unitParams);
        page.addView(reading, full());
        latencyHint = text("连接后点击测试 HTTP 延迟", 12, SOFT);
        latencyHint.setGravity(Gravity.CENTER);
        page.addView(latencyHint, full());
        space(page, 14);
        stateLabel = text("已断开", 17, WHITE);
        stateLabel.setGravity(Gravity.CENTER);
        page.addView(stateLabel, full());
        description = text("选用配置后，开启你的连接。", 13, SOFT);
        description.setGravity(Gravity.CENTER);
        description.setPadding(dp(8), dp(4), dp(8), 0);
        page.addView(description, full());
        space(page, 16);
        LinearLayout history = card();
        history.addView(text("最近一分钟 · 下载", 15, WHITE), full());
        TextView historyHint = text("天气图标对应下载量：晴为静默，云雨为有流量。", 12, SOFT);
        historyHint.setPadding(0, dp(3), 0, dp(10));
        history.addView(historyHint, full());
        View divider = new View(this);
        divider.setBackgroundColor(0x1AFFFFFF);
        history.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout columns = row();
        historyLabels = new TextView[6];
        historyIcons = new WeatherIconView[6];
        String[] times = {"−50″", "−40″", "−30″", "−20″", "−10″", "现在"};
        for (int i = 0; i < 6; i++) {
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(9), 0, 0);
            TextView time = text(times[i], 12, SOFT);
            time.setGravity(Gravity.CENTER);
            cell.addView(time, full());
            WeatherIconView icon = new WeatherIconView(this);
            icon.setTint(theme.secondaryTextColor);
            cell.addView(icon, new LinearLayout.LayoutParams(-1, dp(30)));
            historyIcons[i] = icon;
            historyLabels[i] = text("—", 12, WHITE);
            historyLabels[i].setGravity(Gravity.CENTER);
            historyLabels[i].setMaxLines(1);
            cell.addView(historyLabels[i], full());
            columns.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        history.addView(columns, full());
        page.addView(history, full());
        space(page, 12);
        LinearLayout metrics = row();
        LinearLayout trafficCard = card();
        trafficCard.addView(text("流量 · 当前服务", 13, SOFT), full());
        totalsLabel = text("—", 22, WHITE);
        totalsLabel.setPadding(0, dp(8), 0, dp(12));
        trafficCard.addView(totalsLabel, full());
        trafficCard.addView(text("实时速率见活动页", 11, SOFT), full());
        trafficCard.setOnClickListener(v -> selectTab(2));
        LinearLayout routingCard = card();
        routingCard.addView(text("路由 · 配置驱动", 13, SOFT), full());
        routeLabel = text("跟随 JSON", 19, WHITE);
        routeLabel.setPadding(0, dp(8), 0, dp(12));
        routingCard.addView(routeLabel, full());
        routingCard.addView(text("查看和编辑规则  ›", 11, SOFT), full());
        routingCard.setOnClickListener(v -> selectTab(1));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -1, 1);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -1, 1);
        right.leftMargin = dp(10);
        metrics.setBaselineAligned(false);
        metrics.addView(trafficCard, left);
        metrics.addView(routingCard, right);
        page.addView(metrics, full());
        space(page, 12);
        errorLabel = text("", 12, WHITE);
        errorLabel.setPadding(dp(14), dp(11), dp(14), dp(11));
        errorLabel.setBackground(round(0x33452D65, 16));
        errorLabel.setTextIsSelectable(true);
        page.addView(errorLabel, full());
        space(page, 12);
        HorizontalScrollView shortcuts = new HorizontalScrollView(this);
        shortcuts.setHorizontalScrollBarEnabled(false);
        LinearLayout shortcutsRow = row();
        shortcutsRow.addView(action("导入 JSON", v -> importDocument()));
        shortcutsRow.addView(action("编辑当前配置", v -> editActive()));
        shortcutsRow.addView(action("查看活动日志", v -> selectTab(2)));
        shortcuts.addView(shortcutsRow);
        page.addView(shortcuts, full());
    }

    private void buildProfiles() {
        heading("配置", "你的 sing-box JSON，保存在本机私有目录。");
        LinearLayout tools = row();
        tools.addView(action("导入 JSON", v -> importDocument()));
        tools.addView(action("新建配置", v -> showEditor(null, "新配置", "{\n  \"log\": {\n    \"level\": \"info\"\n  },\n  \"inbounds\": [],\n  \"outbounds\": []\n}")));
        page.addView(tools, full());
        TextView note = text("新建模板仅为编辑起点，不含节点或可用 VPN 入站。保存会调用内核检查；连接仍须具备有效的 TUN 配置。运行中修改配置需停止后重新连接。", 13, SOFT);
        note.setPadding(0, dp(10), 0, dp(22));
        page.addView(note, full());
        try {
            List<ProfileStore.Profile> profiles = ProfileStore.list(this);
            if (profiles.isEmpty()) {
                LinearLayout empty = card();
                empty.addView(text("还没有配置", 23, WHITE), full());
                TextView info = text("导入已有的 sing-box JSON，或新建后填写自己的配置。这里不会提供虚假代理节点。", 15, SOFT);
                info.setPadding(0, dp(10), 0, dp(10));
                empty.addView(info, full());
                empty.addView(action("从文件导入", v -> importDocument()));
                page.addView(empty, full());
            }
            String active = ProfileStore.activeId(this);
            for (ProfileStore.Profile profile : profiles) {
                LinearLayout entry = card();
                entry.addView(text(profile.name, 20, WHITE), full());
                TextView badge = text(profile.id.equals(active) ? "● 已选用" : "未选用", 12, SOFT);
                badge.setPadding(0, dp(6), 0, dp(13));
                entry.addView(badge, full());
                LinearLayout buttons = row();
                Button use = action(profile.id.equals(active) ? "当前配置" : "选用", v -> selectProfile(profile));
                use.setEnabled(!profile.id.equals(active));
                buttons.addView(use);
                buttons.addView(action("编辑", v -> openEditor(profile)));
                buttons.addView(action("删除", v -> deleteProfile(profile)));
                entry.addView(buttons, full());
                page.addView(entry, full());
                space(page, 14);
            }
        } catch (Exception e) {
            page.addView(text("读取配置失败：" + safeError(e), 15, WHITE), full());
        }
    }

    private void buildActivity() {
        heading("活动", "实时速率与服务日志，不生成模拟数据。");
        LinearLayout live = card();
        stateLabel = text("", 23, WHITE);
        live.addView(stateLabel, full());
        totalsLabel = text("—", 17, WHITE);
        totalsLabel.setPadding(0, dp(12), 0, dp(6));
        live.addView(totalsLabel, full());
        description = text("", 14, SOFT);
        live.addView(description, full());
        page.addView(live, full());
        space(page, 18);
        LinearLayout logCard = card();
        LinearLayout header = row();
        header.addView(text("服务日志", 20, WHITE), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(action("刷新", v -> { lastLogs = null; refreshStatus(); }));
        logCard.addView(header, full());
        logsLabel = text("暂无日志", 12, SOFT);
        logsLabel.setTypeface(Typeface.MONOSPACE);
        logsLabel.setTextIsSelectable(true);
        logsLabel.setPadding(0, dp(14), 0, 0);
        logCard.addView(logsLabel, full());
        page.addView(logCard, full());
        space(page, 16);
        errorLabel = text("", 14, WHITE);
        errorLabel.setTextIsSelectable(true);
        page.addView(errorLabel, full());
        lastLogs = null;
    }

    private void buildSettings() {
        heading("设置", "内核、VPN 授权与本机信息。");
        LinearLayout engine = card();
        engine.addView(text("sing-box 内核", 21, WHITE), full());
        versionLabel = text(coreVersion, 15, SOFT);
        versionLabel.setTextIsSelectable(true);
        versionLabel.setPadding(0, dp(10), 0, dp(16));
        engine.addView(versionLabel, full());
        engine.addView(text("arm64-v8a · 原生 Android 界面", 13, SOFT), full());
        page.addView(engine, full());
        space(page, 16);
        LinearLayout permission = card();
        permission.addView(text("VPN 授权", 21, WHITE), full());
        TextView explanation = text("连接前由 Android 显示系统 VPN 授权。撤销授权可前往系统 VPN 设置。", 14, SOFT);
        explanation.setPadding(0, dp(10), 0, dp(8));
        permission.addView(explanation, full());
        permission.addView(action("打开系统 VPN 设置", v -> {
            try { startActivity(new Intent("android.settings.VPN_SETTINGS")); }
            catch (Exception e) { showError("无法打开系统设置", e); }
        }));
        page.addView(permission, full());
        space(page, 16);
        LinearLayout privacy = card();
        privacy.addView(text("配置与隐私", 21, WHITE), full());
        TextView privacyText = text("配置保存在应用 files/profiles 私有目录。不会主动导出或上传配置。日志可能包含地址与连接信息，分享前请自行检查。删除应用会清除本机配置。", 14, SOFT);
        privacyText.setPadding(0, dp(10), 0, dp(8));
        privacy.addView(privacyText, full());
        privacy.addView(action("管理配置", v -> selectTab(1)));
        page.addView(privacy, full());
        space(page, 16);
        LinearLayout about = card();
        about.addView(text("开源许可", 21, WHITE), full());
        TextView license = text("sing-box 使用 GNU General Public License v3.0 或更新版本（GPL-3.0-or-later）。源码与完整许可见项目仓库。", 14, SOFT);
        license.setPadding(0, dp(10), 0, dp(8));
        about.addView(license, full());
        about.addView(action("查看项目与许可", v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yuedaochangmendian/sing-box-1/tree/feature/skybox-android-arm64"))); }
            catch (Exception e) { showError("无法打开链接", e); }
        }));
        page.addView(about, full());
        space(page, 16);
        LinearLayout weatherInfo = card();
        weatherInfo.addView(text("天气数据", 21, WHITE), full());
        TextView weatherSource = text("天气与城市搜索来自 Open-Meteo（HTTPS，无需 API Key）。仅使用手动选择的城市坐标，不申请定位权限；同一城市最短 15 分钟刷新一次。天气主题只影响配色与图标，不代表 VPN 质量。", 14, SOFT);
        weatherSource.setPadding(0, dp(10), 0, dp(8));
        weatherInfo.addView(weatherSource, full());
        weatherInfo.addView(action("刷新天气", v -> refreshWeather(true)));
        weatherInfo.addView(action("更换城市", v -> showCityPicker()));
        page.addView(weatherInfo, full());
        space(page, 16);
        LinearLayout errors = card();
        errors.addView(text("最近错误", 21, WHITE), full());
        errorLabel = text("", 14, SOFT);
        errorLabel.setTextIsSelectable(true);
        errorLabel.setPadding(0, dp(10), 0, 0);
        errors.addView(errorLabel, full());
        page.addView(errors, full());
    }

    private void buildDock() {
        dock = row();
        dock.setPadding(dp(T_DOCK_PAD), dp(T_DOCK_PAD), dp(T_DOCK_PAD), dp(T_DOCK_PAD));
        dock.setBackground(round(withAlpha(theme.dockColor, 0xE6), 40));
        dock.setElevation(dp(6));
        String[] names = {"概览", "配置", "活动", "设置"};
        int[] icons = {R.drawable.nav_now, R.drawable.nav_cities,
                R.drawable.nav_activity, R.drawable.nav_settings};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(3), dp(2), dp(3), dp(2));
            item.setBackground(round(i == selectedTab ? withAlpha(theme.iconColor, 0x2E) : Color.TRANSPARENT, 30));
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setColorFilter(i == selectedTab ? theme.iconColor : withAlpha(theme.secondaryTextColor, 0xCC));
            TextView label = text(names[i], 10, i == selectedTab ? theme.iconColor : theme.secondaryTextColor);
            label.setGravity(Gravity.CENTER);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            item.addView(icon, new LinearLayout.LayoutParams(-1, dp(22)));
            item.addView(label, full());
            item.setContentDescription(names[i]);
            item.setOnClickListener(v -> selectTab(index));
            dock.addView(item, new LinearLayout.LayoutParams(0, dp(T_DOCK_SLOT), 1));
            tabLabels[i] = label;
            tabPills[i] = item;
            tabIcons[i] = icon;
        }
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        dockParams.setMargins(dp(16), 0, dp(16), dp(T_DOCK_MARGIN));
        root.addView(dock, dockParams);
        int reserved = T_DOCK_SLOT + T_DOCK_PAD * 2 + T_DOCK_MARGIN + 8;
        page.setPadding(dp(20), dp(16), dp(20), dp(reserved));
    }

    private void selectTab(int tab) {
        if (selectedTab == tab) return;
        selectedTab = tab;
        buildPage();
    }

    private void refreshStatus() {
        if (isFinishing() || isDestroyed()) return;
        boolean running = VpnController.running;
        String state = VpnController.state == null ? "未知状态" : VpnController.state;
        String error = VpnController.error == null ? "" : VpnController.error;
        if (latencyLabel != null) {
            latencyLabel.setText(!running ? "—" : LatencyProbe.testing ? "···" : LatencyProbe.milliseconds >= 0 ? String.valueOf(LatencyProbe.milliseconds) : "—");
            latencyHint.setText(!running ? "连接后点击测试 HTTP 延迟" : LatencyProbe.testing ? "正在测试 HTTP 请求耗时…" : !LatencyProbe.failure.isEmpty() ? "测试失败：" + LatencyProbe.failure : LatencyProbe.fastest >= 0 ? "最快 " + LatencyProbe.fastest + " ms · 最慢 " + LatencyProbe.slowest + " ms" : "点击读数测试 HTTP 延迟");
        }
        if (connectionSwitch != null) {
            renderingSwitch = true;
            connectionSwitch.setChecked(running);
            connectionSwitch.setText(running ? "On  " : "Off  ");
            connectionSwitch.setEnabled(!busy && !permissionPending);
            renderingSwitch = false;
        }
        String activeName = ProfileStore.activeName(this);
        if (profileLabel != null) profileLabel.setText(activeName + "  ﹀");
        if (nodeTitle != null) nodeTitle.setText(activeName);
        if (stateLabel != null) {
            stateLabel.setText((VpnController.running ? "● " : "○ ") + state);
            stateLabel.setTextColor(theme.textColor);
        }
        if (description != null) {
            if (selectedTab == 2) {
                description.setText("下载 " + rate(VpnController.downlink) + "  ·  上传 " + rate(VpnController.uplink));
            } else {
                description.setText(running ? "VPN 服务运行中 · 实际转发由配置决定"
                        : busy ? "正在检查配置…" : permissionPending ? "等待系统 VPN 授权"
                        : "选用配置并开启连接，状态将实时显示。");
            }
        }
        if (totalsLabel != null) {
            String total = "↓ " + bytes(VpnController.downlinkTotal) + "\n↑ " + bytes(VpnController.uplinkTotal);
            totalsLabel.setText(total);
        }
        if (historyLabels != null) {
            long[] samples = VpnController.downloadHistory();
            // Contract: chronological order (oldest -> newest), negative values are unavailable.
            int available = samples == null ? 0 : Math.min(6, samples.length);
            for (int i = 0; i < 6; i++) {
                int source = i - (6 - available);
                long sample = source < 0 ? -1 : samples[samples.length - available + source];
                historyLabels[i].setText(bytes(sample));
                historyIcons[i].setType(sample < 0 ? WeatherIconView.TYPE_UNKNOWN
                        : sample == 0 ? WeatherIconView.TYPE_SUN
                        : sample > 262144 ? WeatherIconView.TYPE_RAIN : WeatherIconView.TYPE_CLOUD);
                historyIcons[i].setTint(sample == 0 ? 0xFFFFD24A : theme.secondaryTextColor);
            }
        }
        if (cityLabel != null) cityLabel.setText(weatherCityName() + "  ﹀");
        if (weatherState != null) {
            boolean has = weatherState.hasData();
            if (temperatureLabel != null) {
                String stamp = has ? weatherState.updatedAt + "/" + weatherState.weatherCode + "/"
                        + weatherState.isDay + "/" + weatherState.loading : "none";
                boolean changed = !stamp.equals(lastWeatherStamp);
                lastWeatherStamp = stamp;
                temperatureLabel.setText(has ? Math.round(weatherState.temperature) + "°" : "—°");
                conditionsLabel.setText(has ? theme.weatherNameZh + " · " + theme.weatherNameEn
                        : weatherState.loading ? "天气加载中…" : "天气暂不可用");
                weatherDetails.setText(has
                        ? "体感 " + Math.round(weatherState.apparentTemperature) + "° · 降水 "
                        + String.format(Locale.ROOT, "%.1f", weatherState.precipitation) + " mm · 风 "
                        + Math.round(weatherState.windSpeed) + " km/h"
                        : "无法读取天气数据，点击下方时间重试");
                weatherUpdated.setText(has ? weatherUpdatedText() : weatherState.errorMessage);
                if (changed) weatherIcon.setWeather(theme);
            }
        }
        if (errorLabel != null) {
            errorLabel.setText(error.isEmpty() ? "暂无服务错误" : error);
            errorLabel.setVisibility(selectedTab == 3 || !error.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (logsLabel != null) {
            String logs = VpnController.logs();
            if (logs == null || logs.isEmpty()) logs = "暂无服务日志。连接后将在这里显示核心输出。";
            if (logs.length() > 60000) logs = "…仅显示最新日志…\n" + logs.substring(logs.length() - 60000);
            if (!logs.equals(lastLogs)) {
                logsLabel.setText(logs);
                lastLogs = logs;
            }
        }
    }

    private void beginStart() {
        if (busy || permissionPending || VpnController.running) return;
        busy = true;
        refreshStatus();
        worker.execute(() -> {
            try {
                String id = ProfileStore.activeId(this);
                String config = ProfileStore.activeConfig(this);
                if (config.isEmpty()) {
                    handler.post(() -> {
                        busy = false;
                        if (isFinishing() || isDestroyed()) return;
                        new AlertDialog.Builder(this).setTitle("先选择有效配置")
                                .setMessage("请导入并选用自己的 sing-box JSON，然后开启连接。")
                                .setPositiveButton("导入 JSON", (dialog, which) -> importDocument())
                                .setNeutralButton("管理配置", (dialog, which) -> selectTab(1))
                                .setNegativeButton("取消", null).show();
                        refreshStatus();
                    });
                    return;
                }
                ProfileStore.validate(getApplicationContext(), config);
                handler.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    pendingConfig = config;
                    pendingId = id;
                    try {
                        Intent authorization = VpnService.prepare(this);
                        if (authorization != null) {
                            permissionPending = true;
                            startActivityForResult(authorization, VPN_PERMISSION);
                        } else startAuthorized();
                    } catch (Exception e) {
                        permissionPending = false;
                        showError("VPN 授权失败", e);
                    }
                    refreshStatus();
                });
            } catch (Exception | LinkageError e) {
                handler.post(() -> {
                    busy = false;
                    if (!isFinishing() && !isDestroyed()) showError("配置检查失败", e);
                    refreshStatus();
                });
            }
        });
    }

    private void startAuthorized() {
        permissionPending = false;
        // Reject a changed profile instead of starting a config other than the one checked.
        if (pendingConfig == null || !pendingConfig.equals(ProfileStore.activeConfig(this))
                || !pendingId.equals(ProfileStore.activeId(this))) {
            pendingConfig = pendingId = null;
            toast("配置已变更，请重新开启连接");
            return;
        }
        pendingConfig = pendingId = null;
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 112);
            }
            Intent intent = new Intent(this, VpnController.class).setAction(VpnController.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            toast("已请求启动，请等待服务状态更新");
        } catch (Exception e) { showError("无法启动 VPN", e); }
    }

    private void stopConnection() {
        pendingConfig = pendingId = null;
        try {
            // A running service already owns its foreground notification; STOP must not create one.
            startService(new Intent(this, VpnController.class).setAction(VpnController.ACTION_STOP));
            toast("已请求停止");
        } catch (Exception e) { showError("无法停止 VPN", e); }
    }

    private void importDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try { startActivityForResult(intent, IMPORT_JSON); }
        catch (Exception e) { showError("无法打开文件选择器", e); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION) {
            permissionPending = false;
            if (resultCode == RESULT_OK) {
                // Activity recreation loses pending validation; request a fresh check safely.
                if (pendingConfig == null) beginStart(); else startAuthorized();
            } else {
                pendingConfig = pendingId = null;
                toast("未授予 VPN 权限，连接未启动");
            }
            refreshStatus();
        } else if (requestCode == IMPORT_JSON && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            toast("正在读取配置…");
            worker.execute(() -> {
                try {
                    String name = "导入配置";
                    try (android.database.Cursor cursor = getContentResolver().query(uri,
                            new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                    }
                    if (name == null || name.trim().isEmpty()) name = "导入配置";
                    if (name.endsWith(".json")) name = name.substring(0, name.length() - 5);
                    final String profileName = name.length() > 80 ? name.substring(0, 80) : name;
                    String content;
                    try (InputStream input = getContentResolver().openInputStream(uri);
                         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        if (input == null) throw new java.io.IOException("无法读取所选文件");
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (output.size() + count > ProfileStore.MAX_CONFIG_BYTES) {
                                throw new java.io.IOException("配置超过 2 MiB 限制");
                            }
                            output.write(buffer, 0, count);
                        }
                        content = new String(output.toByteArray(), StandardCharsets.UTF_8);
                        if (content.startsWith("\uFEFF")) content = content.substring(1);
                    }
                    final String json = content;
                    handler.post(() -> {
                        if (!isFinishing() && !isDestroyed()) showEditor(null, profileName, json);
                    });
                } catch (Exception e) {
                    handler.post(() -> { if (!isFinishing() && !isDestroyed()) showError("导入失败", e); });
                }
            });
        }
    }

    private void showProfilePicker() {
        try {
            List<ProfileStore.Profile> profiles = ProfileStore.list(this);
            String[] names = new String[profiles.size() + 1];
            for (int i = 0; i < profiles.size(); i++) names[i] = profiles.get(i).name
                    + (profiles.get(i).id.equals(ProfileStore.activeId(this)) ? "  ✓" : "");
            names[names.length - 1] = "＋ 导入 / 管理配置";
            new AlertDialog.Builder(this).setTitle("选用配置").setItems(names, (dialog, which) -> {
                if (which == profiles.size()) selectTab(1); else selectProfile(profiles.get(which));
            }).show();
        } catch (Exception e) { showError("无法读取配置", e); }
    }

    private boolean canModify() {
        if (VpnController.running || busy || permissionPending) {
            toast("请先停止连接，并等待当前操作完成后再修改配置");
            return false;
        }
        return true;
    }

    private void selectProfile(ProfileStore.Profile profile) {
        if (!canModify()) return;
        busy = true;
        worker.execute(() -> {
            try {
                ProfileStore.validate(this, ProfileStore.read(this, profile.id));
                ProfileStore.select(this, profile.id);
                handler.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    buildPage();
                    toast("已选用 " + profile.name);
                });
            } catch (Exception | LinkageError e) {
                handler.post(() -> { busy = false; if (!isFinishing() && !isDestroyed()) showError("选用失败", e); });
            }
        });
    }

    private void editActive() {
        if (!canModify()) return;
        String id = ProfileStore.activeId(this);
        if (id.isEmpty()) { selectTab(1); return; }
        try { showEditor(id, ProfileStore.activeName(this), ProfileStore.read(this, id)); }
        catch (Exception e) { showError("无法读取配置", e); }
    }

    private void openEditor(ProfileStore.Profile profile) {
        if (!canModify()) return;
        try { showEditor(profile.id, profile.name, ProfileStore.read(this, profile.id)); }
        catch (Exception e) { showError("无法读取配置", e); }
    }

    private void showEditor(String id, String name, String json) {
        if (!canModify()) return;
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(4));
        EditText nameInput = new EditText(this);
        nameInput.setHint("配置名称");
        nameInput.setText(name);
        nameInput.setSingleLine(true);
        nameInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        content.addView(nameInput, full());
        EditText editor = new EditText(this);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(12);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHorizontallyScrolling(true);
        editor.setText(json);
        editor.setMinLines(10);
        content.addView(editor, new LinearLayout.LayoutParams(-1, dp(290)));
        TextView result = new TextView(this);
        result.setText("保存前将检查 JSON 与 sing-box 配置。配置不会导出到公共目录。");
        result.setTextSize(12);
        content.addView(result, full());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(id == null ? "导入 / 新建配置" : "编辑配置")
                .setView(content).setNegativeButton("取消", null)
                .setNeutralButton("检查", null).setPositiveButton("检查并保存", null).create();
        dialog.setOnShowListener(ignored -> {
            View.OnClickListener validate = clicked -> {
                if (!canModify()) return;
                boolean save = clicked == dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                String editedName = nameInput.getText().toString();
                String editedJson = editor.getText().toString();
                busy = true;
                dialog.setCancelable(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                result.setText("正在调用 sing-box 内核检查…");
                worker.execute(() -> {
                    try {
                        if (save) ProfileStore.save(getApplicationContext(), id, editedName, editedJson);
                        else ProfileStore.validate(getApplicationContext(), editedJson);
                        handler.post(() -> {
                            busy = false;
                            if (isFinishing() || isDestroyed()) return;
                            if (save) {
                                dialog.dismiss();
                                buildPage();
                                toast("配置已通过检查并保存");
                            } else {
                                result.setText("内核检查通过。是否能建立 VPN 仍取决于 TUN、权限与网络环境。");
                                unlockEditor(dialog);
                            }
                        });
                    } catch (Exception | LinkageError e) {
                        handler.post(() -> {
                            busy = false;
                            if (isFinishing() || isDestroyed()) return;
                            result.setText("检查 / 保存失败：" + safeError(e));
                            unlockEditor(dialog);
                        });
                    }
                });
            };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(validate);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(validate);
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void unlockEditor(AlertDialog dialog) {
        dialog.setCancelable(true);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
    }

    private void deleteProfile(ProfileStore.Profile profile) {
        if (!canModify()) return;
        new AlertDialog.Builder(this).setTitle("删除配置？")
                .setMessage("将从本机删除「" + profile.name + "」。此操作不可撤销。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    if (!canModify()) return;
                    try {
                        ProfileStore.delete(this, profile.id);
                        buildPage();
                        toast("配置已删除");
                    } catch (Exception e) { showError("删除失败", e); }
                }).show();
    }

    private void heading(String title, String subtitle) {
        TextView h = text(title, 32, WHITE);
        h.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        page.addView(h, full());
        TextView s = text(subtitle, 14, SOFT);
        s.setPadding(0, dp(7), 0, dp(24));
        page.addView(s, full());
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(withAlpha(theme.cardColor, 0xE8), 22));
        return card;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        int resolved = color == WHITE ? theme.textColor : color == SOFT ? theme.secondaryTextColor : color;
        view.setTextColor(resolved);
        view.setFontFeatureSettings("kern");
        view.setLineSpacing(dp(2), 1);
        return view;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static String themeKey(WeatherTheme value) {
        return value.type.name() + (value.isDay ? "-day" : "-night");
    }

    private String weatherCityName() {
        WeatherService.City city = weatherService == null ? null : weatherService.getCity();
        if (city != null) return city.displayName;
        return weatherState == null || weatherState.city.isEmpty() ? "台北" : weatherState.city;
    }

    private String weatherUpdatedText() {
        long age = Math.max(0L, System.currentTimeMillis() - weatherState.updatedAt);
        String when = weatherState.stale || age >= WeatherService.MIN_REFRESH_INTERVAL_MS
                ? "数据已过期" : "已更新";
        return when + " · 点击刷新";
    }

    private void refreshWeather(boolean force) {
        if (weatherService != null) weatherService.fetch(force);
    }

    private void applyWeather() {
        WeatherTheme next = WeatherTheme.from(weatherState);
        WeatherTheme.apply(this, root, weatherState);
        String key = themeKey(next);
        theme = next;
        if (!key.equals(themeKey)) {
            themeKey = key;
            buildPage(); // Palette changed: rebuild so every card, dock and label picks it up.
            return;
        }
        refreshStatus(); // Same palette: only refresh readings, keeping the scroll position.
    }

    private void showCityPicker() {
        LinearLayout panel = column();
        panel.setPadding(dp(18), dp(8), dp(18), 0);
        EditText input = new EditText(this);
        cityInput = input;
        input.setHint("搜索城市，例如 台北市 / Tokyo");
        input.setSingleLine(true);
        panel.addView(input, full());
        citySearchStatus = new TextView(this);
        citySearchStatus.setTextSize(12);
        citySearchStatus.setText("输入城市名后会自动搜索");
        panel.addView(citySearchStatus, full());
        cityResults = column();
        ScrollView resultsScroll = new ScrollView(this);
        resultsScroll.addView(cityResults, new ScrollView.LayoutParams(-1, -2));
        panel.addView(resultsScroll, new LinearLayout.LayoutParams(-1, dp(240)));
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(android.text.Editable s) {
                String query = s.toString().trim();
                if (weatherService == null) return;
                citySearchStatus.setText(query.isEmpty() ? "输入城市名后会自动搜索" : "正在搜索…");
                weatherService.searchCity(query);
            }
        });
        cityDialog = new AlertDialog.Builder(this)
                .setTitle("选择天气城市")
                .setView(panel)
                .setNegativeButton("取消", null)
                .setNeutralButton("使用当前位置天气数据源默认城市", (dialog, which) -> {
                    weatherService.setCity(WeatherService.DEFAULT_CITY);
                    weatherSearchRefresh();
                })
                .create();
        cityDialog.setOnDismissListener(d -> cityDialog = null);
        cityDialog.show();
        weatherService.searchCity(input.getText().toString());
    }

    private void weatherSearchRefresh() {
        refreshWeather(true);
        if (weatherState != null) applyWeather();
    }

    private void showCityResults(List<WeatherService.City> cities) {
        if (cityResults == null || isFinishing() || isDestroyed()) return;
        cityResults.removeAllViews();
        if (cities == null || cities.isEmpty()) {
            boolean emptyQuery = cityInput == null || cityInput.getText().toString().trim().isEmpty();
            citySearchStatus.setText(emptyQuery ? "输入城市名后会自动搜索" : "没有找到匹配城市");
            return;
        }
        citySearchStatus.setText("找到 " + cities.size() + " 个结果");
        for (WeatherService.City city : cities) {
            TextView item = text(city.displayName + (city.country.isEmpty() ? "" : " · " + city.country), 15, theme.textColor);
            item.setPadding(dp(10), dp(12), dp(10), dp(12));
            item.setBackground(round(withAlpha(theme.cardColor, 0xE8), 14));
            item.setOnClickListener(v -> {
                weatherService.setCity(city);
                if (cityDialog != null) cityDialog.dismiss();
                refreshWeather(true);
            });
            LinearLayout.LayoutParams params = full();
            params.bottomMargin = dp(6);
            cityResults.addView(item, params);
        }
    }

    private Button action(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(WHITE);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        android.content.res.ColorStateList ripple = android.content.res.ColorStateList.valueOf(0x33FFFFFF);
        button.setBackground(new android.graphics.drawable.RippleDrawable(ripple, round(0x24FFFFFF, 24), round(WHITE, 24)));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void space(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void showError(String title, Throwable e) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(safeError(e)).setPositiveButton("知道了", null).show();
    }
    private static String safeError(Throwable e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
    private static String bytes(long value) {
        if (value < 0) return "—";
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", value / 1024.0);
        if (value < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", value / (1024.0 * 1024 * 1024));
    }
    private static String rate(long value) { return value < 0 ? "—" : bytes(value) + "/s"; }
}
