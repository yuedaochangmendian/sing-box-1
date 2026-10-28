package io.github.yuedaochangmendian.skybox

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Dependency-free Open-Meteo client. Network and JSON work never runs on the UI thread.
 * Call close() from the owner's onDestroy(). All callbacks run on the main thread, with
 * cancellation checked both before posting and at delivery. Search and weather have separate
 * cancellation tokens, so typing a city does not cancel a weather refresh.
 *
 * The last successful reading and the per-coordinate request-start times are persisted in the
 * `skybox_weather` preferences, so Activity recreation (rotation or recycling) keeps showing the
 * previous reading and still honours [MIN_REFRESH_INTERVAL_MS] instead of immediately requesting
 * again. Persisted times are wall-clock (`System.currentTimeMillis()`) values, because they must
 * survive process restarts; a record more than [FUTURE_TOLERANCE_MS] in the future (system clock
 * rolled back) is treated as missing so requests are allowed again.
 */
class WeatherService(context: Context, callback: Callback) : AutoCloseable {
    interface Callback {
        fun onWeather(state: WeatherState)

        fun onCities(cities: List<City>)

        /** citySearch is true for geocoding failures, false for weather failures. */
        fun onError(message: String, citySearch: Boolean)
    }

    class City(
        displayName: String,
        country: String?,
        latitude: Double,
        longitude: Double,
        timezone: String?,
    ) {
        val displayName: String
        val country: String
        val latitude: Double
        val longitude: Double
        val timezone: String

        init {
            val name = displayName.trim()
            require(
                name.isNotEmpty() && latitude.isFinite() && latitude in -90.0..90.0 &&
                    longitude.isFinite() && longitude in -180.0..180.0,
            ) { "城市名称或经纬度无效" }
            val zone = timezone?.trim()
            this.displayName = name
            this.country = country?.trim().orEmpty()
            this.latitude = latitude
            this.longitude = longitude
            this.timezone = if (zone.isNullOrEmpty()) "auto" else zone
        }

        override fun toString(): String =
            if (country.isEmpty()) displayName else "$displayName · $country"
    }

    /** One in-flight request; cancellation is checked both before posting and at delivery. */
    private class Request {
        @Volatile
        var cancelled = false

        private var connection: HttpURLConnection? = null
        private var future: Future<*>? = null

        @Synchronized
        fun attach(value: HttpURLConnection) {
            check()
            connection = value
        }

        @Synchronized
        fun attach(value: Future<*>) {
            future = value
            if (cancelled) value.cancel(true)
        }

        @Synchronized
        fun detach(value: HttpURLConnection) {
            if (connection === value) connection = null
        }

        @Synchronized
        fun cancel() {
            cancelled = true
            future?.cancel(true)
            connection?.disconnect()
            connection = null
        }

        fun check() {
            if (cancelled || Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("请求已取消")
            }
        }
    }

    private val lock = Any()
    private val preferences: SharedPreferences =
        (context.applicationContext ?: context)
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val workers: ExecutorService = Executors.newFixedThreadPool(2)

    /** Request-start times (wall clock, epoch ms) keyed by coordinate; mirrors `attempt_*` prefs. */
    private val lastAttempts = HashMap<String, Long>()
    private val snapshots = HashMap<String, WeatherState>()

    private var callback: Callback? = callback
    private var city: City
    private var state: WeatherState
    private var weatherRequest: Request? = null
    private var searchRequest: Request? = null
    private var fetching = false
    private var closed = false

    init {
        city = readCity()
        restoreAttempts()
        val restored = restoreSnapshot()
        if (restored != null) {
            // Keep the restored reading addressable by coordinate so switching back to it is instant.
            snapshots[coordinateKey(restored.latitude, restored.longitude)] = restored
        }
        state = if (restored != null &&
            coordinateKey(restored.latitude, restored.longitude) == coordinateKey(city.latitude, city.longitude)
        ) {
            relabel(restored, city)
        } else {
            empty(city)
        }
        saveCity(city) // Persist the default on first use as well as explicit selections.
    }

    fun getCity(): City = synchronized(lock) { city }

    /** Returns a snapshot; it does not initiate a request. */
    fun getState(): WeatherState = synchronized(lock) { aged(state) }

