package io.github.yuedaochangmendian.skybox

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

/** Measures HTTPS round-trip through the current app route; not a proxy-node TCP handshake. */
object LatencyProbe {
    @Volatile
    var milliseconds = -1L

    @Volatile
    var fastest = -1L

    @Volatile
    var slowest = -1L

    @Volatile
    var testing = false

    @Volatile
    var failure = ""

    @JvmStatic
    @Synchronized
    fun test(complete: (() -> Unit)? = null) {
        if (testing || !VpnController.running) return
        testing = true
        Thread({
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                val start = SystemClock.elapsedRealtime()
                val code = connection.responseCode
                if (code != 204) throw java.io.IOException("HTTP $code")
                if (!VpnController.running) throw java.io.IOException("连接已断开")
                milliseconds = SystemClock.elapsedRealtime() - start
                fastest = if (fastest < 0) milliseconds else minOf(fastest, milliseconds)
                slowest = maxOf(slowest, milliseconds)
                failure = ""
            } catch (error: Exception) {
                milliseconds = -1
                failure = error.message ?: "测试失败"
            } finally {
                connection?.disconnect()
                testing = false
                complete?.invoke()
            }
        }, "skybox-latency").start()
    }
}
