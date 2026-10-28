package io.github.yuedaochangmendian.skybox

import android.app.AlertDialog
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout

/**
 * Dialogs and anchored menus of the sky screen, split out of MainActivity so the activity keeps
 * only layout and lifecycle code. Everything here runs on the UI thread and reaches the activity
 * through its internal surface.
 */

/** A popup that grows out of the floating circle the user tapped. */
internal fun MainActivity.showAnchoredMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
    val popup = PopupMenu(this, anchor)
    items.forEachIndexed { index, entry -> popup.menu.add(0, index, index, entry.first) }
    popup.setOnMenuItemClickListener { item ->
        if (item.itemId in items.indices) items[item.itemId].second.invoke()
        true
    }
    popup.show()
}

internal fun MainActivity.showEditor(id: String?, name: String, json: String) {
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
                            staticGroupCacheReset()
                            buildPanel()
                            toast("配置已通过检查并保存")
                        } else {
                            result.setText("内核检查通过。是否能建立 VPN 仍取决于 TUN、权限与网络环境。")
                            unlockEditor(dialog)
                        }
                    }
                } catch (e: Throwable) {
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

private fun MainActivity.reportEditorFailure(dialog: AlertDialog, result: TextView, e: Throwable) {
    handler.post {
        busy = false
        if (isFinishing || isDestroyed) return@post
        result.setText("检查 / 保存失败：" + MainActivity.safeError(e))
        unlockEditor(dialog)
    }
}

private fun MainActivity.unlockEditor(dialog: AlertDialog) {
    dialog.setCancelable(true)
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
}

internal fun MainActivity.showCityPicker() {
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
        .setNeutralButton("跟随节点地区") { _, _ ->
            val service = weatherService
            if (service != null) {
                service.setCity(service.getCity(), manual = false)
                refreshNodeRegion(true)
                weatherSearchRefresh()
            }
        }
        .create()
    cityDialog = dialog
    dialog.setOnDismissListener { cityDialog = null }
    dialog.show()
    weatherService?.searchCity(input.text.toString())
}

internal fun MainActivity.showCityResults(cities: List<WeatherService.City>?) {
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
        item.background = rippleSurface(
            0x22000000,
            0xFFEDF1F6.toInt(),
            0xFFFFFFFF.toInt(),
            12,
        )
        item.setOnClickListener {
            weatherService?.setCity(city, manual = true)
            cityDialog?.dismiss()
            refreshWeather(true)
        }
        val params = full()
        params.bottomMargin = dp(6)
        results.addView(item, params)
    }
}