    /** Persists a selection and fetches it. Old-city responses can never overwrite the selection. */
    fun setCity(selected: City) {
        synchronized(lock) {
            if (closed) return
            weatherRequest?.cancel()
            fetching = false
            city = selected
            saveCity(selected)
            val cached = snapshots[coordinateKey(selected.latitude, selected.longitude)]
            state = if (cached == null) empty(selected) else relabel(cached, selected)
            fetch(false)
        }
    }

    /**
     * Requests current weather, or delivers the cached snapshot. force skips the successful-data
     * freshness check, but NEVER bypasses the 15-minute per-coordinate request-start limit.
     * Failed attempts also count towards that limit. This object schedules no periodic polling.
     */
    fun fetch(force: Boolean) {
        synchronized(lock) {
            if (closed || fetching) return
            weatherRequest?.cancel()
            val request = Request()
            weatherRequest = request
            state = aged(state)
            val now = System.currentTimeMillis()
            val last = lastAttempt(coordinateKey(city.latitude, city.longitude))
            val throttled = last != null && now >= last && now - last < MIN_REFRESH_INTERVAL_MS
            if (throttled || (!force && state.hasData() && !state.stale && !state.error)) {
                val cached = state
                post(request, false) { callback?.onWeather(cached) }
                return
            }
            val target = city
            recordAttempt(coordinateKey(target.latitude, target.longitude), now)
            fetching = true
            state = state.withStatus(state.stale, false, true, "")
            val loading = state
            post(request, false) { callback?.onWeather(loading) }
            request.attach(workers.submit(Runnable { loadWeather(request, target) }))
        }
    }

    /** Latest query wins. Empty input cancels any pending search and delivers an empty list. */
    fun searchCity(query: String?) {
        synchronized(lock) {
            if (closed) return
            searchRequest?.cancel()
            val request = Request()
            searchRequest = request
            val text = query?.trim().orEmpty()
            if (text.isEmpty()) {
                post(request, true) { callback?.onCities(emptyList()) }
                return
            }
            if (text.length > MAX_QUERY_LENGTH) {
                post(request, true) { callback?.onError("城市名称最多 $MAX_QUERY_LENGTH 个字符", true) }
                return
            }
            request.attach(workers.submit(Runnable {
                try {
                    var found = searchOnce(text, request)
                    // The geocoding index stores full administrative names (台北市, not 台北),
                    // so retry once with the common 市 suffix before reporting an empty result.
                    if (found.isEmpty() && text.length >= 2 && !text.endsWith("市")) {
                        found = searchOnce(text + "市", request)
                    }
                    val immutable: List<City> = Collections.unmodifiableList(found)
                    post(request, true) { callback?.onCities(immutable) }
                } catch (error: Exception) {
                    val message = errorMessage(error)
                    post(request, true) { callback?.onError(message, true) }
                }
            }))
        }
    }

    private fun searchOnce(text: String, request: Request): List<City> {
        val uri = Uri.parse("https://geocoding-api.open-meteo.com/v1/search")
            .buildUpon().appendQueryParameter("name", text)
            .appendQueryParameter("count", "10")
            .appendQueryParameter("language", "zh")
            .appendQueryParameter("format", "json").build()
        val document = getJson(uri.toString(), request)
        val results: JSONArray? = document.optJSONArray("results")
        val found = ArrayList<City>()
        if (results != null) {
            for (i in 0 until results.length()) {
                request.check()
                val item = results.optJSONObject(i) ?: continue
                try {
                    var name = item.getString("name").trim()
                    val region = item.optString("admin1", "")
                    if (region.isNotEmpty() && region != name) name += " · $region"
                    found.add(
                        City(
                            name, item.optString("country", ""), number(item, "latitude"),
                            number(item, "longitude"), item.optString("timezone", "auto"),
                        ),
                    )
                } catch (ignored: JSONException) {
                    // One malformed candidate must not hide the other valid cities.
                } catch (ignored: IllegalArgumentException) {
                    // Same: an invalid candidate is skipped, not fatal to the whole search.
                }
            }
        }
        return found
    }

