package io.github.yuedaochangmendian.skybox;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Dependency-free Open-Meteo client. Network and JSON work never runs on the UI thread.
 * Call close() from the owner's onDestroy(). All callbacks run on the main thread, with
 * cancellation checked both before posting and at delivery. Search and weather have separate
 * cancellation tokens, so typing a city does not cancel a weather refresh.
 */
public final class WeatherService implements AutoCloseable {
    public static final long MIN_REFRESH_INTERVAL_MS = 15L * 60L * 1000L;
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    public static final int TIMEOUT_MS = 8000;
    public static final City DEFAULT_CITY = new City(
            "台北", "台湾", 25.0330, 121.5654, "Asia/Taipei");

    public interface Callback {
        void onWeather(WeatherState state);
        void onCities(List<City> cities);
        /** citySearch is true for geocoding failures, false for weather failures. */
        void onError(String message, boolean citySearch);
    }

    public static final class City {
        public final String displayName;
        public final String country;
        public final double latitude;
        public final double longitude;
        public final String timezone;

        public City(String displayName, String country, double latitude,
                    double longitude, String timezone) {
            if (displayName == null || displayName.trim().isEmpty()
                    || !finite(latitude) || latitude < -90 || latitude > 90
                    || !finite(longitude) || longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("城市名称或经纬度无效");
            }
            this.displayName = displayName.trim();
            this.country = country == null ? "" : country.trim();
            this.latitude = latitude;
            this.longitude = longitude;
            this.timezone = timezone == null || timezone.trim().isEmpty()
                    ? "auto" : timezone.trim();
        }

        @Override public String toString() {
            return country.isEmpty() ? displayName : displayName + " · " + country;
        }

        private String key() {
            return Double.toString(latitude) + "," + Double.toString(longitude);
        }
    }

    private static final class Request {
        volatile boolean cancelled;
        private HttpURLConnection connection;
        private Future<?> future;

        synchronized void attach(HttpURLConnection value) throws InterruptedIOException {
            check();
            connection = value;
        }

        synchronized void attach(Future<?> value) {
            future = value;
            if (cancelled) value.cancel(true);
        }

        synchronized void detach(HttpURLConnection value) {
            if (connection == value) connection = null;
        }

        synchronized void cancel() {
            cancelled = true;
            if (future != null) future.cancel(true);
            if (connection != null) connection.disconnect();
            connection = null;
        }

