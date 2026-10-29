package io.github.yuedaochangmendian.skybox

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Exit-node geolocation. The request follows the current app route, so with the VPN running the
 * observed IP is the tunnel exit and the location is the node's region; without the VPN it is the
 * local network. Both endpoints are keyless HTTPS; the second one only runs when the first fails.
 */
object NodeLocation {
    class Location(
        val ip: String,
        val country: String,
        val city: String,
        val latitude: Double,
        val longitude: Double,
        val timezone: String,
    ) {
        val isValid: Boolean =
            latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0

        /** "城市 · 国家" like the weather city line; falls back to whichever part is known. */
        fun displayName(): String = when {
            city.isNotEmpty() && country.isNotEmpty() -> "$city · $country"
            city.isNotEmpty() -> city
            else -> country
        }
    }

    @Volatile
    var probing = false
        private set

    @Volatile
    var last: Location? = null
        private set

    @Volatile
    var lastError = ""
        private set

    /** Epoch ms of the last finished attempt; used to throttle automatic re-probes. */
    @Volatile
    var lastAttemptAt = 0L
        private set

    /**
     * True when the tunnel came up or went down while the request was in flight, so the answer
     * belongs to a different route than the one the app is on now. The reading is discarded and
     * [lastAttemptAt] is cleared so the caller may retry immediately.
     */
    @Volatile
    var routeChanged = false
        private set

    @JvmStatic
    @Synchronized
    fun probe(complete: (() -> Unit)? = null) {
        if (probing) return
        probing = true
        routeChanged = false
        // Capture the route before the request: deciding afterwards would label a direct reading
        // as a node reading whenever the VPN connects in the meantime.
        val tunnelAtRequest = VpnController.running
        Thread({
            var result: Location? = null
            var failure = ""
            for (endpoint in ENDPOINTS) {
                try {
                    result = fetch(endpoint.first, tunnelAtRequest, endpoint.second)
                    break
                } catch (error: Exception) {
                    failure = error.message ?: error.javaClass.simpleName
                    if (routeChanged || Thread.currentThread().isInterrupted) break
                }
            }
            lastAttemptAt = System.currentTimeMillis()
            when {
                routeChanged -> {
                    // Keep the previous reading untouched and let the caller re-probe right away.
                    lastError = "网络路径已变化"
                    lastAttemptAt = 0L
                }
                result != null -> {
                    last = result
                    lastError = ""
                }
                failure.isNotEmpty() -> lastError = failure
            }
            probing = false
            complete?.invoke()
        }, "skybox-node-location").start()
    }

    /** True when the current reading came from a tunnel exit (VPN was running at probe time). */
    @Volatile
    var fromNode = false
        private set

    private fun fetch(address: String, tunnelAtRequest: Boolean, parse: (JSONObject) -> Location): Location {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw java.io.IOException("节点地区 HTTP $status")
            connection.inputStream.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (output.size() + count > MAX_RESPONSE_BYTES) {
                            throw java.io.IOException("节点地区响应超限")
                        }
                        output.write(buffer, 0, count)
                    }
                    val document = JSONObject(String(output.toByteArray(), StandardCharsets.UTF_8))
                    if (document.optBoolean("error", false)) throw java.io.IOException("节点地区查询被拒绝")
                    val location = parse(document)
                    if (!location.isValid) throw java.io.IOException("节点地区数据无效")
                    if (VpnController.running != tunnelAtRequest) {
                        routeChanged = true
                        throw java.io.IOException("网络路径已变化")
                    }
                    fromNode = tunnelAtRequest
                    return location
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 8000
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    /** ipwho.is first (exact fields), ipapi.co as the fallback shape. */
    private val ENDPOINTS = arrayOf(
        Pair("https://ipwho.is/") { document: JSONObject ->
            if (!document.optBoolean("success", true)) throw java.io.IOException("ipwho.is 查询失败")
            Location(
                document.optString("ip", ""),
                document.optString("country", ""),
                document.optString("city", ""),
                document.optDouble("latitude", Double.NaN),
                document.optDouble("longitude", Double.NaN),
                document.optJSONObject("timezone")?.optString("id", "") ?: "",
            )
        },
        Pair("https://ipapi.co/json/") { document: JSONObject ->
            Location(
                document.optString("ip", ""),
                document.optString("country_name", ""),
                document.optString("city", ""),
                document.optDouble("latitude", Double.NaN),
                document.optDouble("longitude", Double.NaN),
                document.optString("timezone", ""),
            )
        },
    )
}