    private fun loadWeather(request: Request, target: City) {
        try {
            val uri = Uri.parse("https://api.open-meteo.com/v1/forecast").buildUpon()
                .appendQueryParameter("latitude", target.latitude.toString())
                .appendQueryParameter("longitude", target.longitude.toString())
                .appendQueryParameter(
                    "current",
                    "temperature_2m,apparent_temperature,precipitation,wind_speed_10m,weather_code,is_day",
                )
                .appendQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min")
                .appendQueryParameter("forecast_days", "4")
                .appendQueryParameter("minutely_15", "precipitation")
                .appendQueryParameter("forecast_minutely_15", "8")
                .appendQueryParameter("temperature_unit", "celsius")
                .appendQueryParameter("wind_speed_unit", "kmh")
                .appendQueryParameter("precipitation_unit", "mm")
                .appendQueryParameter("timezone", target.timezone).build()
            val document = getJson(uri.toString(), request)
            val current = document.getJSONObject("current")
            val code = number(current, "weather_code")
            val day = number(current, "is_day")
            val rain = number(current, "precipitation")
            val wind = number(current, "wind_speed_10m")
            if (code != Math.rint(code) || code < 0 || code > 99 ||
                (day != 0.0 && day != 1.0) || rain < 0 || wind < 0
            ) {
                throw JSONException("天气字段无效")
            }
            val daily = parseDaily(document)
            val nowcast = parseNowcast(document)
            // Air quality is a separate, optional endpoint: its failure must not hide the weather.
            val air = loadAirQuality(request, target)
            val result = WeatherState(
                target.displayName, target.country, target.latitude, target.longitude,
                number(current, "temperature_2m"), number(current, "apparent_temperature"),
                rain, wind, code.toInt(), day == 1.0, System.currentTimeMillis(),
                false, false, false, "",
                daily, nowcast, air.first, air.second,
            )
            val zone = document.optString("timezone", target.timezone)
            val resolved = City(
                target.displayName, target.country, target.latitude, target.longitude, zone,
            )
            post(request, false) {
                fetching = false
                city = resolved
                saveCity(resolved)
                state = result
                snapshots[coordinateKey(target.latitude, target.longitude)] = result
                saveSnapshot(result)
                callback?.onWeather(result)
            }
        } catch (error: Exception) {
            val message = errorMessage(error)
            post(request, false) {
                fetching = false
                state = state.withStatus(true, true, false, message)
                snapshots[coordinateKey(target.latitude, target.longitude)] = state
                callback?.onWeather(state)
                // onWeather may synchronously close the service or change the city.
                if (active(request, false)) callback?.onError(message, false)
            }
        }
    }

    /** Parses `daily`; a malformed day is skipped instead of failing the whole reading. */
    private fun parseDaily(document: JSONObject): List<DailyForecast> {
        val daily = document.optJSONObject("daily") ?: return emptyList()
        val times = daily.optJSONArray("time") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code")
        val maxima = daily.optJSONArray("temperature_2m_max")
        val minima = daily.optJSONArray("temperature_2m_min")
        if (codes == null || maxima == null || minima == null) return emptyList()
        val count = minOf(times.length(), codes.length(), maxima.length(), minima.length(), 4)
        val result = ArrayList<DailyForecast>(count)
        for (i in 0 until count) {
            val max = maxima.optDouble(i, Double.NaN)
            val min = minima.optDouble(i, Double.NaN)
            if (!max.isFinite() || !min.isFinite() || max < min) continue
            val rawCode = codes.optDouble(i, -1.0)
            val code = if (rawCode.isFinite()) rawCode.toInt() else -1
            result.add(DailyForecast(dayLabel(times.optString(i, ""), i), code, max, min))
        }
        return result
    }

    /** Parses the 15-minute nowcast; values are mm per 15 minutes and never negative. */
    private fun parseNowcast(document: JSONObject): List<Double> {
        val section = document.optJSONObject("minutely_15") ?: return emptyList()
        val values = section.optJSONArray("precipitation") ?: return emptyList()
        val result = ArrayList<Double>(values.length())
        for (i in 0 until values.length()) {
            val value = values.optDouble(i, 0.0)
            result.add(if (value.isFinite() && value > 0) value else 0.0)
        }
        return result
    }

