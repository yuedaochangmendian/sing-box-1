package io.github.yuedaochangmendian.skybox

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.nekohasekai.libbox.Libbox
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Native, dependency-free sky dashboard. The upper half is real weather for the node's region
 * (exit-IP geolocation, with a manual city override); the lower half mirrors the connection:
 * a live speed curve, a latency pill, a start/stop pill and the policy-group selector.
 * All connection information comes from VpnController.
 */
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var weatherScroll: ScrollView
    private lateinit var panel: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var backdrop: SkyBackdropView
    private lateinit var floatLeft: View
    private lateinit var floatRight: View

    private var panelOpen = false

    // Weather screen views; dialogs in SkyDialogs.kt reuse several of them.
    internal var cityTitle: TextView? = null
    internal var citySubtitle: TextView? = null
    private var bigTemp: TextView? = null
    private var conditionLine: TextView? = null
    private var latencyPillText: TextView? = null
    private var startPillText: TextView? = null
    private var startPillIcon: ImageView? = null
    private var speedSentence: TextView? = null
    private var speedChart: PrecipitationChartView? = null
    private var groupsRows: LinearLayout? = null
    private var weatherError: TextView? = null
    private var renderedGroupRevision = -1L
    private val expandedGroups = HashSet<String>()
    private var staticGroupCache: Pair<String, List<GroupSnapshot>>? = null

    internal val handler = Handler(Looper.getMainLooper())
    internal val worker: ExecutorService = Executors.newSingleThreadExecutor()

    // Panel (配置/活动/设置) shared labels.
    private var stateLabel: TextView? = null
    private var description: TextView? = null
    private var totalsLabel: TextView? = null
    private var errorLabel: TextView? = null
    private var logsLabel: TextView? = null
    private var versionLabel: TextView? = null
    internal var weatherService: WeatherService? = null
    internal var weatherState: WeatherState? = null
    internal var theme: WeatherTheme = WeatherTheme.from(null)
    private var themeKey = ""
    internal var cityDialog: android.app.AlertDialog? = null
    internal var cityResults: LinearLayout? = null
    internal var cityInput: EditText? = null
    internal var citySearchStatus: TextView? = null
    internal var busy = false
    private var permissionPending = false
    private var pendingConfig: String? = null
    private var pendingId: String? = null
    private var coreVersion = "正在读取…"
    private var resumed = false
    private var lastLogs: String? = null
    private var tickCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            tickCount++
            keepReadingsFresh()
            refreshStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restores the dock selection together with the "tab" entry written in onSaveInstanceState.
        if (savedInstanceState != null) {
            selectedTab = savedInstanceState.getInt("tab", 0).coerceIn(0, 2)
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = 0xFF72B3E9.toInt()
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF3383D4.toInt(), 0xFF5F9FE0.toInt(), 0xFF8EC3EC.toInt()),
        )
        root.setOnApplyWindowInsetsListener { _, insets ->
            var left = insets.systemWindowInsetLeft
            var top = insets.systemWindowInsetTop
            var right = insets.systemWindowInsetRight
            var bottom = insets.systemWindowInsetBottom
            if (Build.VERSION.SDK_INT >= 30) {
                val safe: Insets = insets.getInsets(
                    WindowInsets.Type.systemBars()
                        or WindowInsets.Type.displayCutout()
                        or WindowInsets.Type.ime(),
                )
                left = safe.left
                top = safe.top
                right = safe.right
                bottom = safe.bottom
            }
            root.setPadding(left, top, right, bottom)
            insets
        }
        backdrop = SkyBackdropView(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        weatherScroll = ScrollView(this)
        weatherScroll.isFillViewport = true
        weatherScroll.isVerticalScrollBarEnabled = false
        weatherScroll.clipToPadding = false
        root.addView(weatherScroll, FrameLayout.LayoutParams(-1, -1))
        panel = FrameLayout(this)
        panel.visibility = View.GONE
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        buildFloatingButtons()
        setContentView(root)
        root.requestApplyInsets()
        val service = WeatherService(this, object : WeatherService.Callback {
            override fun onWeather(state: WeatherState) {
                if (isFinishing || isDestroyed) return
                weatherState = state
                applyWeather()
            }

            override fun onCities(cities: List<WeatherService.City>) {
                showCityResults(cities)
            }

            override fun onError(message: String, citySearch: Boolean) {
                if (isFinishing || isDestroyed) return
                if (citySearch && cityDialog?.isShowing == true) citySearchStatus?.text = message
            }
        })
        weatherService = service
        weatherState = service.getState()
        theme = WeatherTheme.from(weatherState)
        themeKey = themeKeyOf(theme)
        WeatherTheme.apply(this, root, weatherState)
        backdrop.setTheme(theme)
        buildWeatherScreen()
        buildPanel()
        VpnController.observeGroups(this) { if (!isFinishing && !isDestroyed) renderGroups() }
        worker.execute {
            try {
                CoreRuntime.ensureInit(applicationContext)
                coreVersion = Libbox.version()
            } catch (e: Throwable) {
                coreVersion = "不可用：" + safeError(e)
            }
            handler.post {
                if (!isFinishing && !isDestroyed) versionLabel?.text = coreVersion
            }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("tab", selectedTab)
        super.onSaveInstanceState(state)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        weatherService?.fetch(false)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val dialog = cityDialog
        if (dialog != null && dialog.isShowing) dialog.dismiss()
        weatherService?.close()
        VpnController.releaseGroups(this)
        worker.shutdown()
        super.onDestroy()
    }

    internal fun buildPanel() {
        stateLabel = null
        description = null
        totalsLabel = null
        errorLabel = null
        logsLabel = null
        versionLabel = null
        panel.removeAllViews()
        panel.background = theme.newBackground()
        scroll = ScrollView(this)
        scroll.setFillViewport(true)
        scroll.setClipToPadding(false)
        scroll.setVerticalScrollBarEnabled(false)
        page = column()
        page.setPadding(dp(20), dp(52), dp(20), dp(118))
        scroll.addView(page, FrameLayout.LayoutParams(-1, -2))
        panel.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        when (selectedTab) {
            0 -> buildProfiles()
            1 -> buildActivity()
            else -> buildSettings()
        }
        buildDock()
        buildPanelClose()
        refreshStatus()
    }

    /** Circular ✕ over the panel; the system back gesture closes it as well. */
    private fun buildPanelClose() {
        val close = text("✕", 17f, WHITE)
        close.setGravity(Gravity.CENTER)
        close.applyLiquidGlass(
            style = Glass.Style.CLEAR,
            radiusDp = Glass.RADIUS_CONTROL_DP,
            surface = theme.cardColor,
            surfaceAlpha = 0.62f,
            dark = !theme.isDay,
        )
        close.contentDescription = "关闭配置面板"
        close.setOnClickListener { closePanel() }
        val params = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END)
        params.topMargin = dp(14)
        params.rightMargin = dp(18)
        panel.addView(close, params)
    }

    private fun openPanel() {
        panelOpen = true
        panel.visibility = View.VISIBLE
        floatLeft.visibility = View.GONE
        floatRight.visibility = View.GONE
        buildPanel()
    }

    private fun closePanel() {
        panelOpen = false
        panel.visibility = View.GONE
        floatLeft.visibility = View.VISIBLE
        floatRight.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        if (panelOpen) {
            closePanel()
            return
        }
        super.onBackPressed()
    }

    /**
     * The two floating circles. Both stay anchored to their corner; tapping opens a popup that
     * grows out of the button instead of swapping the whole screen.
     */
    private fun buildFloatingButtons() {
        val size = dp(54)
        val left = ImageView(this)
        left.setImageResource(R.drawable.ic_panel)
        left.scaleType = ImageView.ScaleType.CENTER
        left.setColorFilter(theme.iconColor)
        left.applyLiquidGlass(
            style = Glass.Style.CLEAR,
            radiusDp = 27,
            surface = theme.cardColor,
            surfaceAlpha = 0.78f,
            dark = !theme.isDay,
            elevationDp = 6,
        )
        left.contentDescription = "打开菜单"
        left.setOnClickListener {
            showAnchoredMenu(
                left,
                listOf(
                    "配置管理" to { openPanelToTab(0) },
                    "活动日志" to { openPanelToTab(1) },
                    "设置" to { openPanelToTab(2) },
                    "天气详情" to { showWeatherDetail() },
                    "刷新节点地区" to { refreshNodeRegion(true) },
                ),
            )
        }
        val leftParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START)
        leftParams.leftMargin = dp(20)
        leftParams.bottomMargin = dp(22)
        root.addView(left, leftParams)
        floatLeft = left

        val right = ImageView(this)
        right.setImageResource(R.drawable.ic_add)
        right.scaleType = ImageView.ScaleType.CENTER
        right.setColorFilter(theme.iconColor)
        right.applyLiquidGlass(
            style = Glass.Style.CLEAR,
            radiusDp = 27,
            surface = theme.cardColor,
            surfaceAlpha = 0.78f,
            dark = !theme.isDay,
            elevationDp = 6,
        )
        right.contentDescription = "添加配置"
        right.setOnClickListener {
            showAnchoredMenu(
                right,
                listOf(
                    "从文件导入 JSON" to { importDocument() },
                    "新建空白配置" to {
                        openPanelToTab(0)
                        showEditor(
                            null,
                            "新配置",
                            "{\n  \"log\": {\n    \"level\": \"info\"\n  },\n  \"inbounds\": [],\n  \"outbounds\": []\n}",
                        )
                    },
                ),
            )
        }
        val rightParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END)
        rightParams.rightMargin = dp(20)
        rightParams.bottomMargin = dp(22)
        root.addView(right, rightParams)
        floatRight = right
    }

    private fun paintFloatingButtons() {
        // Theme updates can arrive before the circles exist; nothing to repaint in that case.
        if (!::floatLeft.isInitialized || !::floatRight.isInitialized) return
        for (button in arrayOf(floatLeft, floatRight)) {
            button.applyLiquidGlass(
                style = Glass.Style.CLEAR,
                radiusDp = 27,
                surface = theme.cardColor,
                surfaceAlpha = 0.78f,
                dark = !theme.isDay,
                elevationDp = 6,
            )
        }
    }

    private fun openPanelToTab(tab: Int) {
        openPanel()
        if (selectedTab == tab) return
        selectedTab = tab
        buildPanel()
    }

    private fun buildWeatherScreen() {
        cityTitle = null
        citySubtitle = null
        bigTemp = null
        conditionLine = null
        latencyPillText = null
        startPillText = null
        startPillIcon = null
        speedSentence = null
        speedChart = null
        groupsRows = null
        weatherError = null
        renderedGroupRevision = -1L
        val content = column()
        content.setPadding(dp(16), dp(22), dp(16), dp(148))
        weatherScroll.removeAllViews()
        weatherScroll.addView(content, FrameLayout.LayoutParams(-1, -2))

        val title = text(regionName(), 25f, WHITE)
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        cityTitle = title
        content.addView(title, full())

        val subtitle = text(regionSubtitle(), 13f, withAlpha(theme.secondaryTextColor, 0xD0))
        subtitle.setPadding(0, dp(7), 0, 0)
        subtitle.contentDescription = "选择天气城市"
        subtitle.setOnClickListener { showCityPicker() }
        citySubtitle = subtitle
        content.addView(subtitle, full())

        space(content, 12)
        val reading = LinearLayout(this)
        reading.orientation = LinearLayout.HORIZONTAL
        reading.gravity = Gravity.TOP
        val temperature = text("—", 104f, WHITE)
        temperature.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL))
        temperature.setIncludeFontPadding(false)
        temperature.contentDescription = "查看天气详情"
        temperature.setOnClickListener { showWeatherDetail() }
        bigTemp = temperature
        reading.addView(temperature, LinearLayout.LayoutParams(-2, -2))
        content.addView(reading, full())

        val conditions = text("天气加载中…", 16f, withAlpha(theme.textColor, 0xE6))
        conditions.setPadding(0, dp(2), 0, 0)
        conditions.setOnClickListener { showWeatherDetail() }
        conditionLine = conditions
        content.addView(conditions, full())

        val error = text("", 12f, withAlpha(theme.textColor, 0xCC))
        error.setPadding(0, dp(6), 0, 0)
        error.visibility = View.GONE
        weatherError = error
        content.addView(error, full())

        space(content, 14)
        val pills = row()
        val latency = pill(R.drawable.ic_speed, "延迟 —")
        latency.setOnClickListener { onLatencyPillClicked() }
        latencyPillText = latency.getChildAt(1) as TextView
        pills.addView(latency, LinearLayout.LayoutParams(-2, dp(40)))
        val start = pill(R.drawable.ic_play, "启动", Glass.Style.TINTED)
        start.setOnClickListener {
            if (VpnController.running) stopConnection() else beginStart()
        }
        startPillText = start.getChildAt(1) as TextView
        startPillIcon = start.getChildAt(0) as ImageView
        val startParams = LinearLayout.LayoutParams(-2, dp(40))
        startParams.leftMargin = dp(10)
        pills.addView(start, startParams)
        content.addView(pills, full())

        space(content, 72)
        val speed = glassCard(mix(theme.cardColor, theme.getGradientColors()[0], 0.30f), 0.78f)
        val sentence = text("未连接 · 连接后显示实时网速", 15f, theme.textColor)
        speedSentence = sentence
        speed.addView(sentence, full())
        val chartRow = row()
        chartRow.setPadding(0, dp(12), 0, 0)
        val chart = PrecipitationChartView(this)
        chart.setBarColors(0xF2BEE5FF.toInt(), 0x8C7FBDF0.toInt())
        chart.setGridColor(withAlpha(theme.textColor, 0x26))
        // Fixed 10-minute window (60 × 10 s), newest bar on the right edge; the timeline labels
        // below (10分钟前 / 5分钟前 / 现在) only stay true with a fixed slot count.
        chart.setSlotCount(VpnController.speedSampleCapacity())
        chart.setSeparatorSlots(30)
        speedChart = chart
        chartRow.addView(chart, LinearLayout.LayoutParams(0, dp(92), 1f))
        val axis = column()
        axis.setGravity(Gravity.CENTER_VERTICAL)
        for (label in arrayOf("快", "中", "慢")) {
            val tick = text(label, 11f, withAlpha(theme.secondaryTextColor, 0xAA))
            tick.setGravity(Gravity.CENTER)
            axis.addView(tick, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val axisParams = LinearLayout.LayoutParams(dp(26), dp(92))
        axisParams.leftMargin = dp(6)
        chartRow.addView(axis, axisParams)
        speed.addView(chartRow, full())
        val timeline = row()
        timeline.setPadding(0, dp(8), dp(32), 0)
        val marks = arrayOf("10分钟前", "5分钟前", "现在")
        val gravities = intArrayOf(Gravity.START, Gravity.CENTER, Gravity.END)
        for (i in marks.indices) {
            val mark = text(marks[i], 11f, withAlpha(theme.secondaryTextColor, 0xCC))
            mark.setGravity(gravities[i])
            timeline.addView(mark, LinearLayout.LayoutParams(0, -2, 1f))
        }
        speed.addView(timeline, full())
        content.addView(speed, full())

        space(content, 14)
        val groups = glassCard(theme.cardColor, 0.60f)
        groups.setPadding(dp(16), dp(6), dp(16), dp(6))
        val rows = column()
        groupsRows = rows
        groups.addView(rows, full())
        content.addView(groups, full())
    }

    /**
     * Capsule control in the reference kit: CLEAR for secondary pills (latency), TINTED for the
     * primary action. Tinted surfaces switch to white glyphs, since the accent is dark enough that
     * the weather text colour would lose contrast.
     */
    private fun pill(iconRes: Int, label: String, style: Glass.Style = Glass.Style.CLEAR): LinearLayout {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL
        container.setPadding(dp(14), 0, dp(16), 0)
        val tinted = style == Glass.Style.TINTED
        container.applyLiquidGlass(
            style = style,
            radiusDp = Glass.RADIUS_CONTROL_DP,
            surface = if (tinted) Glass.accent(!theme.isDay) else theme.cardColor,
            surfaceAlpha = if (tinted) 0.92f else 0.66f,
            dark = !theme.isDay,
        )
        val icon = ImageView(this)
        icon.setImageResource(iconRes)
        icon.setColorFilter(if (tinted) Color.WHITE else theme.textColor)
        container.addView(icon, LinearLayout.LayoutParams(dp(15), dp(15)))
        val value = text(label, 14f, if (tinted) 0xF7FFFFFF.toInt() else WHITE)
        val params = LinearLayout.LayoutParams(-2, -2)
        params.leftMargin = dp(7)
        container.addView(value, params)
        return container
    }

    /** Frosted glass card; [surfaceAlpha] keeps the sky visible without washing out the content. */
    private fun glassCard(surface: Int, surfaceAlpha: Float): LinearLayout {
        val container = column()
        container.setPadding(dp(16), dp(15), dp(16), dp(15))
        container.applyLiquidGlassSurface(
            style = Glass.Style.REGULAR,
            radiusDp = Glass.RADIUS_CARD_DP,
            surface = surface,
            surfaceAlpha = surfaceAlpha,
            dark = !theme.isDay,
        )
        return container
    }

    private fun buildProfiles() {
        heading("配置", "你的 sing-box JSON，保存在本机私有目录。")
        val tools = row()
        tools.addView(action("导入 JSON") { importDocument() })
        tools.addView(
            action("新建配置") {
                showEditor(
                    null,
                    "新配置",
                    "{\n  \"log\": {\n    \"level\": \"info\"\n  },\n  \"inbounds\": [],\n  \"outbounds\": []\n}",
                )
            },
        )
        page.addView(tools, full())
        val note = text(
            "新建模板仅为编辑起点，不含节点或可用 VPN 入站。保存会调用内核检查；连接仍须具备有效的 TUN 配置。" +
                "运行中修改配置需停止后重新连接。",
            13f,
            SOFT,
        )
        note.setPadding(0, dp(10), 0, dp(22))
        page.addView(note, full())
        try {
            val profiles = ProfileStore.list(this)
            if (profiles.isEmpty()) {
                val empty = card()
                empty.addView(text("还没有配置", 23f, WHITE), full())
                val info = text("导入已有的 sing-box JSON，或新建后填写自己的配置。这里不会提供虚假代理节点。", 15f, SOFT)
                info.setPadding(0, dp(10), 0, dp(10))
                empty.addView(info, full())
                empty.addView(action("从文件导入") { importDocument() })
                page.addView(empty, full())
            }
            val active = ProfileStore.activeId(this)
            for (profile in profiles) {
                val entry = card()
                entry.addView(text(profile.name, 20f, WHITE), full())
                val isActive = profile.id == active
                val badge = text(if (isActive) "● 已选用" else "未选用", 12f, SOFT)
                badge.setPadding(0, dp(6), 0, dp(13))
                entry.addView(badge, full())
                val buttons = row()
                val use = action(if (isActive) "当前配置" else "选用") { selectProfile(profile) }
                use.isEnabled = !isActive
                buttons.addView(use)
                buttons.addView(action("编辑") { openEditor(profile) })
                buttons.addView(action("删除") { deleteProfile(profile) })
                entry.addView(buttons, full())
                page.addView(entry, full())
                space(page, 14)
            }
        } catch (e: Exception) {
            page.addView(text("读取配置失败：" + safeError(e), 15f, WHITE), full())
        }
    }

    private fun buildActivity() {
        heading("活动", "实时速率与服务日志，不生成模拟数据。")
        val live = card()
        val state = text("", 23f, WHITE)
        stateLabel = state
        live.addView(state, full())
        val totals = text("—", 17f, WHITE)
        totals.setPadding(0, dp(12), 0, dp(6))
        totalsLabel = totals
        live.addView(totals, full())
        val desc = text("", 14f, SOFT)
        description = desc
        live.addView(desc, full())
        page.addView(live, full())
        space(page, 18)
        val logCard = card()
        val header = row()
        header.addView(text("服务日志", 20f, WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            action("刷新") {
                lastLogs = null
                refreshStatus()
            },
        )
        logCard.addView(header, full())
        val logs = text("暂无日志", 12f, SOFT)
        logs.setTypeface(Typeface.MONOSPACE)
        logs.setTextIsSelectable(true)
        logs.setPadding(0, dp(14), 0, 0)
        logsLabel = logs
        logCard.addView(logs, full())
        page.addView(logCard, full())
        space(page, 16)
        val error = text("", 14f, WHITE)
        error.setTextIsSelectable(true)
        errorLabel = error
        page.addView(error, full())
        lastLogs = null
    }

    private fun buildSettings() {
        heading("设置", "内核、VPN 授权与本机信息。")
        val engine = card()
        engine.addView(text("sing-box 内核", 21f, WHITE), full())
        val version = text(coreVersion, 15f, SOFT)
        version.setTextIsSelectable(true)
        version.setPadding(0, dp(10), 0, dp(16))
        versionLabel = version
        engine.addView(version, full())
        engine.addView(text("arm64-v8a · 原生 Android 界面", 13f, SOFT), full())
        page.addView(engine, full())
        space(page, 16)
        val permission = card()
        permission.addView(text("VPN 授权", 21f, WHITE), full())
        val explanation = text("连接前由 Android 显示系统 VPN 授权。撤销授权可前往系统 VPN 设置。", 14f, SOFT)
        explanation.setPadding(0, dp(10), 0, dp(8))
        permission.addView(explanation, full())
        permission.addView(
            action("打开系统 VPN 设置") {
                try {
                    startActivity(Intent("android.settings.VPN_SETTINGS"))
                } catch (e: Exception) {
                    showError("无法打开系统设置", e)
                }
            },
        )
        page.addView(permission, full())
        space(page, 16)
        val privacy = card()
        privacy.addView(text("配置与隐私", 21f, WHITE), full())
        val privacyText = text(
            "配置保存在应用 files/profiles 私有目录。不会主动导出或上传配置。日志可能包含地址与连接信息，" +
                "分享前请自行检查。删除应用会清除本机配置。",
            14f,
            SOFT,
        )
        privacyText.setPadding(0, dp(10), 0, dp(8))
        privacy.addView(privacyText, full())
        privacy.addView(action("管理配置") { openPanelToTab(0) })
        page.addView(privacy, full())
        space(page, 16)
        val about = card()
        about.addView(text("开源许可", 21f, WHITE), full())
        val license = text(
            "sing-box 使用 GNU General Public License v3.0 或更新版本（GPL-3.0-or-later）。源码与完整许可见项目仓库。",
            14f,
            SOFT,
        )
        license.setPadding(0, dp(10), 0, dp(8))
        about.addView(license, full())
        about.addView(
            action("查看项目与许可") {
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/yuedaochangmendian/sing-box-1/tree/feature/skybox-android-arm64"),
                        ),
                    )
                } catch (e: Exception) {
                    showError("无法打开链接", e)
                }
            },
        )
        page.addView(about, full())
        space(page, 16)
        val weatherInfo = card()
        weatherInfo.addView(text("天气与地区", 21f, WHITE), full())
        val weatherSource = text(
            "天气、小时预报与空气质量来自 Open-Meteo（HTTPS，无需 API Key）。连接 VPN 时首页地区自动跟随" +
                "节点出口 IP 所在地（ipwho.is / ipapi.co），手动选择城市后以手动为准；同一坐标最短 15 分钟刷新。" +
                "天气主题只影响配色与图标，不代表 VPN 质量。",
            14f,
            SOFT,
        )
        weatherSource.setPadding(0, dp(10), 0, dp(8))
        weatherInfo.addView(weatherSource, full())
        weatherInfo.addView(action("刷新天气") { refreshWeather(true) })
        weatherInfo.addView(action("更换城市") { showCityPicker() })
        weatherInfo.addView(action("重新检测节点地区") { refreshNodeRegion(true) })
        page.addView(weatherInfo, full())
        space(page, 16)
        val errors = card()
        errors.addView(text("最近错误", 21f, WHITE), full())
        val error = text("", 14f, SOFT)
        error.setTextIsSelectable(true)
        error.setPadding(0, dp(10), 0, 0)
        errorLabel = error
        errors.addView(error, full())
        page.addView(errors, full())
    }

    private fun buildDock() {
        val bar = row()
        bar.setPadding(dp(T_DOCK_PAD), dp(T_DOCK_PAD), dp(T_DOCK_PAD), dp(T_DOCK_PAD))
        bar.applyLiquidGlass(
            style = Glass.Style.CLEAR,
            radiusDp = Glass.RADIUS_DOCK_DP,
            surface = theme.dockColor,
            surfaceAlpha = 0.84f,
            dark = !theme.isDay,
            elevationDp = 6,
        )
        val names = arrayOf("配置", "活动", "设置")
        val icons = intArrayOf(
            R.drawable.nav_cities,
            R.drawable.nav_activity,
            R.drawable.nav_settings,
        )
        for (i in names.indices) {
            val index = i
            val item = column()
            item.setGravity(Gravity.CENTER)
            item.setPadding(dp(3), dp(2), dp(3), dp(2))
            item.background = round(
                if (i == selectedTab) withAlpha(theme.iconColor, 0x2E) else Color.TRANSPARENT,
                30,
            )
            val icon = ImageView(this)
            icon.setImageResource(icons[i])
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER)
            icon.setColorFilter(if (i == selectedTab) theme.iconColor else withAlpha(theme.secondaryTextColor, 0xCC))
            val label = text(names[i], 10f, if (i == selectedTab) theme.iconColor else theme.secondaryTextColor)
            label.setGravity(Gravity.CENTER)
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            item.addView(icon, LinearLayout.LayoutParams(-1, dp(22)))
            item.addView(label, full())
            item.contentDescription = names[i]
            item.setOnClickListener { openPanelToTab(index) }
            bar.addView(item, LinearLayout.LayoutParams(0, dp(T_DOCK_SLOT), 1f))
            tabLabels[i] = label
            tabPills[i] = item
            tabIcons[i] = icon
        }
        val dockParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        dockParams.setMargins(dp(16), 0, dp(16), dp(T_DOCK_MARGIN))
        panel.addView(bar, dockParams)
        val reserved = T_DOCK_SLOT + T_DOCK_PAD * 2 + T_DOCK_MARGIN + 8
        page.setPadding(dp(20), dp(52), dp(20), dp(reserved))
    }

    private val tabIcons = arrayOfNulls<ImageView>(3)
    private val tabLabels = arrayOfNulls<TextView>(3)
    private val tabPills = arrayOfNulls<View>(3)
    private var selectedTab = 0

    private fun regionName(): String {
        val node = NodeLocation.last
        if (node != null && node.isValid && NodeLocation.fromNode) return node.displayName()
        val service = weatherService
        if (service != null) return service.getCity().displayName
        val state = weatherState
        return if (state == null || state.city.isEmpty()) "未知地区" else state.city
    }

    private fun regionSubtitle(): String = when {
        NodeLocation.last?.isValid == true && NodeLocation.fromNode ->
            if (weatherService?.isManual() == true) "节点地区 · 手动城市优先，点击更换" else "节点地区 · 点击更换城市"
        else -> "连接后自动获取节点地区 · 或点击选择城市"
    }

    private fun onLatencyPillClicked() {
        if (!VpnController.running) {
            toast("请先开启 VPN 连接")
            return
        }
        LatencyProbe.test {
            handler.post { if (!isFinishing && !isDestroyed) refreshStatus() }
        }
        showLatencyDetail()
    }

    /**
     * Automatic refresh cadence from the 1-second ticker: one latency probe and one node-region
     * probe every 30 s / 5 min while connected, plus an immediate pair when the tunnel comes up.
     * The "came up" edge is tracked in a process-wide flag, so merely opening the app while the
     * tunnel is already up no longer re-runs the detection toast.
     */
    private fun keepReadingsFresh() {
        val running = VpnController.running
        if (running && !lastRunningObserved) {
            LatencyProbe.test()
            refreshNodeRegion(true)
        }
        lastRunningObserved = running
        if (!running) return
        if (tickCount % 30 == 0 && !LatencyProbe.testing) LatencyProbe.test()
        // Throttled by the probe itself, which also survives an activity recreation.
        if (!NodeLocation.probing && System.currentTimeMillis() - NodeLocation.lastAttemptAt > FIVE_MINUTES_MS) {
            refreshNodeRegion(false)
        }
    }

    /** manual=true always toasts; otherwise the caller's throttle decides. */
    internal fun refreshNodeRegion(manual: Boolean) {
        if (NodeLocation.probing) return
        if (manual) toast("正在检测节点地区…")
        NodeLocation.probe {
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (NodeLocation.routeChanged) {
                    // The tunnel went up or down mid-request; the answer describes the other route.
                    if (manual) toast("网络刚切换，稍后重新检测节点地区")
                    return@post
                }
                val node = NodeLocation.last
                if (node == null || !node.isValid) {
                    if (manual) toast("节点地区检测失败：" + NodeLocation.lastError)
                    return@post
                }
                // Last line of defence: never attach a reading to the wrong route.
                if (NodeLocation.fromNode != VpnController.running) return@post
                applyNodeLocation(node)
            }
        }
    }

    private fun applyNodeLocation(node: NodeLocation.Location) {
        val service = weatherService
        if (service != null && !service.isManual()) {
            val name = node.city.ifEmpty { node.country }.ifEmpty { "节点地区" }
            try {
                service.setCity(
                    WeatherService.City(
                        name, node.country, node.latitude, node.longitude,
                        node.timezone.ifEmpty { "auto" },
                    ),
                    manual = false,
                )
            } catch (ignored: IllegalArgumentException) {
                // A provider glitch outside the coordinate range must not crash the home screen.
            }
        }
        cityTitle?.text = regionName()
        citySubtitle?.text = regionSubtitle()
    }

    internal fun renderGroups() {
        val rows = groupsRows ?: return
        val live = VpnGroups.groups
        val snapshots = if (live.isNotEmpty()) live else staticGroups()
        rows.removeAllViews()
        if (snapshots.isEmpty()) {
            val hint = text("当前配置没有策略组。导入包含 selector 或 urltest 出站的配置后，可在这里直接切换节点。", 13f, SOFT)
            hint.setPadding(0, dp(12), 0, dp(12))
            rows.addView(hint, full())
            return
        }
        if (live.isEmpty()) {
            // A connected config can still fall back to the static list (for example while the
            // group stream is still connecting), so saying "未连接" there is simply wrong.
            val note = text(
                if (VpnController.running) {
                    "策略组状态同步中 · 显示配置中的策略组"
                } else {
                    "未连接 · 显示配置中的策略组，连接后可切换与测速"
                },
                12f,
                SOFT,
            )
            note.setPadding(0, dp(8), 0, dp(4))
            rows.addView(note, full())
        }
        for ((index, group) in snapshots.withIndex()) {
            if (index > 0) {
                val divider = View(this)
                divider.setBackgroundColor(withAlpha(theme.textColor, 0x1F))
                rows.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            }
            val expanded = group.tag in expandedGroups
            val header = row()
            header.setPadding(0, dp(11), 0, dp(11))
            val name = text(group.tag, 15f, WHITE)
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            header.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            val summary = text(groupSummary(group), 12f, SOFT)
            summary.setGravity(Gravity.END)
            header.addView(summary, LinearLayout.LayoutParams(-2, -2))
            val chevron = text(if (expanded) "▾" else "▸", 13f, SOFT)
            val chevronParams = LinearLayout.LayoutParams(dp(18), -2)
            chevronParams.leftMargin = dp(8)
            header.addView(chevron, chevronParams)
            header.contentDescription = group.tag
            header.setOnClickListener {
                if (expanded) expandedGroups.remove(group.tag) else expandedGroups.add(group.tag)
                renderGroups()
            }
            rows.addView(header, full())
            if (!expanded) continue
            if (live.isNotEmpty() && group.selectable && group.items.isNotEmpty()) {
                val tools = row()
                tools.setPadding(0, 0, 0, dp(6))
                tools.addView(action("测速") {
                    for (item in group.items) VpnController.urlTest(item.tag)
                    toast("正在测速…")
                })
                rows.addView(tools, full())
            }
            for (item in group.items) {
                val line = row()
                line.setPadding(dp(10), dp(7), dp(10), dp(7))
                line.background = round(withAlpha(theme.textColor, 0x14), 12)
                val label = text(item.tag, 14f, WHITE)
                label.setSingleLine(true)
                label.ellipsize = TextUtils.TruncateAt.MIDDLE
                line.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                val isSelected = live.isNotEmpty() && group.selected == item.tag
                val delay = text(delayLabel(item.delay), 12f, SOFT)
                delay.setGravity(Gravity.END)
                line.addView(delay, LinearLayout.LayoutParams(-2, -2))
                if (isSelected) {
                    val check = text("✓", 13f, theme.iconColor)
                    val checkParams = LinearLayout.LayoutParams(-2, -2)
                    checkParams.leftMargin = dp(8)
                    line.addView(check, checkParams)
                }
                line.contentDescription = item.tag
                line.setOnClickListener {
                    if (!VpnController.running) {
                        toast("连接后可切换出口")
                        return@setOnClickListener
                    }
                    if (!group.selectable) {
                        toast("该策略组不可手动选择")
                        return@setOnClickListener
                    }
                    VpnController.selectOutbound(group.tag, item.tag) { error -> toast(error) }
                }
                val lineParams = full()
                lineParams.topMargin = dp(4)
                rows.addView(line, lineParams)
            }
        }
    }

    private fun groupSummary(group: GroupSnapshot): String {
        val selected = group.selected.ifEmpty { group.items.firstOrNull()?.tag.orEmpty() }
        val count = if (group.items.isEmpty()) "" else " · " + group.items.size + " 节点"
        return selected + count
    }

    private fun delayLabel(delay: Int): String = when {
        delay > 0 -> delay.toString() + " ms"
        delay < 0 -> "超时"
        else -> "未测"
    }

    /** Selector/urltest groups straight from the active config, shown while the core is off. */
    internal fun staticGroupCacheReset() {
        staticGroupCache = null
        renderedGroupRevision = -1
    }

    private fun staticGroups(): List<GroupSnapshot> {
        val activeId = try {
            ProfileStore.activeId(this)
        } catch (ignored: Exception) {
            ""
        }
        val cached = staticGroupCache
        if (cached != null && cached.first == activeId) return cached.second
        val parsed = parseStaticGroups(activeId)
        staticGroupCache = Pair(activeId, parsed)
        return parsed
    }

    private fun parseStaticGroups(activeId: String): List<GroupSnapshot> {
        if (activeId.isEmpty()) return emptyList()
        val config = try {
            ProfileStore.read(this, activeId)
        } catch (ignored: Exception) {
            return emptyList()
        }
        val document = try {
            org.json.JSONObject(config)
        } catch (ignored: Exception) {
            return emptyList()
        }
        val outbounds = document.optJSONArray("outbounds") ?: return emptyList()
        val result = ArrayList<GroupSnapshot>()
        for (i in 0 until outbounds.length()) {
            val item = outbounds.optJSONObject(i) ?: continue
            val type = item.optString("type", "")
            if (type != "selector" && type != "urltest") continue
            val tag = item.optString("tag", "")
            if (tag.isEmpty()) continue
            val members = ArrayList<GroupSnapshot.ItemSnapshot>()
            val list = item.optJSONArray("outbounds")
            if (list != null) {
                for (j in 0 until list.length()) {
                    val memberTag = list.optString(j, "")
                    if (memberTag.isNotEmpty()) {
                        members.add(GroupSnapshot.ItemSnapshot(memberTag, "", 0, 0))
                    }
                }
            }
            result.add(GroupSnapshot(tag, type, type == "selector", "", members))
        }
        return result
    }

    private fun refreshStatus() {
        if (isFinishing || isDestroyed) return
        val running = VpnController.running
        val latency = latencyPillText
        if (latency != null) {
            latency.text = when {
                !running -> "延迟 —"
                LatencyProbe.testing -> "延迟 …"
                LatencyProbe.milliseconds >= 0 -> "延迟 " + LatencyProbe.milliseconds + " ms"
                else -> "延迟 失败"
            }
        }
        val start = startPillText
        if (start != null) {
            start.text = when {
                busy -> "检查中…"
                permissionPending -> "等待授权"
                running -> "停止"
                else -> "启动"
            }
        }
        startPillIcon?.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        val sentence = speedSentence
        if (sentence != null) {
            sentence.text = if (running) {
                "当前下载 " + rate(VpnController.downlink) + " · 上传 " + rate(VpnController.uplink)
            } else {
                "未连接 · 连接后显示实时网速"
            }
        }
        val chart = speedChart
        if (chart != null) {
            val samples = if (running) speedSamples() else emptyList()
            chart.setValues(samples)
        }
        if (running) {
            val revision = VpnGroups.revision
            if (revision != renderedGroupRevision) {
                renderedGroupRevision = revision
                renderGroups()
            }
        } else if (renderedGroupRevision != 0L) {
            renderedGroupRevision = 0L
            renderGroups()
        }
        renderWeather(weatherState)
        val activeName = ProfileStore.activeName(this)
        stateLabel?.let { label ->
            label.text = (if (running) "● " else "○ ") + VpnController.state
        }
        description?.let { label ->
            label.text = if (running) {
                "下载 " + rate(VpnController.downlink) + "  ·  上传 " + rate(VpnController.uplink)
            } else if (busy) {
                "正在检查配置…"
            } else if (permissionPending) {
                "等待系统 VPN 授权"
            } else {
                "当前配置：" + if (activeName.isEmpty()) "未选择" else activeName
            }
        }
        totalsLabel?.text = "↓ " + bytes(VpnController.downlinkTotal) + "\n↑ " + bytes(VpnController.uplinkTotal)
        val errorView = errorLabel
        if (errorView != null) {
            val error = VpnController.error
            errorView.text = if (error.isEmpty()) "暂无服务错误" else error
            errorView.visibility = if (selectedTab == 2 || error.isNotEmpty()) View.VISIBLE else View.GONE
        }
        val logsView = logsLabel
        if (logsView != null) {
            var logs = VpnController.logs()
            if (logs.isEmpty()) logs = "暂无服务日志。连接后将在这里显示核心输出。"
            if (logs.length > 60000) logs = "…仅显示最新日志…\n" + logs.substring(logs.length - 60000)
            if (logs != lastLogs) {
                logsView.text = logs
                lastLogs = logs
            }
        }
    }

    private fun speedSamples(): List<Double> {
        val history = VpnController.speedHistory()
        val result = ArrayList<Double>(history.size)
        for (value in history) result.add(value.toDouble())
        return result
    }

    private fun beginStart() {
        if (busy || permissionPending || VpnController.running) return
        busy = true
        refreshStatus()
        worker.execute {
            try {
                val id = ProfileStore.activeId(this)
                val config = ProfileStore.activeConfig(this)
                if (config.isEmpty()) {
                    handler.post {
                        busy = false
                        if (isFinishing || isDestroyed) return@post
                        android.app.AlertDialog.Builder(this).setTitle("先选择有效配置")
                            .setMessage("请导入并选用自己的 sing-box JSON，然后开启连接。")
                            .setPositiveButton("导入 JSON") { _, _ -> importDocument() }
                            .setNeutralButton("管理配置") { _, _ -> openPanelToTab(0) }
                            .setNegativeButton("取消", null).show()
                        refreshStatus()
                    }
                    return@execute
                }
                ProfileStore.validate(applicationContext, config)
                handler.post {
                    busy = false
                    if (isFinishing || isDestroyed) return@post
                    pendingConfig = config
                    pendingId = id
                    try {
                        val authorization = VpnService.prepare(this)
                        if (authorization != null) {
                            permissionPending = true
                            startActivityForResult(authorization, VPN_PERMISSION)
                        } else {
                            startAuthorized()
                        }
                    } catch (e: Exception) {
                        permissionPending = false
                        showError("VPN 授权失败", e)
                    }
                    refreshStatus()
                }
            } catch (e: Throwable) {
                reportStartFailure(e)
            }
        }
    }

    private fun reportStartFailure(e: Throwable) {
        handler.post {
            busy = false
            if (!isFinishing && !isDestroyed) showError("配置检查失败", e)
            refreshStatus()
        }
    }

    private fun startAuthorized() {
        permissionPending = false
        // Reject a changed profile instead of starting a config other than the one checked.
        val checkedConfig = pendingConfig
        val checkedId = pendingId
        if (checkedConfig == null || checkedConfig != ProfileStore.activeConfig(this) ||
            checkedId != ProfileStore.activeId(this)
        ) {
            pendingConfig = null
            pendingId = null
            toast("配置已变更，请重新开启连接")
            return
        }
        pendingConfig = null
        pendingId = null
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 112)
            }
            val intent = Intent(this, VpnController::class.java).setAction(VpnController.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            toast("已请求启动，请等待服务状态更新")
        } catch (e: Exception) {
            showError("无法启动 VPN", e)
        }
    }

    private fun stopConnection() {
        pendingConfig = null
        pendingId = null
        try {
            // A running service already owns its foreground notification; STOP must not create one.
            startService(Intent(this, VpnController::class.java).setAction(VpnController.ACTION_STOP))
            toast("已请求停止")
        } catch (e: Exception) {
            showError("无法停止 VPN", e)
        }
    }

    private fun importDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("application/json", "text/plain", "application/octet-stream"),
        )
        try {
            startActivityForResult(intent, IMPORT_JSON)
        } catch (e: Exception) {
            showError("无法打开文件选择器", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION) {
            permissionPending = false
            if (resultCode == RESULT_OK) {
                // Activity recreation loses pending validation; request a fresh check safely.
                if (pendingConfig == null) beginStart() else startAuthorized()
            } else {
                pendingConfig = null
                pendingId = null
                toast("未授予 VPN 权限，连接未启动")
            }
            refreshStatus()
        } else if (requestCode == IMPORT_JSON && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) importFromUri(uri)
        }
    }

    private fun importFromUri(uri: android.net.Uri) {
        toast("正在读取配置…")
        worker.execute {
            try {
                var name = "导入配置"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayName = cursor.getString(0)
                        if (displayName != null) name = displayName
                    }
                }
                if (name.trim().isEmpty()) name = "导入配置"
                if (name.endsWith(".json")) name = name.substring(0, name.length - 5)
                val profileName = if (name.length > 80) name.substring(0, 80) else name
                val input = contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
                val bytes = input.use { stream ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count == -1) break
                            if (output.size() + count > ProfileStore.MAX_CONFIG_BYTES) {
                                throw IOException("配置超过 2 MiB 限制")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
                var content = String(bytes, StandardCharsets.UTF_8)
                if (content.startsWith("\uFEFF")) content = content.substring(1)
                val json = content
                handler.post {
                    if (!isFinishing && !isDestroyed) showEditor(null, profileName, json)
                }
            } catch (e: Throwable) {
                handler.post { if (!isFinishing && !isDestroyed) showError("导入失败", e) }
            }
        }
    }

    internal fun canModify(): Boolean {
        if (VpnController.running || busy || permissionPending) {
            toast("请先停止连接，并等待当前操作完成后再修改配置")
            return false
        }
        return true
    }

    private fun selectProfile(profile: ProfileStore.Profile) {
        if (!canModify()) return
        busy = true
        worker.execute {
            try {
                ProfileStore.validate(this, ProfileStore.read(this, profile.id))
                ProfileStore.select(this, profile.id)
                handler.post {
                    busy = false
                    if (isFinishing || isDestroyed) return@post
                    staticGroupCacheReset()
                    buildPanel()
                    toast("已选用 " + profile.name)
                }
            } catch (e: Throwable) {
                reportProfileFailure(e)
            }
        }
    }

    private fun reportProfileFailure(e: Throwable) {
        handler.post {
            busy = false
            if (!isFinishing && !isDestroyed) showError("选用失败", e)
        }
    }

    private fun openEditor(profile: ProfileStore.Profile) {
        if (!canModify()) return
        try {
            showEditor(profile.id, profile.name, ProfileStore.read(this, profile.id))
        } catch (e: Exception) {
            showError("无法读取配置", e)
        }
    }

    private fun deleteProfile(profile: ProfileStore.Profile) {
        if (!canModify()) return
        android.app.AlertDialog.Builder(this).setTitle("删除配置？")
            .setMessage("将从本机删除「" + profile.name + "」。此操作不可撤销。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                if (!canModify()) return@setPositiveButton
                try {
                    ProfileStore.delete(this, profile.id)
                    staticGroupCacheReset()
                    buildPanel()
                    toast("配置已删除")
                } catch (e: Exception) {
                    showError("删除失败", e)
                }
            }.show()
    }

    private fun heading(title: String, subtitle: String) {
        val h = text(title, 32f, WHITE)
        h.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL))
        page.addView(h, full())
        val s = text(subtitle, 14f, SOFT)
        s.setPadding(0, dp(7), 0, dp(24))
        page.addView(s, full())
    }

    private fun card(): LinearLayout {
        val container = column()
        container.setPadding(dp(16), dp(14), dp(16), dp(14))
        // Content cards sit a little denser than the home cards so body text stays comfortable.
        container.applyLiquidGlassSurface(
            style = Glass.Style.REGULAR,
            radiusDp = Glass.RADIUS_CARD_DP,
            surface = theme.cardColor,
            surfaceAlpha = 0.88f,
            dark = !theme.isDay,
        )
        return container
    }

    internal fun column(): LinearLayout {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        return layout
    }

    internal fun row(): LinearLayout {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.setGravity(Gravity.CENTER_VERTICAL)
        return layout
    }

    internal fun text(value: String, size: Float, color: Int): TextView {
        val view = TextView(this)
        view.setText(value)
        view.setTextSize(size)
        val resolved = if (color == WHITE) theme.textColor else if (color == SOFT) theme.secondaryTextColor else color
        view.setTextColor(resolved)
        view.fontFeatureSettings = "kern"
        view.setLineSpacing(dp(2).toFloat(), 1f)
        return view
    }

    /** Paints the sky screen. Cheap text updates every tick; cards only on new data. */
    private fun renderWeather(state: WeatherState?) {
        val title = cityTitle ?: return
        title.text = regionName()
        citySubtitle?.text = regionSubtitle()
        val has = state != null && state.hasData()
        if (!has) {
            bigTemp?.text = "—"
            conditionLine?.text = if (state?.loading == true) "天气加载中…" else "天气暂不可用"
            val message = state?.errorMessage.orEmpty()
            weatherError?.text = message
            weatherError?.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
            return
        }
        val current = state!!
        bigTemp?.text = temperatureSpan(Math.round(current.temperature).toString())
        val max = current.todayMax()
        val min = current.todayMin()
        conditionLine?.text = if (max.isFinite() && min.isFinite()) {
            theme.weatherNameZh + "  最高 " + Math.round(max) + "°  最低 " + Math.round(min) + "°"
        } else {
            theme.weatherNameZh
        }
        val message = when {
            current.error && current.errorMessage.isNotEmpty() -> current.errorMessage
            current.stale -> "数据已过期 · 点击刷新"
            else -> ""
        }
        weatherError?.text = message
        weatherError?.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        weatherError?.setOnClickListener { refreshWeather(true) }
    }

    /** Big reading with the ring degree sign scaled down and raised, like the reference layout. */
    internal fun temperatureSpan(value: String): CharSequence {
        val text = value + "°"
        val span = SpannableString(text)
        val start = text.length - 1
        span.setSpan(RelativeSizeSpan(0.34f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(SuperscriptSpan(), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    internal fun refreshWeather(force: Boolean) {
        weatherService?.fetch(force)
    }

    internal fun weatherSearchRefresh() {
        refreshWeather(true)
        if (weatherState != null) applyWeather()
    }

    private fun applyWeather() {
        val state = weatherState
        val next = WeatherTheme.from(state)
        WeatherTheme.apply(this, root, state)
        backdrop.setTheme(next)
        paintFloatingButtons()
        val key = themeKeyOf(next)
        theme = next
        if (key != themeKey) {
            themeKey = key
            // Palette changed: rebuild both surfaces so cards, dock and labels pick it up.
            buildWeatherScreen()
            buildPanel()
            refreshStatus()
            return
        }
        refreshStatus() // Same palette: only refresh readings, keeping the scroll position.
    }

    internal fun action(label: String, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.setTextSize(13f)
        button.isAllCaps = false
        button.setTextColor(WHITE)
        button.setMinWidth(0)
        button.setMinimumWidth(0)
        button.setMinHeight(dp(44))
        button.setMinimumHeight(dp(44))
        button.setPadding(dp(14), dp(8), dp(14), dp(8))
        button.applyLiquidGlass(
            style = Glass.Style.CLEAR,
            radiusDp = Glass.RADIUS_CONTROL_DP,
            surface = theme.cardColor,
            surfaceAlpha = 0.55f,
            dark = !theme.isDay,
        )
        button.setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(-2, -2)
        params.rightMargin = dp(8)
        button.layoutParams = params
        return button
    }

    private fun round(color: Int, radius: Int): GradientDrawable {
        val background = GradientDrawable()
        background.setColor(color)
        background.setCornerRadius(dp(radius).toFloat())
        return background
    }

    internal fun roundSurface(color: Int, radius: Int): GradientDrawable = round(color, radius)

    internal fun full(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)

    internal fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    private fun space(parent: LinearLayout, size: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(size)))
    }

    internal fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    internal fun showError(title: String, e: Throwable) {
        if (isFinishing || isDestroyed) return
        android.app.AlertDialog.Builder(this).setTitle(title)
            .setMessage(safeError(e)).setPositiveButton("知道了", null).show()
    }

    companion object {
        private const val IMPORT_JSON = 110
        private const val VPN_PERMISSION = 111

        /** Process-wide "tunnel was up" flag so a recreated activity does not re-toast on open. */
        @Volatile
        private var lastRunningObserved = false
        internal const val WHITE = -1 // Color.WHITE
        internal const val SOFT = -0x20000001 // 0xDFFFFFFF
        private const val T_DOCK_SLOT = 52
        private const val T_DOCK_PAD = 4
        private const val T_DOCK_MARGIN = 10
        private const val FIVE_MINUTES_MS = 5 * 60_000L

        internal fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

        /** Weighted mix of two opaque colours, used to give cards distinct colour weights. */
        internal fun mix(from: Int, to: Int, amount: Float): Int = Color.rgb(
            Math.round(Color.red(from) * (1 - amount) + Color.red(to) * amount),
            Math.round(Color.green(from) * (1 - amount) + Color.green(to) * amount),
            Math.round(Color.blue(from) * (1 - amount) + Color.blue(to) * amount),
        )

        internal fun themeKeyOf(value: WeatherTheme): String =
            value.type.name + (if (value.isDay) "-day" else "-night")

        internal fun safeError(e: Throwable): String {
            val message = e.message
            return if (message == null || message.trim().isEmpty()) e.javaClass.simpleName else message
        }

        internal fun bytes(value: Long): String {
            if (value < 0) return "—"
            if (value < 1024) return value.toString() + " B"
            if (value < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", value / 1024.0)
            if (value < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024))
            return String.format(Locale.ROOT, "%.2f GB", value / (1024.0 * 1024 * 1024))
        }

        internal fun rate(value: Long): String = if (value < 0) "—" else bytes(value) + "/s"
    }
}
