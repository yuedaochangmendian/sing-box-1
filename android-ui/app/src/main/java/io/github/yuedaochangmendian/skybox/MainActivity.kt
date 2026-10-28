package io.github.yuedaochangmendian.skybox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.nekohasekai.libbox.Libbox
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Native, dependency-free sky dashboard. All connection information comes from VpnController. */
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var backdrop: SkyBackdropView
    private lateinit var panel: FrameLayout
    private lateinit var weatherScroll: ScrollView
    private lateinit var floatLeft: View
    private lateinit var floatRight: View

    private var panelOpen = false
    private var cityTitle: TextView? = null
    private var citySubtitle: TextView? = null
    private var bigTemp: TextView? = null
    private var conditionLine: TextView? = null
    private var aqiPillText: TextView? = null
    private var precipSentence: TextView? = null
    private var precipChart: PrecipitationChartView? = null
    private var precipEmpty: TextView? = null
    private var forecastRows: LinearLayout? = null
    private var forecastCard: LinearLayout? = null
    private var weatherError: TextView? = null
    private var renderedWeather = ""

    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private var profileLabel: TextView? = null
    private var nodeTitle: TextView? = null
    private var stateLabel: TextView? = null
    private var description: TextView? = null
    private var totalsLabel: TextView? = null
    private var routeLabel: TextView? = null
    private var errorLabel: TextView? = null
    private var logsLabel: TextView? = null
    private var versionLabel: TextView? = null
    private var latencyLabel: TextView? = null
    private var latencyHint: TextView? = null
    private var historyLabels: Array<TextView?>? = null
    private var historyIcons: Array<WeatherIconView?>? = null
    private var weatherService: WeatherService? = null
    private var weatherState: WeatherState? = null
    private var theme: WeatherTheme = WeatherTheme.from(null)
    private var themeKey = ""
    private var cityDialog: AlertDialog? = null
    private var cityResults: LinearLayout? = null
    private var cityInput: EditText? = null
    private var citySearchStatus: TextView? = null
    private val themedCards = ArrayList<View>()
    private val themedPills = ArrayList<View>()
    private val tabIcons = arrayOfNulls<ImageView>(4)
    private var connectionSwitch: Switch? = null
    private val tabLabels = arrayOfNulls<TextView>(4)
    private val tabPills = arrayOfNulls<View>(4)
    private var selectedTab = 0
    private var renderingSwitch = false
    private var busy = false
    private var permissionPending = false
    private var pendingConfig: String? = null
    private var pendingId: String? = null
    private var coreVersion = "正在读取…"
    private var resumed = false
    private var lastLogs: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            refreshStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = 0xFF72B3E9.toInt()
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        selectedTab = if (savedInstanceState == null) 0 else savedInstanceState.getInt("tab", 0)
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
        worker.execute {
            try {
                CoreRuntime.ensureInit(applicationContext)
                coreVersion = Libbox.version()
            } catch (e: Exception) {
                coreVersion = "不可用：" + safeError(e)
            } catch (e: LinkageError) {
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
        worker.shutdown()
        super.onDestroy()
    }

    private fun buildPanel() {
        profileLabel = null
        nodeTitle = null
        stateLabel = null
        description = null
        totalsLabel = null
        routeLabel = null
        errorLabel = null
        logsLabel = null
        versionLabel = null
        latencyLabel = null
        latencyHint = null
        connectionSwitch = null
        historyLabels = null
        historyIcons = null
        tabIcons.fill(null)
        tabLabels.fill(null)
        tabPills.fill(null)
        themedCards.clear()
        themedPills.clear()
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
            0 -> buildOverview()
            1 -> buildProfiles()
            2 -> buildActivity()
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
        close.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            round(withAlpha(theme.cardColor, 0xCC), 20),
            round(WHITE, 20),
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

    private fun buildFloatingButtons() {
        val size = dp(56)
        val ripple = ColorStateList.valueOf(0x33FFFFFF)
        val left = ImageView(this)
        left.setImageResource(R.drawable.ic_panel)
        left.scaleType = ImageView.ScaleType.CENTER
        left.setColorFilter(theme.iconColor)
        left.background = RippleDrawable(ripple, oval(withAlpha(theme.cardColor, 0xD9)), oval(WHITE))
        left.contentDescription = "打开配置与设置"
        left.setOnClickListener { openPanel() }
        val leftParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START)
        leftParams.leftMargin = dp(20)
        leftParams.bottomMargin = dp(22)
        root.addView(left, leftParams)
        floatLeft = left

        val right = ImageView(this)
        right.setImageResource(R.drawable.ic_add)
        right.scaleType = ImageView.ScaleType.CENTER
        right.setColorFilter(theme.iconColor)
        right.background = RippleDrawable(ripple, oval(withAlpha(theme.cardColor, 0xD9)), oval(WHITE))
        right.contentDescription = "添加配置"
        right.setOnClickListener { showAddProfileMenu() }
        val rightParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END)
        rightParams.rightMargin = dp(20)
        rightParams.bottomMargin = dp(22)
        root.addView(right, rightParams)
        floatRight = right
    }

    private fun paintFloatingButtons() {
        val ripple = ColorStateList.valueOf(0x33FFFFFF)
        val surface = oval(withAlpha(theme.cardColor, 0xD9))
        floatLeft.background = RippleDrawable(ripple, surface, oval(WHITE))
        floatRight.background = RippleDrawable(ripple, oval(withAlpha(theme.cardColor, 0xD9)), oval(WHITE))
    }

    /** Bottom-right ＋: the same two entry points as the profile page, without leaving the sky. */
    private fun showAddProfileMenu() {
        AlertDialog.Builder(this)
            .setTitle("添加配置")
            .setItems(arrayOf("从文件导入 JSON", "新建空白配置")) { _, which ->
                if (which == 0) {
                    importDocument()
                } else {
                    openPanel()
                    selectTab(1)
                    showEditor(
                        null,
                        "新配置",
                        "{\n  \"log\": {\n    \"level\": \"info\"\n  },\n  \"inbounds\": [],\n  \"outbounds\": []\n}",
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildWeatherScreen() {
        cityTitle = null
        citySubtitle = null
        bigTemp = null
        conditionLine = null
        aqiPillText = null
        precipSentence = null
        precipChart = null
        precipEmpty = null
        forecastRows = null
        forecastCard = null
        weatherError = null
        renderedWeather = ""
        val content = column()
        content.setPadding(dp(16), dp(22), dp(16), dp(148))
        weatherScroll.removeAllViews()
        weatherScroll.addView(content, FrameLayout.LayoutParams(-1, -2))

        val title = text(weatherCityName(), 25f, WHITE)
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        cityTitle = title
        content.addView(title, full())

        val subtitle = text("手动选择城市，获取当地天气  ›", 13f, withAlpha(theme.secondaryTextColor, 0xD0))
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
        bigTemp = temperature
        reading.addView(temperature, LinearLayout.LayoutParams(-2, -2))
        content.addView(reading, full())

        val conditions = text("天气加载中…", 16f, withAlpha(theme.textColor, 0xE6))
        conditions.setPadding(0, dp(2), 0, 0)
        conditionLine = conditions
        content.addView(conditions, full())

        val error = text("", 12f, withAlpha(theme.textColor, 0xCC))
        error.setPadding(0, dp(6), 0, 0)
        error.visibility = View.GONE
        weatherError = error
        content.addView(error, full())

        space(content, 14)
        val pills = row()
        val air = pill(R.drawable.ic_leaf, "空气质量 —")
        air.setOnClickListener { showAirQuality() }
        aqiPillText = air.getChildAt(1) as TextView
        pills.addView(air, LinearLayout.LayoutParams(-2, dp(40)))
        val forecastPill = pill(R.drawable.ic_play, "天气预报")
        forecastPill.setOnClickListener {
            val target = forecastCard
            if (target != null) weatherScroll.smoothScrollTo(0, Math.max(0, target.top - dp(24)))
        }
        val forecastParams = LinearLayout.LayoutParams(-2, dp(40))
        forecastParams.leftMargin = dp(10)
        pills.addView(forecastPill, forecastParams)
        content.addView(pills, full())

        space(content, 72)
        val precip = glassCard()
        val sentence = text("正在读取降水预报…", 15f, theme.textColor)
        precipSentence = sentence
        precip.addView(sentence, full())
        val chartRow = row()
        chartRow.setPadding(0, dp(12), 0, 0)
        val chart = PrecipitationChartView(this)
        chart.setBarColors(0xF2BEE5FF.toInt(), 0x8C7FBDF0.toInt())
        chart.setGridColor(withAlpha(theme.textColor, 0x26))
        precipChart = chart
        chartRow.addView(chart, LinearLayout.LayoutParams(0, dp(92), 1f))
        val axis = column()
        axis.setGravity(Gravity.CENTER_VERTICAL)
        for (label in arrayOf("大", "中", "小")) {
            val tick = text(label, 11f, withAlpha(theme.secondaryTextColor, 0xAA))
            tick.setGravity(Gravity.CENTER)
            axis.addView(tick, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val axisParams = LinearLayout.LayoutParams(dp(26), dp(92))
        axisParams.leftMargin = dp(6)
        chartRow.addView(axis, axisParams)
        precip.addView(chartRow, full())
        val timeline = row()
        timeline.setPadding(0, dp(8), dp(32), 0)
        val marks = arrayOf("现在", "1小时后", "2小时后")
        val gravities = intArrayOf(Gravity.START, Gravity.CENTER, Gravity.END)
        for (i in marks.indices) {
            val mark = text(marks[i], 11f, withAlpha(theme.secondaryTextColor, 0xCC))
            mark.setGravity(gravities[i])
            timeline.addView(mark, LinearLayout.LayoutParams(0, -2, 1f))
        }
        precip.addView(timeline, full())
        val empty = text("", 12f, withAlpha(theme.secondaryTextColor, 0xCC))
        empty.setPadding(0, dp(8), 0, 0)
        empty.visibility = View.GONE
        precipEmpty = empty
        precip.addView(empty, full())
        content.addView(precip, full())

        space(content, 14)
        val forecast = glassCard()
        forecast.setPadding(dp(16), dp(6), dp(16), dp(6))
        val rows = column()
        forecastRows = rows
        forecast.addView(rows, full())
        forecastCard = forecast
        content.addView(forecast, full())
    }

    private fun pill(iconRes: Int, label: String): LinearLayout {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL
        container.setPadding(dp(14), 0, dp(16), 0)
        container.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            round(withAlpha(theme.cardColor, 0xC4), 20),
            round(WHITE, 20),
        )
        val icon = ImageView(this)
        icon.setImageResource(iconRes)
        icon.setColorFilter(theme.textColor)
        container.addView(icon, LinearLayout.LayoutParams(dp(15), dp(15)))
        val value = text(label, 14f, WHITE)
        val params = LinearLayout.LayoutParams(-2, -2)
        params.leftMargin = dp(7)
        container.addView(value, params)
        return container
    }

    /** Translucent rounded card used by the sky screen (lighter than the control panel cards). */
    private fun glassCard(): LinearLayout {
        val container = column()
        container.setPadding(dp(16), dp(15), dp(16), dp(15))
        container.background = round(withAlpha(theme.cardColor, 0xB8), 22)
        return container
    }

    private fun oval(color: Int): GradientDrawable {
        val background = GradientDrawable()
        background.shape = GradientDrawable.OVAL
        background.setColor(color)
        return background
    }

    private fun buildOverview() {
        val head = row()
        val title = text("控制", 30f, WHITE)
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL))
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this)
        toggle.setText("Off  ")
        toggle.setTextColor(WHITE)
        toggle.setTextSize(17f)
        toggle.thumbTintList = ColorStateList.valueOf(WHITE)
        toggle.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFFFFC229.toInt(), 0x66FFFFFF),
        )
        toggle.setPadding(dp(13), 0, dp(10), 0)
        toggle.background = round(0x24FFFFFF, 32)
        toggle.contentDescription = "启动或停止 VPN 连接"
        toggle.setOnCheckedChangeListener { _, checked ->
            if (renderingSwitch) return@setOnCheckedChangeListener
            if (checked) beginStart() else stopConnection()
            refreshStatus()
        }
        connectionSwitch = toggle
        themedPills.add(toggle)
        head.addView(toggle, LinearLayout.LayoutParams(-2, T_PILL))
        page.addView(head, full())
        val currentName = ProfileStore.activeName(this)
        val active = text((if (currentName.isEmpty()) "未选择配置" else currentName) + "  ﹀", 13f, SOFT)
        active.setPadding(0, dp(8), 0, dp(16))
        active.contentDescription = "选用配置"
        active.setOnClickListener { showProfilePicker() }
        nodeTitle = active
        page.addView(active, full())
        val reading = row()
        reading.setGravity(Gravity.CENTER)
        val latency = text("—", 68f, WHITE)
        latency.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL))
        latency.setIncludeFontPadding(false)
        latency.contentDescription = "点击测试当前 VPN 路由的 HTTP 延迟"
        latency.setOnClickListener {
            if (!VpnController.running) {
                toast("请先开启 VPN 连接")
                return@setOnClickListener
            }
            LatencyProbe.test {
                handler.post { if (!isFinishing && !isDestroyed) refreshStatus() }
            }
            refreshStatus()
        }
        latencyLabel = latency
        reading.addView(latency, LinearLayout.LayoutParams(-2, dp(88)))
        val unit = text("ms", 17f, SOFT)
        val unitParams = LinearLayout.LayoutParams(-2, -2)
        unitParams.gravity = Gravity.TOP
        unitParams.topMargin = dp(13)
        unitParams.leftMargin = dp(6)
        reading.addView(unit, unitParams)
        page.addView(reading, full())
        val hint = text("连接后点击测试 HTTP 延迟", 12f, SOFT)
        hint.setGravity(Gravity.CENTER)
        latencyHint = hint
        page.addView(hint, full())
        space(page, 14)
        val state = text("已断开", 17f, WHITE)
        state.setGravity(Gravity.CENTER)
        stateLabel = state
        page.addView(state, full())
        val desc = text("选用配置后，开启你的连接。", 13f, SOFT)
        desc.setGravity(Gravity.CENTER)
        desc.setPadding(dp(8), dp(4), dp(8), 0)
        description = desc
        page.addView(desc, full())
        space(page, 16)
        val history = card()
        history.addView(text("最近一分钟 · 下载", 15f, WHITE), full())
        val historyHint = text("天气图标对应下载量：晴为静默，云雨为有流量。", 12f, SOFT)
        historyHint.setPadding(0, dp(3), 0, dp(10))
        history.addView(historyHint, full())
        val divider = View(this)
        divider.setBackgroundColor(0x1AFFFFFF)
        history.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
        val columns = row()
        val labels = arrayOfNulls<TextView>(6)
        val icons = arrayOfNulls<WeatherIconView>(6)
        val times = arrayOf("−50″", "−40″", "−30″", "−20″", "−10″", "现在")
        for (i in 0 until 6) {
            val cell = column()
            cell.setGravity(Gravity.CENTER)
            cell.setPadding(0, dp(9), 0, 0)
            val time = text(times[i], 12f, SOFT)
            time.setGravity(Gravity.CENTER)
            cell.addView(time, full())
            val cellIcon = WeatherIconView(this)
            cellIcon.setTint(theme.secondaryTextColor)
            cell.addView(cellIcon, LinearLayout.LayoutParams(-1, dp(30)))
            icons[i] = cellIcon
            val label = text("—", 12f, WHITE)
            label.setGravity(Gravity.CENTER)
            label.setMaxLines(1)
            labels[i] = label
            cell.addView(label, full())
            columns.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        historyLabels = labels
        historyIcons = icons
        history.addView(columns, full())
        page.addView(history, full())
        space(page, 12)
        val metrics = row()
        val trafficCard = card()
        trafficCard.addView(text("流量 · 当前服务", 13f, SOFT), full())
        val totals = text("—", 22f, WHITE)
        totals.setPadding(0, dp(8), 0, dp(12))
        totalsLabel = totals
        trafficCard.addView(totals, full())
        trafficCard.addView(text("实时速率见活动页", 11f, SOFT), full())
        trafficCard.setOnClickListener { selectTab(2) }
        val routingCard = card()
        routingCard.addView(text("路由 · 配置驱动", 13f, SOFT), full())
        val route = text("跟随 JSON", 19f, WHITE)
        route.setPadding(0, dp(8), 0, dp(12))
        routeLabel = route
        routingCard.addView(route, full())
        routingCard.addView(text("查看和编辑规则  ›", 11f, SOFT), full())
        routingCard.setOnClickListener { selectTab(1) }
        val left = LinearLayout.LayoutParams(0, -1, 1f)
        val right = LinearLayout.LayoutParams(0, -1, 1f)
        right.leftMargin = dp(10)
        metrics.setBaselineAligned(false)
        metrics.addView(trafficCard, left)
        metrics.addView(routingCard, right)
        page.addView(metrics, full())
        space(page, 12)
        val error = text("", 12f, WHITE)
        error.setPadding(dp(14), dp(11), dp(14), dp(11))
        error.background = round(0x33452D65, 16)
        error.setTextIsSelectable(true)
        errorLabel = error
        page.addView(error, full())
        space(page, 12)
        val shortcuts = HorizontalScrollView(this)
        shortcuts.setHorizontalScrollBarEnabled(false)
        val shortcutsRow = row()
        shortcutsRow.addView(action("导入 JSON") { importDocument() })
        shortcutsRow.addView(action("编辑当前配置") { editActive() })
        shortcutsRow.addView(action("查看活动日志") { selectTab(2) })
        shortcuts.addView(shortcutsRow)
        page.addView(shortcuts, full())
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
        privacy.addView(action("管理配置") { selectTab(1) })
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
        weatherInfo.addView(text("天气数据", 21f, WHITE), full())
        val weatherSource = text(
            "天气与城市搜索来自 Open-Meteo（HTTPS，无需 API Key）。仅使用手动选择的城市坐标，不申请定位权限；" +
                "同一城市最短 15 分钟刷新一次。天气主题只影响配色与图标，不代表 VPN 质量。",
            14f,
            SOFT,
        )
        weatherSource.setPadding(0, dp(10), 0, dp(8))
        weatherInfo.addView(weatherSource, full())
        weatherInfo.addView(action("刷新天气") { refreshWeather(true) })
        weatherInfo.addView(action("更换城市") { showCityPicker() })
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
        bar.background = round(withAlpha(theme.dockColor, 0xE6), 40)
        bar.elevation = dp(6).toFloat()
        val names = arrayOf("概览", "配置", "活动", "设置")
        val icons = intArrayOf(
            R.drawable.nav_now,
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
            item.setOnClickListener { selectTab(index) }
            bar.addView(item, LinearLayout.LayoutParams(0, dp(T_DOCK_SLOT), 1f))
            tabLabels[i] = label
            tabPills[i] = item
            tabIcons[i] = icon
        }
        val dockParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        dockParams.setMargins(dp(16), 0, dp(16), dp(T_DOCK_MARGIN))
        panel.addView(bar, dockParams)
        dock = bar
        val reserved = T_DOCK_SLOT + T_DOCK_PAD * 2 + T_DOCK_MARGIN + 8
        page.setPadding(dp(20), dp(52), dp(20), dp(reserved))
    }

    private fun selectTab(tab: Int) {
        if (!panelOpen) openPanel()
        if (selectedTab == tab) return
        selectedTab = tab
        buildPanel()
    }

    private fun refreshStatus() {
        if (isFinishing || isDestroyed) return
        val running = VpnController.running
        val state = VpnController.state ?: "未知状态"
        val error = VpnController.error ?: ""
        val latency = latencyLabel
        if (latency != null) {
            latency.text = when {
                !running -> "—"
                LatencyProbe.testing -> "···"
                LatencyProbe.milliseconds >= 0 -> LatencyProbe.milliseconds.toString()
                else -> "—"
            }
            latencyHint?.text = when {
                !running -> "连接后点击测试 HTTP 延迟"
                LatencyProbe.testing -> "正在测试 HTTP 请求耗时…"
                LatencyProbe.failure.isNotEmpty() -> "测试失败：" + LatencyProbe.failure
                LatencyProbe.fastest >= 0 ->
                    "最快 " + LatencyProbe.fastest + " ms · 最慢 " + LatencyProbe.slowest + " ms"
                else -> "点击读数测试 HTTP 延迟"
            }
        }
        val toggle = connectionSwitch
        if (toggle != null) {
            renderingSwitch = true
            toggle.isChecked = running
            toggle.text = if (running) "On  " else "Off  "
            toggle.isEnabled = !busy && !permissionPending
            renderingSwitch = false
        }
        val activeName = ProfileStore.activeName(this)
        val activeLabel = if (activeName.isEmpty()) "未选择配置" else activeName
        profileLabel?.text = activeLabel + "  ﹀"
        nodeTitle?.text = activeLabel + "  ﹀"
        stateLabel?.let { label ->
            label.text = (if (VpnController.running) "● " else "○ ") + state
            label.setTextColor(theme.textColor)
        }
        description?.let { label ->
            label.text = if (selectedTab == 2) {
                "下载 " + rate(VpnController.downlink) + "  ·  上传 " + rate(VpnController.uplink)
            } else if (running) {
                "VPN 服务运行中 · 实际转发由配置决定"
            } else if (busy) {
                "正在检查配置…"
            } else if (permissionPending) {
                "等待系统 VPN 授权"
            } else {
                "选用配置并开启连接，状态将实时显示。"
            }
        }
        totalsLabel?.text = "↓ " + bytes(VpnController.downlinkTotal) + "\n↑ " + bytes(VpnController.uplinkTotal)
        val labels = historyLabels
        val icons = historyIcons
        if (labels != null && icons != null) {
            val samples = VpnController.downloadHistory()
            // Contract: chronological order (oldest -> newest), negative values are unavailable.
            val available = minOf(6, samples.size)
            for (i in 0 until 6) {
                val source = i - (6 - available)
                val sample = if (source < 0) -1L else samples[samples.size - available + source]
                labels[i]?.text = bytes(sample)
                val icon = icons[i]
                if (icon != null) {
                    icon.setType(
                        when {
                            sample < 0 -> WeatherIconView.TYPE_UNKNOWN
                            sample == 0L -> WeatherIconView.TYPE_SUN
                            sample > 262144L -> WeatherIconView.TYPE_RAIN
                            else -> WeatherIconView.TYPE_CLOUD
                        },
                    )
                    icon.setTint(if (sample == 0L) 0xFFFFD24A.toInt() else theme.secondaryTextColor)
                }
            }
        }
        renderWeather(weatherState)
        val errorView = errorLabel
        if (errorView != null) {
            errorView.text = if (error.isEmpty()) "暂无服务错误" else error
            errorView.visibility = if (selectedTab == 3 || error.isNotEmpty()) View.VISIBLE else View.GONE
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
                        AlertDialog.Builder(this).setTitle("先选择有效配置")
                            .setMessage("请导入并选用自己的 sing-box JSON，然后开启连接。")
                            .setPositiveButton("导入 JSON") { _, _ -> importDocument() }
                            .setNeutralButton("管理配置") { _, _ -> selectTab(1) }
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
            } catch (e: Exception) {
                reportStartFailure(e)
            } catch (e: LinkageError) {
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

    private fun importFromUri(uri: Uri) {
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
            } catch (e: Exception) {
                handler.post { if (!isFinishing && !isDestroyed) showError("导入失败", e) }
            }
        }
    }

    // No call sites in the Java version either; kept for a one-to-one port.
    private fun showProfilePicker() {
        try {
            val profiles = ProfileStore.list(this)
            val active = ProfileStore.activeId(this)
            val names = Array(profiles.size + 1) { i ->
                if (i < profiles.size) {
                    profiles[i].name + (if (profiles[i].id == active) "  ✓" else "")
                } else {
                    "＋ 导入 / 管理配置"
                }
            }
            AlertDialog.Builder(this).setTitle("选用配置").setItems(names) { _, which ->
                if (which == profiles.size) selectTab(1) else selectProfile(profiles[which])
            }.show()
        } catch (e: Exception) {
            showError("无法读取配置", e)
        }
    }

    private fun canModify(): Boolean {
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
                    buildPanel()
                    toast("已选用 " + profile.name)
                }
            } catch (e: Exception) {
                reportProfileFailure(e)
            } catch (e: LinkageError) {
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

    private fun editActive() {
        if (!canModify()) return
        val id = ProfileStore.activeId(this)
        if (id.isEmpty()) {
            selectTab(1)
            return
        }
        try {
            showEditor(id, ProfileStore.activeName(this), ProfileStore.read(this, id))
        } catch (e: Exception) {
            showError("无法读取配置", e)
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

    private fun showEditor(id: String?, name: String, json: String) {
        if (!canModify()) return
        val content = column()
        content.setPadding(dp(20), dp(8), dp(20), dp(4))
        val nameInput = EditText(this)
        nameInput.setHint("配置名称")
        nameInput.setText(name)
        nameInput.setSingleLine(true)
        nameInput.setFilters(arrayOf<InputFilter>(InputFilter.LengthFilter(80)))
        content.addView(nameInput, full())
        val editor = EditText(this)
        editor.setGravity(Gravity.TOP or Gravity.START)
        editor.setTypeface(Typeface.MONOSPACE)
        editor.setTextSize(12f)
        editor.setInputType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        editor.setHorizontallyScrolling(true)
        editor.setText(json)
        editor.setMinLines(10)
        content.addView(editor, LinearLayout.LayoutParams(-1, dp(290)))
        val result = TextView(this)
        result.setText("保存前将检查 JSON 与 sing-box 配置。配置不会导出到公共目录。")
        result.setTextSize(12f)
        content.addView(result, full())
        val dialog = AlertDialog.Builder(this).setTitle(if (id == null) "导入 / 新建配置" else "编辑配置")
            .setView(content).setNegativeButton("取消", null)
            .setNeutralButton("检查", null).setPositiveButton("检查并保存", null).create()
        dialog.setOnShowListener {
            val validate = View.OnClickListener { clicked ->
                if (!canModify()) return@OnClickListener
                val save = clicked === dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val editedName = nameInput.text.toString()
                val editedJson = editor.text.toString()
                busy = true
                dialog.setCancelable(false)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                result.setText("正在调用 sing-box 内核检查…")
                val profileId = id
                worker.execute {
                    try {
                        if (save) {
                            ProfileStore.save(applicationContext, profileId, editedName, editedJson)
                        } else {
                            ProfileStore.validate(applicationContext, editedJson)
                        }
                        handler.post {
                            busy = false
                            if (isFinishing || isDestroyed) return@post
                            if (save) {
                                dialog.dismiss()
                                buildPanel()
                                toast("配置已通过检查并保存")
                            } else {
                                result.setText("内核检查通过。是否能建立 VPN 仍取决于 TUN、权限与网络环境。")
                                unlockEditor(dialog)
                            }
                        }
                    } catch (e: Exception) {
                        reportEditorFailure(dialog, result, e)
                    } catch (e: LinkageError) {
                        reportEditorFailure(dialog, result, e)
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(validate)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(validate)
        }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun reportEditorFailure(dialog: AlertDialog, result: TextView, e: Throwable) {
        handler.post {
            busy = false
            if (isFinishing || isDestroyed) return@post
            result.setText("检查 / 保存失败：" + safeError(e))
            unlockEditor(dialog)
        }
    }

    private fun unlockEditor(dialog: AlertDialog) {
        dialog.setCancelable(true)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
    }

    private fun deleteProfile(profile: ProfileStore.Profile) {
        if (!canModify()) return
        AlertDialog.Builder(this).setTitle("删除配置？")
            .setMessage("将从本机删除「" + profile.name + "」。此操作不可撤销。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                if (!canModify()) return@setPositiveButton
                try {
                    ProfileStore.delete(this, profile.id)
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
        container.background = round(withAlpha(theme.cardColor, 0xE8), 22)
        return container
    }

    private fun column(): LinearLayout {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        return layout
    }

    private fun row(): LinearLayout {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.setGravity(Gravity.CENTER_VERTICAL)
        return layout
    }

    private fun text(value: String, size: Float, color: Int): TextView {
        val view = TextView(this)
        view.setText(value)
        view.setTextSize(size)
        val resolved = if (color == WHITE) theme.textColor else if (color == SOFT) theme.secondaryTextColor else color
        view.setTextColor(resolved)
        view.fontFeatureSettings = "kern"
        view.setLineSpacing(dp(2).toFloat(), 1f)
        return view
    }

    private fun weatherCityName(): String {
        val city = weatherService?.getCity()
        if (city != null) return city.displayName
        val state = weatherState
        return if (state == null || state.city.isEmpty()) "台北" else state.city
    }

    /** Paints the sky screen. Cheap text updates every tick; rows and bars only on new data. */
    private fun renderWeather(state: WeatherState?) {
        val title = cityTitle ?: return
        title.text = weatherCityName()
        val has = state != null && state.hasData()
        if (!has) {
            bigTemp?.text = "—"
            conditionLine?.text = if (state?.loading == true) "天气加载中…" else "天气暂不可用"
            aqiPillText?.text = "空气质量 —"
            val message = state?.errorMessage.orEmpty()
            weatherError?.text = message
            weatherError?.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
            if (renderedWeather != "none") {
                renderedWeather = "none"
                precipChart?.setValues(emptyList())
                precipSentence?.text = "暂无降水数据"
                precipEmpty?.visibility = View.GONE
                renderForecast(null)
            }
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
        aqiPillText?.text = if (current.aqi.isFinite()) {
            "空气质量 " + current.aqiLabel + " " + Math.round(current.aqi)
        } else {
            "空气质量 —"
        }
        val message = when {
            current.error && current.errorMessage.isNotEmpty() -> current.errorMessage
            current.stale -> "数据已过期 · 点击刷新"
            else -> ""
        }
        weatherError?.text = message
        weatherError?.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        weatherError?.setOnClickListener { refreshWeather(true) }

        val stamp = current.updatedAt.toString() + "/" + current.weatherCode + "/" +
            current.daily.size + "/" + current.precipitation15.size + "/" + current.aqi
        if (stamp == renderedWeather) return
        renderedWeather = stamp
        precipSentence?.text = precipSummary(current.precipitation15)
        precipChart?.setValues(current.precipitation15)
        precipEmpty?.let { empty ->
            empty.visibility = if (current.precipitation15.isEmpty()) View.VISIBLE else View.GONE
            empty.text = "该地区暂不提供 15 分钟级降水预报，可参考下方日预报。"
        }
        renderForecast(current)
    }

    private fun renderForecast(state: WeatherState?) {
        val rows = forecastRows ?: return
        rows.removeAllViews()
        val daily = state?.daily.orEmpty().filter { it.isValid() }
        if (daily.isEmpty()) {
            val hint = text("暂无逐日预报，点击天气胶囊或稍后重试。", 13f, SOFT)
            hint.setPadding(0, dp(14), 0, dp(14))
            rows.addView(hint, full())
            return
        }
        val floor = daily.minOf { it.min }
        val ceiling = daily.maxOf { it.max }
        daily.forEachIndexed { index, day ->
            if (index > 0) {
                val divider = View(this)
                divider.setBackgroundColor(withAlpha(theme.textColor, 0x1F))
                rows.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            }
            val line = row()
            line.setPadding(0, dp(10), 0, dp(10))
            val label = text(day.label, 15f, WHITE)
            line.addView(label, LinearLayout.LayoutParams(dp(52), -2))
            val description = text(themeFor(day.weatherCode).weatherNameZh, 14f, withAlpha(theme.secondaryTextColor, 0xF0))
            description.setSingleLine(true)
            description.ellipsize = TextUtils.TruncateAt.END
            line.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
            val icon = WeatherIconView(this)
            val dayTheme = themeFor(day.weatherCode)
            icon.setType(dayTheme.iconType)
            icon.setTint(theme.iconColor)
            icon.contentDescription = dayTheme.weatherNameZh
            val iconParams = LinearLayout.LayoutParams(dp(26), dp(26))
            iconParams.leftMargin = dp(6)
            iconParams.rightMargin = dp(8)
            line.addView(icon, iconParams)
            val low = text(Math.round(day.min).toString() + "°", 14f, theme.secondaryTextColor)
            low.setGravity(Gravity.END)
            line.addView(low, LinearLayout.LayoutParams(dp(36), -2))
            val bar = TemperatureRangeView(this)
            bar.setTrackColor(withAlpha(theme.textColor, 0x26))
            bar.setRange(day.min, day.max, floor, ceiling)
            val barParams = LinearLayout.LayoutParams(0, dp(5), 1f)
            barParams.leftMargin = dp(10)
            barParams.rightMargin = dp(10)
            line.addView(bar, barParams)
            val high = text(Math.round(day.max).toString() + "°", 14f, WHITE)
            high.setGravity(Gravity.END)
            line.addView(high, LinearLayout.LayoutParams(dp(36), -2))
            rows.addView(line, full())
        }
    }

    /** Reuses the WMO mapping for a single code without inventing a reading. */
    private fun themeFor(code: Int): WeatherTheme = WeatherTheme.from(
        WeatherState(
            "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, code, true, 1L, false, false, false,
        ),
    )

    /**
     * Turns the 15-minute nowcast into one sentence: when it stops, whether it returns, or when it
     * starts. Thresholds are 0.05 mm per 15 minutes so trace amounts do not count as rain.
     */
    private fun precipSummary(values: List<Double>): String {
        if (values.isEmpty()) return "暂无分钟级降水预报"
        val wet = 0.05
        if (values.first() > wet) {
            val stop = values.indexOfFirst { it <= wet }
            if (stop < 0) return "未来两小时降水持续"
            val resume = (stop until values.size).firstOrNull { values[it] > wet }
            return if (resume != null) {
                "约" + roundMinutes(stop * 15) + "分钟后雨渐停，不过" +
                    roundMinutes(resume * 15) + "分钟左右还会下雨"
            } else {
                "约" + roundMinutes(stop * 15) + "分钟后雨渐停"
            }
        }
        val start = values.indexOfFirst { it > wet }
        return if (start >= 0) {
            "预计" + roundMinutes(start * 15) + "分钟后开始下雨"
        } else {
            "未来两小时无降水"
        }
    }

    private fun roundMinutes(minutes: Int): Int = Math.max(5, Math.round(minutes / 5f) * 5)

    /** Big reading with the ring degree sign scaled down and raised, like the reference layout. */
    private fun temperatureSpan(value: String): CharSequence {
        val text = value + "°"
        val span = SpannableString(text)
        val start = text.length - 1
        span.setSpan(RelativeSizeSpan(0.34f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(SuperscriptSpan(), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    private fun showAirQuality() {
        val current = weatherState
        val value = current?.aqi ?: Double.NaN
        val message = if (value.isFinite()) {
            "US AQI " + Math.round(value) + " · " + current?.aqiLabel.orEmpty() +
                "\n\n空气质量指数来自 Open-Meteo 空气质量接口，采用 US AQI 口径；" +
                "数值越高表示污染越重，与 VPN 连接状态无关。"
        } else {
            "暂未获取到空气质量数据。空气质量为可选接口，失败不影响天气显示。"
        }
        AlertDialog.Builder(this).setTitle("空气质量").setMessage(message)
            .setPositiveButton("知道了", null).show()
    }

    private fun refreshWeather(force: Boolean) {
        weatherService?.fetch(force)
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

    private fun showCityPicker() {
        val panel = column()
        panel.setPadding(dp(18), dp(8), dp(18), 0)
        val input = EditText(this)
        cityInput = input
        input.setHint("搜索城市，例如 台北市 / Tokyo")
        input.setSingleLine(true)
        panel.addView(input, full())
        val status = TextView(this)
        citySearchStatus = status
        status.setTextSize(12f)
        status.setText("输入城市名后会自动搜索")
        panel.addView(status, full())
        val results = column()
        cityResults = results
        val resultsScroll = ScrollView(this)
        resultsScroll.addView(results, FrameLayout.LayoutParams(-1, -2))
        panel.addView(resultsScroll, LinearLayout.LayoutParams(-1, dp(240)))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                val service = weatherService ?: return
                citySearchStatus?.text = if (query.isEmpty()) "输入城市名后会自动搜索" else "正在搜索…"
                service.searchCity(query)
            }
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择天气城市")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setNeutralButton("使用默认城市（台北）") { _, _ ->
                weatherService?.setCity(WeatherService.DEFAULT_CITY)
                weatherSearchRefresh()
            }
            .create()
        cityDialog = dialog
        dialog.setOnDismissListener { cityDialog = null }
        dialog.show()
        weatherService?.searchCity(input.text.toString())
    }

    private fun weatherSearchRefresh() {
        refreshWeather(true)
        if (weatherState != null) applyWeather()
    }

    private fun showCityResults(cities: List<WeatherService.City>?) {
        val results = cityResults
        if (results == null || isFinishing || isDestroyed) return
        results.removeAllViews()
        if (cities == null || cities.isEmpty()) {
            val input = cityInput
            val emptyQuery = input == null || input.text.toString().trim().isEmpty()
            citySearchStatus?.text = if (emptyQuery) "输入城市名后会自动搜索" else "没有找到匹配城市"
            return
        }
        citySearchStatus?.text = "找到 " + cities.size + " 个结果"
        for (city in cities) {
            // The picker is a light system dialog, so its rows use fixed dialog-friendly colors
            // instead of the sky palette (which would be white-on-white in day themes).
            val item = text(
                city.displayName + (if (city.country.isEmpty()) "" else " · " + city.country),
                15f,
                0xFF1B2430.toInt(),
            )
            item.setPadding(dp(12), dp(13), dp(12), dp(13))
            item.background = RippleDrawable(
                ColorStateList.valueOf(0x22000000),
                round(0xFFEDF1F6.toInt(), 12),
                round(0xFFFFFFFF.toInt(), 12),
            )
            item.setOnClickListener {
                weatherService?.setCity(city)
                cityDialog?.dismiss()
                refreshWeather(true)
            }
            val params = full()
            params.bottomMargin = dp(6)
            results.addView(item, params)
        }
    }

    private fun action(label: String, onClick: () -> Unit): Button {
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
        val ripple = ColorStateList.valueOf(0x33FFFFFF)
        button.background = RippleDrawable(ripple, round(0x24FFFFFF, 24), round(WHITE, 24))
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

    private fun full(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    private fun space(parent: LinearLayout, size: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(size)))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showError(title: String, e: Throwable) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle(title).setMessage(safeError(e)).setPositiveButton("知道了", null).show()
    }

    private companion object {
        const val IMPORT_JSON = 110
        const val VPN_PERMISSION = 111
        const val WHITE = -1 // Color.WHITE
        const val SOFT = -0x20000001 // 0xDFFFFFFF
        const val T_PILL = 46
        const val T_DOCK_SLOT = 52
        const val T_DOCK_PAD = 4
        const val T_DOCK_MARGIN = 10

        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

        fun themeKeyOf(value: WeatherTheme): String = value.type.name + (if (value.isDay) "-day" else "-night")

        fun safeError(e: Throwable): String {
            val message = e.message
            return if (message == null || message.trim().isEmpty()) e.javaClass.simpleName else message
        }

        fun bytes(value: Long): String {
            if (value < 0) return "—"
            if (value < 1024) return value.toString() + " B"
            if (value < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", value / 1024.0)
            if (value < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024))
            return String.format(Locale.ROOT, "%.2f GB", value / (1024.0 * 1024 * 1024))
        }

        fun rate(value: Long): String = if (value < 0) "—" else bytes(value) + "/s"
    }
}