    /**
     * Optional US AQI lookup. Returns (value, band) or (NaN, "") when the endpoint is unavailable,
     * so a missing air-quality reading never turns into a weather failure.
     */
    private fun loadAirQuality(request: Request, target: City): Pair<Double, String> {
        return try {
            val uri = Uri.parse("https://air-quality-api.open-meteo.com/v1/air-quality").buildUpon()
                .appendQueryParameter("latitude", target.latitude.toString())
                .appendQueryParameter("longitude", target.longitude.toString())
                .appendQueryParameter("current", "us_aqi")
                .appendQueryParameter("timezone", target.timezone).build()
            val document = getJson(uri.toString(), request)
            val value = number(document.getJSONObject("current"), "us_aqi")
            if (!value.isFinite() || value < 0) Pair(Double.NaN, "") else Pair(value, aqiBand(value))
        } catch (error: InterruptedIOException) {
            throw error
        } catch (ignored: Exception) {
            Pair(Double.NaN, "")
        }
    }

    private fun getJson(address: String, request: Request): JSONObject {
        request.check()
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            request.attach(connection)
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false // Never forward to a non-HTTPS redirect.
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            request.check()
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("天气服务 HTTP $status")
            }
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                throw IOException("天气响应超过 ${MAX_RESPONSE_BYTES / 1024} KB 限制")
            }
            val encoding = connection.contentEncoding
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                throw IOException("天气服务返回了不支持的内容编码")
            }
            connection.inputStream.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(4096)
                    var count = input.read(buffer)
                    while (count != -1) {
                        request.check()
                        if (count > MAX_RESPONSE_BYTES - output.size()) {
                            throw IOException("天气响应超过 ${MAX_RESPONSE_BYTES / 1024} KB 限制")
                        }
                        output.write(buffer, 0, count)
                        count = input.read(buffer)
                    }
                    request.check()
                    val result = JSONObject(String(output.toByteArray(), StandardCharsets.UTF_8))
                    if (result.optBoolean("error", false)) {
                        throw IOException("天气服务暂时无法处理请求")
                    }
                    return result
                }
            }
        } finally {
            request.detach(connection)
            connection.disconnect()
        }
    }

    private fun post(request: Request, search: Boolean, delivery: () -> Unit) {
        synchronized(lock) {
            if (!active(request, search)) return // Check BEFORE crossing to the main thread.
            main.post {
                synchronized(lock) {
                    if (active(request, search)) delivery()
                }
            }
        }
    }

    private fun active(request: Request, search: Boolean): Boolean =
        !closed && !request.cancelled && callback != null &&
            request === (if (search) searchRequest else weatherRequest)

    private fun readCity(): City {
        return try {
            City(
                preferences.getString(KEY_CITY, DEFAULT_CITY.displayName) ?: DEFAULT_CITY.displayName,
                preferences.getString(KEY_COUNTRY, DEFAULT_CITY.country) ?: DEFAULT_CITY.country,
                (preferences.getString(KEY_LATITUDE, "25.0330") ?: "25.0330").toDouble(),
                (preferences.getString(KEY_LONGITUDE, "121.5654") ?: "121.5654").toDouble(),
                preferences.getString(KEY_TIMEZONE, DEFAULT_CITY.timezone) ?: DEFAULT_CITY.timezone,
            )
        } catch (ignored: RuntimeException) {
            DEFAULT_CITY
        }
    }

    private fun saveCity(value: City) {
        preferences.edit().putString(KEY_CITY, value.displayName)
            .putString(KEY_COUNTRY, value.country)
            .putString(KEY_LATITUDE, value.latitude.toString())
            .putString(KEY_LONGITUDE, value.longitude.toString())
            .putString(KEY_TIMEZONE, value.timezone).apply()
    }

    /** Restores every still-useful `attempt_<lat>,<lon>` record; expired or rolled-back ones go. */
    private fun restoreAttempts() {
        val now = System.currentTimeMillis()
        val editor = preferences.edit()
        for ((name, raw) in preferences.all) {
            if (!name.startsWith(ATTEMPT_PREFIX)) continue
            val key = name.substring(ATTEMPT_PREFIX.length)
            val recorded = raw as? Long
            if (recorded == null || key.isEmpty() || !isUsableAttempt(recorded, now)) {
                // Expired records can no longer throttle anything and only bloat the prefs file.
                editor.remove(name)
                continue
            }
            lastAttempts[key] = recorded
        }
        editor.apply()
    }

    /**
     * Returns the persisted request-start time for a coordinate, or null when it cannot throttle.
     * A timestamp more than [FUTURE_TOLERANCE_MS] ahead of the current wall clock means the clock
     * jumped backwards; it is discarded so the user is not blocked until the clock catches up.
     */
    private fun lastAttempt(key: String): Long? {
        val recorded = lastAttempts[key] ?: return null
        val now = System.currentTimeMillis()
        if (recorded < 0L || recorded - now > FUTURE_TOLERANCE_MS) {
            lastAttempts.remove(key)
            preferences.edit().remove(ATTEMPT_PREFIX + key).apply()
            return null
        }
        return recorded
    }

    /** Persists a request start and drops records that can no longer throttle a request. */
    private fun recordAttempt(key: String, now: Long) {
        lastAttempts[key] = now
        val editor = preferences.edit().putLong(ATTEMPT_PREFIX + key, now)
        val obsolete = ArrayList<String>()
        for ((name, recorded) in lastAttempts) {
            if (name != key && !isUsableAttempt(recorded, now)) obsolete.add(name)
        }
        for (name in obsolete) {
            lastAttempts.remove(name)
            editor.remove(ATTEMPT_PREFIX + name)
        }
        editor.apply()
    }

    /** Persists the measurements of the latest successful reading (never an error state). */
    private fun saveSnapshot(value: WeatherState) {
        preferences.edit()
            .putString(SNAPSHOT_CITY, value.city)
            .putString(SNAPSHOT_COUNTRY, value.country)
            .putString(SNAPSHOT_LATITUDE, value.latitude.toString())
            .putString(SNAPSHOT_LONGITUDE, value.longitude.toString())
            .putString(SNAPSHOT_TEMPERATURE, value.temperature.toString())
            .putString(SNAPSHOT_APPARENT_TEMPERATURE, value.apparentTemperature.toString())
            .putString(SNAPSHOT_PRECIPITATION, value.precipitation.toString())
            .putString(SNAPSHOT_WIND_SPEED, value.windSpeed.toString())
            .putInt(SNAPSHOT_WEATHER_CODE, value.weatherCode)
            .putBoolean(SNAPSHOT_IS_DAY, value.isDay)
            .putLong(SNAPSHOT_UPDATED_AT, value.updatedAt)
            .putString(SNAPSHOT_EXTRAS, encodeExtras(value))
            .apply()
    }

    /**
     * Reads the persisted successful reading. Returns null (and drops the record) when it is
     * incomplete, corrupted, impossible, or dated more than [FUTURE_TOLERANCE_MS] ahead.
     */
    private fun restoreSnapshot(): WeatherState? {
        val latitudeText = preferences.getString(SNAPSHOT_LATITUDE, null) ?: return null
        val longitudeText = preferences.getString(SNAPSHOT_LONGITUDE, null) ?: return null
        val temperatureText = preferences.getString(SNAPSHOT_TEMPERATURE, null) ?: return null
        val apparentText = preferences.getString(SNAPSHOT_APPARENT_TEMPERATURE, null) ?: return null
        val precipitationText = preferences.getString(SNAPSHOT_PRECIPITATION, null) ?: return null
        val windText = preferences.getString(SNAPSHOT_WIND_SPEED, null) ?: return null
        return try {
            val latitude = latitudeText.toDouble()
            val longitude = longitudeText.toDouble()
            val temperature = temperatureText.toDouble()
            val apparent = apparentText.toDouble()
            val precipitation = precipitationText.toDouble()
            val wind = windText.toDouble()
            val code = preferences.getInt(SNAPSHOT_WEATHER_CODE, -1)
            val updatedAt = preferences.getLong(SNAPSHOT_UPDATED_AT, 0L)
            val now = System.currentTimeMillis()
            val valid = latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude.isFinite() && longitude in -180.0..180.0 &&
                temperature.isFinite() && apparent.isFinite() &&
                precipitation.isFinite() && wind.isFinite() &&
                code in 0..99 && updatedAt > 0L && updatedAt - now <= FUTURE_TOLERANCE_MS
            if (!valid) {
                clearSnapshot()
                return null
            }
            val extras = decodeExtras(preferences.getString(SNAPSHOT_EXTRAS, "") ?: "")
            WeatherState(
                preferences.getString(SNAPSHOT_CITY, "").orEmpty(),
                preferences.getString(SNAPSHOT_COUNTRY, "").orEmpty(),
                latitude, longitude, temperature, apparent, precipitation, wind, code,
                preferences.getBoolean(SNAPSHOT_IS_DAY, true), updatedAt, false, false, false,
                "", extras.daily, extras.nowcast, extras.aqi, extras.aqiLabel,
            )
        } catch (ignored: RuntimeException) {
            // A malformed record must never break construction or the first fetch.
            clearSnapshot()
            null
        }
    }

    private fun clearSnapshot() {
        preferences.edit()
            .remove(SNAPSHOT_CITY).remove(SNAPSHOT_COUNTRY)
            .remove(SNAPSHOT_LATITUDE).remove(SNAPSHOT_LONGITUDE)
            .remove(SNAPSHOT_TEMPERATURE).remove(SNAPSHOT_APPARENT_TEMPERATURE)
            .remove(SNAPSHOT_PRECIPITATION).remove(SNAPSHOT_WIND_SPEED)
            .remove(SNAPSHOT_WEATHER_CODE).remove(SNAPSHOT_IS_DAY)
            .remove(SNAPSHOT_UPDATED_AT).remove(SNAPSHOT_EXTRAS)
            .apply()
    }

    /** Idempotent; cancels queued work, disconnects active I/O, and suppresses pending callbacks. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            fetching = false
            weatherRequest?.cancel()
            searchRequest?.cancel()
            main.removeCallbacksAndMessages(null)
            callback = null
            workers.shutdownNow()
        }
    }

    companion object {
        const val MIN_REFRESH_INTERVAL_MS: Long = 15L * 60L * 1000L
        const val MAX_RESPONSE_BYTES: Int = 256 * 1024
        const val TIMEOUT_MS: Int = 8000

        @JvmField
        val DEFAULT_CITY = City("台北", "台湾", 25.0330, 121.5654, "Asia/Taipei")

        private fun empty(value: City): WeatherState =
            WeatherState.empty(value.displayName, value.country, value.latitude, value.longitude)

        private fun relabel(value: WeatherState, city: City): WeatherState = WeatherState(
            city.displayName, city.country, city.latitude, city.longitude,
            value.temperature, value.apparentTemperature, value.precipitation, value.windSpeed,
            value.weatherCode, value.isDay, value.updatedAt, value.stale, value.error, false,
            value.errorMessage, value.daily, value.precipitation15, value.aqi, value.aqiLabel,
        )

        /** 今天/明天 for the first two entries, then the localized weekday of the returned date. */
        private fun dayLabel(date: String, index: Int): String {
            if (index == 0) return "今天"
            if (index == 1) return "明天"
            val weekday = try {
                val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(date)
                if (parsed == null) -1 else {
                    val calendar = java.util.Calendar.getInstance()
                    calendar.time = parsed
                    calendar.get(java.util.Calendar.DAY_OF_WEEK)
                }
            } catch (ignored: Exception) {
                -1
            }
            val names = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            return if (weekday in 1..7) names[weekday - 1] else "第 " + (index + 1) + " 天"
        }

        private fun aqiBand(value: Double): String = when {
            value <= 50 -> "优"
            value <= 100 -> "良"
            value <= 150 -> "轻度污染"
            value <= 200 -> "中度污染"
            value <= 300 -> "重度污染"
            else -> "严重污染"
        }

        /** Serializes the optional sections (forecast/nowcast/AQI) into one preference string. */
        private fun encodeExtras(value: WeatherState): String = try {
            val root = JSONObject()
            val days = JSONArray()
            for (day in value.daily) {
                days.put(
                    JSONObject().put("l", day.label).put("c", day.weatherCode)
                        .put("x", day.max).put("n", day.min),
                )
            }
            root.put("daily", days)
            val nowcast = JSONArray()
            for (millimetres in value.precipitation15) nowcast.put(millimetres)
            root.put("now", nowcast)
            if (value.aqi.isFinite()) root.put("aqi", value.aqi)
            root.put("band", value.aqiLabel)
            root.toString()
        } catch (ignored: JSONException) {
            ""
        }

        private class Extras(
            val daily: List<DailyForecast>,
            val nowcast: List<Double>,
            val aqi: Double,
            val aqiLabel: String,
        )

        /** A malformed record degrades to empty sections; the base reading stays usable. */
        private fun decodeExtras(raw: String): Extras {
            if (raw.isEmpty()) return Extras(emptyList(), emptyList(), Double.NaN, "")
            return try {
                val root = JSONObject(raw)
                val daily = ArrayList<DailyForecast>()
                val days = root.optJSONArray("daily")
                if (days != null) {
                    for (i in 0 until days.length()) {
                        val item = days.optJSONObject(i) ?: continue
                        val day = DailyForecast(
                            item.optString("l", ""), item.optInt("c", -1),
                            item.optDouble("x", Double.NaN), item.optDouble("n", Double.NaN),
                        )
                        if (day.isValid()) daily.add(day)
                    }
                }
                val nowcast = ArrayList<Double>()
                val values = root.optJSONArray("now")
                if (values != null) {
                    for (i in 0 until values.length()) {
                        val millimetres = values.optDouble(i, 0.0)
                        nowcast.add(if (millimetres.isFinite() && millimetres > 0) millimetres else 0.0)
                    }
                }
                val aqi = root.optDouble("aqi", Double.NaN)
                Extras(
                    daily, nowcast, if (aqi.isFinite()) aqi else Double.NaN,
                    root.optString("band", ""),
                )
            } catch (ignored: JSONException) {
                Extras(emptyList(), emptyList(), Double.NaN, "")
            }
        }

        private fun aged(value: WeatherState): WeatherState {
            val age = System.currentTimeMillis() - value.updatedAt
            val stale = value.stale || !value.hasData() || age < 0 ||
                age >= MIN_REFRESH_INTERVAL_MS
            return value.withStatus(stale, value.error, value.loading, value.errorMessage)
        }

        private fun number(obj: JSONObject, key: String): Double {
            val result = obj.getDouble(key)
            if (!result.isFinite()) throw JSONException("缺少有效字段: $key")
            return result
        }

        private fun errorMessage(error: Exception): String = when (error) {
            is SocketTimeoutException -> "天气请求超时，请稍后重试"
            is JSONException -> "天气服务返回的数据无效"
            is IOException -> {
                val message = error.message
                if (message != null && message.startsWith("天气")) message
                else "无法连接天气服务，请检查网络"
            }
            else -> "天气请求失败，请稍后重试"
        }

        private fun coordinateKey(latitude: Double, longitude: Double): String =
            latitude.toString() + "," + longitude.toString()

        private fun isUsableAttempt(recorded: Long, now: Long): Boolean =
            recorded >= 0L && recorded - now <= FUTURE_TOLERANCE_MS &&
                now - recorded < MIN_REFRESH_INTERVAL_MS
    }
}