/** The real forecast the provider returned: hours, days, AQI, wind and the minute nowcast. */
internal fun MainActivity.showWeatherDetail() {
    val state = weatherState
    if (state == null || !state.hasData()) {
        toast("天气还未加载完成")
        return
    }
    val content = column()
    content.setPadding(dp(20), dp(10), dp(20), dp(6))
    val parts = ArrayList<String>()
    if (state.apparentTemperature.isFinite()) parts.add("体感 " + Math.round(state.apparentTemperature) + "°")
    if (state.windSpeed.isFinite()) parts.add("风速 " + Math.round(state.windSpeed) + " km/h")
    if (state.aqi.isFinite()) parts.add("空气" + state.aqiLabel + " " + Math.round(state.aqi))
    val summary = TextView(this)
    summary.setTextSize(13f)
    summary.setTextColor(0xFF1B2430.toInt())
    summary.setText(if (parts.isEmpty()) "详细读数暂缺" else parts.joinToString(" · "))
    content.addView(summary, full())
    val hours = state.hourly.filter { it.isValid() }
    if (hours.isNotEmpty()) {
        val headline = TextView(this)
        headline.setTextSize(14f)
        headline.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        headline.setTextColor(0xFF1B2430.toInt())
        headline.setText("未来 24 小时")
        headline.setPadding(0, dp(14), 0, dp(6))
        content.addView(headline, full())
        val strip = row()
        for (hour in hours) {
            val cell = column()
            cell.setGravity(Gravity.CENTER)
            cell.setPadding(dp(6), dp(2), dp(6), dp(2))
            val label = TextView(this)
            label.setTextSize(11f)
            label.setTextColor(0xFF44566B.toInt())
            label.setText(hour.label)
            cell.addView(label, LinearLayout.LayoutParams(-2, -2))
            val icon = WeatherIconView(this)
            icon.setType(themeFor(hour.weatherCode).iconType)
            icon.setTint(0xFF287DF0.toInt())
            cell.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val temperature = TextView(this)
            temperature.setTextSize(12f)
            temperature.setTextColor(0xFF1B2430.toInt())
            temperature.setText(Math.round(hour.temperature).toString() + "°")
            cell.addView(temperature, LinearLayout.LayoutParams(-2, -2))
            strip.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val stripScroll = HorizontalScrollView(this)
        stripScroll.setHorizontalScrollBarEnabled(false)
        stripScroll.addView(strip, FrameLayout.LayoutParams(-2, -2))
        content.addView(stripScroll, full())
    }
    val nowcast = precipSummary(state.precipitation15)
    if (state.precipitation15.isNotEmpty()) {
        val rain = TextView(this)
        rain.setTextSize(13f)
        rain.setTextColor(0xFF354C63.toInt())
        rain.setText(nowcast)
        rain.setPadding(0, dp(12), 0, 0)
        content.addView(rain, full())
    }
    val daily = state.daily.filter { it.isValid() }
    if (daily.isNotEmpty()) {
        val headline = TextView(this)
        headline.setTextSize(14f)
        headline.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        headline.setTextColor(0xFF1B2430.toInt())
        headline.setText("逐日预报")
        headline.setPadding(0, dp(14), 0, dp(4))
        content.addView(headline, full())
        val floor = daily.minOf { it.min }
        val ceiling = daily.maxOf { it.max }
        for (day in daily) {
            val line = row()
            line.setPadding(0, dp(8), 0, dp(8))
            val label = TextView(this)
            label.setTextSize(14f)
            label.setTextColor(0xFF1B2430.toInt())
            label.setText(day.label)
            label.width = dp(48)
            line.addView(label, LinearLayout.LayoutParams(dp(48), -2))
            val description = TextView(this)
            description.setTextSize(13f)
            description.setTextColor(0xFF44566B.toInt())
            description.setText(themeFor(day.weatherCode).weatherNameZh)
            description.setSingleLine(true)
            description.ellipsize = TextUtils.TruncateAt.END
            line.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
            val low = TextView(this)
            low.setTextSize(13f)
            low.setTextColor(0xFF44566B.toInt())
            low.setText(Math.round(day.min).toString() + "°")
            low.gravity = Gravity.END
            line.addView(low, LinearLayout.LayoutParams(dp(34), -2))
            val bar = TemperatureRangeView(this)
            bar.setTrackColor(0x261B2430)
            bar.setRange(day.min, day.max, floor, ceiling)
            val barParams = LinearLayout.LayoutParams(0, dp(5), 1f)
            barParams.leftMargin = dp(8)
            barParams.rightMargin = dp(8)
            line.addView(bar, barParams)
            val high = TextView(this)
            high.setTextSize(13f)
            high.setTextColor(0xFF1B2430.toInt())
            high.setText(Math.round(day.max).toString() + "°")
            high.gravity = Gravity.END
            line.addView(high, LinearLayout.LayoutParams(dp(34), -2))
            content.addView(line, full())
        }
    }
    val scroll = ScrollView(this)
    scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
    scroll.isVerticalScrollBarEnabled = false
    AlertDialog.Builder(this).setTitle(state.city + (if (state.country.isEmpty()) "" else " · " + state.country))
        .setView(scroll).setPositiveButton("关闭", null).show()
}

internal fun MainActivity.showLatencyDetail() {
    val running = VpnController.running
    val message = when {
        !running -> "当前未连接。开启 VPN 后，这里显示经由当前路由的 HTTPS 往返延迟。"
        LatencyProbe.testing -> "正在测试…"
        LatencyProbe.milliseconds >= 0 -> {
            val builder = StringBuilder()
            builder.append("当前 ").append(LatencyProbe.milliseconds).append(" ms")
            if (LatencyProbe.fastest >= 0) {
                builder.append("\n最快 ").append(LatencyProbe.fastest).append(" ms · 最慢 ")
                    .append(LatencyProbe.slowest).append(" ms")
            }
            builder.append("\n\n通过当前 VPN 路由请求 gstatic generate_204 的耗时，反映整体出口质量，")
                .append("不等于节点 TCP 握手延迟。")
            builder.toString()
        }
        else -> "测试失败：" + LatencyProbe.failure.ifEmpty { "无法到达测试地址" } +
            "\n\n请确认配置可用后重试。"
    }
    AlertDialog.Builder(this).setTitle("延迟").setMessage(message)
        .setPositiveButton("知道了", null).show()
}

/** Reuses the WMO mapping for a single code without inventing a reading. */
internal fun MainActivity.themeFor(code: Int): WeatherTheme = WeatherTheme.from(
    WeatherState(
        "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, code, true, 1L, false, false, false,
    ),
)

/**
 * Turns the 15-minute nowcast into one sentence: when it stops, whether it returns, or when it
 * starts. Thresholds are 0.05 mm per 15 minutes so trace amounts do not count as rain.
 */
internal fun precipSummary(values: List<Double>): String {
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

private fun MainActivity.rippleSurface(rippleColor: Int, surface: Int, border: Int, radius: Int) =
    android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(rippleColor),
        roundSurface(surface, radius),
        roundSurface(border, radius),
    )