        void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("请求已取消");
            }
        }
    }

    private final Object lock = new Object();
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Map<String, Long> lastAttempts = new HashMap<>();
    private final Map<String, WeatherState> snapshots = new HashMap<>();
    private Callback callback;
    private City city;
    private WeatherState state;
    private Request weatherRequest;
    private Request searchRequest;
    private boolean fetching;
    private boolean closed;

    public WeatherService(Context context, Callback callback) {
        if (context == null || callback == null) throw new IllegalArgumentException("context/callback");
        Context application = context.getApplicationContext();
        preferences = (application == null ? context : application)
                .getSharedPreferences("skybox_weather", Context.MODE_PRIVATE);
        this.callback = callback;
        city = readCity();
        state = empty(city);
        saveCity(city); // Persist the default on first use as well as explicit selections.
    }

    public City getCity() {
        synchronized (lock) {
            return city;
        }
    }

    /** Returns a snapshot; it does not initiate a request. */
    public WeatherState getState() {
        synchronized (lock) {
            return aged(state);
        }
    }

    /** Persists a selection and fetches it. Old-city responses can never overwrite the selection. */
    public void setCity(City selected) {
        if (selected == null) throw new IllegalArgumentException("city");
        synchronized (lock) {
            if (closed) return;
            if (weatherRequest != null) weatherRequest.cancel();
            fetching = false;
            city = selected;
            saveCity(selected);
            WeatherState cached = snapshots.get(selected.key());
            state = cached == null ? empty(selected) : relabel(cached, selected);
            fetch(false);
        }
    }

    /**
     * Requests current weather, or delivers the cached snapshot. force skips the successful-data
     * freshness check, but NEVER bypasses the 15-minute per-coordinate request-start limit.
     * Failed attempts also count towards that limit. This object schedules no periodic polling.
     */
    public void fetch(boolean force) {
        synchronized (lock) {
            if (closed || fetching) return;
            if (weatherRequest != null) weatherRequest.cancel();
            final Request request = new Request();
            weatherRequest = request;
            state = aged(state);
            long now = SystemClock.elapsedRealtime();
            Long last = lastAttempts.get(city.key());
            boolean throttled = last != null && now >= last
                    && now - last < MIN_REFRESH_INTERVAL_MS;
            if (throttled || (!force && state.hasData() && !state.stale && !state.error)) {
                final WeatherState cached = state;
                post(request, false, () -> callback.onWeather(cached));
                return;
            }
            final City target = city;
            lastAttempts.put(target.key(), now);
            fetching = true;
            state = state.withStatus(state.stale, false, true, "");
            final WeatherState loading = state;
            post(request, false, () -> callback.onWeather(loading));
            request.attach(workers.submit(() -> loadWeather(request, target)));
        }
    }

    /** Latest query wins. Empty input cancels any pending search and delivers an empty list. */
    public void searchCity(String query) {
        synchronized (lock) {
            if (closed) return;
            if (searchRequest != null) searchRequest.cancel();
            final Request request = new Request();
            searchRequest = request;
            final String text = query == null ? "" : query.trim();
            if (text.isEmpty()) {
                post(request, true, () -> callback.onCities(Collections.emptyList()));
                return;
            }
            if (text.length() > 120) {
                post(request, true, () -> callback.onError("城市名称最多 120 个字符", true));
                return;
            }
            request.attach(workers.submit(() -> {
                try {
                    List<City> found = searchOnce(text, request);
                    // The geocoding index stores full administrative names (台北市, not 台北),
                    // so retry once with the common 市 suffix before reporting an empty result.
                    if (found.isEmpty() && text.length() >= 2 && !text.endsWith("市")) {
                        found = searchOnce(text + "市", request);
                    }
                    List<City> immutable = Collections.unmodifiableList(found);
                    post(request, true, () -> callback.onCities(immutable));
                } catch (IOException | JSONException | RuntimeException e) {
                    String message = errorMessage(e);
                    post(request, true, () -> callback.onError(message, true));
                }
            }));
        }
    }

    private List<City> searchOnce(String text, Request request) throws IOException, JSONException {
        Uri uri = Uri.parse("https://geocoding-api.open-meteo.com/v1/search")
                .buildUpon().appendQueryParameter("name", text)
                .appendQueryParameter("count", "10")
                .appendQueryParameter("language", "zh")
                .appendQueryParameter("format", "json").build();
        JSONObject document = getJson(uri.toString(), request);
        JSONArray results = document.optJSONArray("results");
        List<City> found = new ArrayList<>();
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                request.check();
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                try {
                    String name = item.getString("name").trim();
                    String region = item.optString("admin1", "");
                    if (!region.isEmpty() && !region.equals(name)) name += " · " + region;
                    found.add(new City(name, item.optString("country", ""),
                            number(item, "latitude"), number(item, "longitude"),
                            item.optString("timezone", "auto")));
                } catch (JSONException | IllegalArgumentException ignored) {
                    // One malformed candidate must not hide the other valid cities.
                }
            }
        }
        return found;
    }

    private void loadWeather(Request request, City target) {
        try {
            Uri uri = Uri.parse("https://api.open-meteo.com/v1/forecast").buildUpon()
                    .appendQueryParameter("latitude", Double.toString(target.latitude))
                    .appendQueryParameter("longitude", Double.toString(target.longitude))
                    .appendQueryParameter("current", "temperature_2m,apparent_temperature,"
                            + "precipitation,wind_speed_10m,weather_code,is_day")
                    .appendQueryParameter("temperature_unit", "celsius")
                    .appendQueryParameter("wind_speed_unit", "kmh")
                    .appendQueryParameter("precipitation_unit", "mm")
                    .appendQueryParameter("timezone", target.timezone).build();
            JSONObject document = getJson(uri.toString(), request);
            JSONObject current = document.getJSONObject("current");
            double code = number(current, "weather_code");
            double day = number(current, "is_day");
            double rain = number(current, "precipitation");
            double wind = number(current, "wind_speed_10m");
            if (code != Math.rint(code) || code < 0 || code > 99
                    || (day != 0 && day != 1) || rain < 0 || wind < 0) {
                throw new JSONException("天气字段无效");
            }
            WeatherState result = new WeatherState(target.displayName, target.country,
                    target.latitude, target.longitude, number(current, "temperature_2m"),
                    number(current, "apparent_temperature"), rain, wind, (int) code,
                    day == 1, System.currentTimeMillis(), false, false, false);
            String zone = document.optString("timezone", target.timezone);
            final City resolved = new City(target.displayName, target.country,
                    target.latitude, target.longitude, zone);
            post(request, false, () -> {
                fetching = false;
                city = resolved;
                saveCity(resolved);
                state = result;
                snapshots.put(target.key(), result);
                callback.onWeather(result);
            });
        } catch (IOException | JSONException | RuntimeException e) {
            final String message = errorMessage(e);
            post(request, false, () -> {
                fetching = false;
                state = state.withStatus(true, true, false, message);
                snapshots.put(target.key(), state);
                callback.onWeather(state);
                // onWeather may synchronously close the service or change the city.
                if (active(request, false)) callback.onError(message, false);
            });
        }
    }

    private JSONObject getJson(String address, Request request) throws IOException, JSONException {
        request.check();
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            request.attach(connection);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false); // Never forward to a non-HTTPS redirect.
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            request.check();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("天气服务 HTTP " + status);
            }
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) {
                throw new IOException("天气响应超过 256 KB 限制");
            }
            String encoding = connection.getContentEncoding();
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
                throw new IOException("天气服务返回了不支持的内容编码");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    request.check();
                    if (count > MAX_RESPONSE_BYTES - output.size()) {
                        throw new IOException("天气响应超过 256 KB 限制");
                    }
                    output.write(buffer, 0, count);
                }
                request.check();
                JSONObject result = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                if (result.optBoolean("error", false)) throw new IOException("天气服务暂时无法处理请求");
                return result;
            }
        } finally {
            request.detach(connection);
            connection.disconnect();
        }
    }

    private void post(Request request, boolean search, Runnable delivery) {
        synchronized (lock) {
            if (!active(request, search)) return; // Check BEFORE crossing to the main thread.
            main.post(() -> {
                synchronized (lock) {
                    if (active(request, search)) delivery.run();
                }
            });
        }
    }

    private boolean active(Request request, boolean search) {
        return !closed && !request.cancelled && callback != null
                && request == (search ? searchRequest : weatherRequest);
    }

    private City readCity() {
        try {
            return new City(preferences.getString("city", DEFAULT_CITY.displayName),
                    preferences.getString("country", DEFAULT_CITY.country),
                    Double.parseDouble(preferences.getString("latitude", "25.0330")),
                    Double.parseDouble(preferences.getString("longitude", "121.5654")),
                    preferences.getString("timezone", DEFAULT_CITY.timezone));
        } catch (RuntimeException ignored) {
            return DEFAULT_CITY;
        }
    }

    private void saveCity(City value) {
        preferences.edit().putString("city", value.displayName).putString("country", value.country)
                .putString("latitude", Double.toString(value.latitude))
                .putString("longitude", Double.toString(value.longitude))
                .putString("timezone", value.timezone).apply();
    }

    private static WeatherState empty(City value) {
        return WeatherState.empty(value.displayName, value.country, value.latitude, value.longitude);
    }

    private static WeatherState relabel(WeatherState value, City city) {
        return new WeatherState(city.displayName, city.country, city.latitude, city.longitude,
                value.temperature, value.apparentTemperature, value.precipitation, value.windSpeed,
                value.weatherCode, value.isDay, value.updatedAt, value.stale, value.error,
                false, value.errorMessage);
    }

    private static WeatherState aged(WeatherState value) {
        long age = System.currentTimeMillis() - value.updatedAt;
        boolean stale = value.stale || !value.hasData() || age < 0 || age >= MIN_REFRESH_INTERVAL_MS;
        return value.withStatus(stale, value.error, value.loading, value.errorMessage);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static double number(JSONObject object, String key) throws JSONException {
        double result = object.getDouble(key);
        if (!finite(result)) throw new JSONException("缺少有效字段: " + key);
        return result;
    }

    private static String errorMessage(Exception error) {
        if (error instanceof SocketTimeoutException) return "天气请求超时，请稍后重试";
        if (error instanceof JSONException) return "天气服务返回的数据无效";
        if (error instanceof IOException) {
            String message = error.getMessage();
            if (message != null && message.startsWith("天气")) return message;
            return "无法连接天气服务，请检查网络";
        }
        return "天气请求失败，请稍后重试";
    }

    /** Idempotent; cancels queued work, disconnects active I/O, and suppresses pending callbacks. */
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            fetching = false;
            if (weatherRequest != null) weatherRequest.cancel();
            if (searchRequest != null) searchRequest.cancel();
            main.removeCallbacksAndMessages(null);
            callback = null;
            workers.shutdownNow();
        }
    }
}