private const val PREFERENCES_NAME = "skybox_weather"
private const val MAX_QUERY_LENGTH = 120

/** Wall-clock tolerance for records that appear to come from the future (clock rollback). */
private const val FUTURE_TOLERANCE_MS = 60_000L

/** One preference entry per coordinate: `attempt_<latitude>,<longitude>` holds an epoch-ms Long. */
private const val ATTEMPT_PREFIX = "attempt_"

private const val KEY_CITY = "city"
private const val KEY_COUNTRY = "country"
private const val KEY_LATITUDE = "latitude"
private const val KEY_LONGITUDE = "longitude"
private const val KEY_TIMEZONE = "timezone"

private const val SNAPSHOT_CITY = "snapshot_city"
private const val SNAPSHOT_COUNTRY = "snapshot_country"
private const val SNAPSHOT_LATITUDE = "snapshot_latitude"
private const val SNAPSHOT_LONGITUDE = "snapshot_longitude"
private const val SNAPSHOT_TEMPERATURE = "snapshot_temperature"
private const val SNAPSHOT_APPARENT_TEMPERATURE = "snapshot_apparent_temperature"
private const val SNAPSHOT_PRECIPITATION = "snapshot_precipitation"
private const val SNAPSHOT_WIND_SPEED = "snapshot_wind_speed"
private const val SNAPSHOT_WEATHER_CODE = "snapshot_weather_code"
private const val SNAPSHOT_IS_DAY = "snapshot_is_day"
private const val SNAPSHOT_UPDATED_AT = "snapshot_updated_at"
private const val SNAPSHOT_EXTRAS = "snapshot_extras"
