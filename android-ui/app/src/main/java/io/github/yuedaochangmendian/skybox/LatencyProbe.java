package io.github.yuedaochangmendian.skybox;

import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.net.URL;

public final class LatencyProbe {
    public static volatile long milliseconds = -1;
    public static volatile long fastest = -1, slowest = -1;
    public static volatile boolean testing;
    public static volatile String failure = "";
    private LatencyProbe() {}

    public static synchronized void test(Runnable complete) {
        if (testing || !VpnController.running) return;
        testing = true;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://www.gstatic.com/generate_204").openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                long start = SystemClock.elapsedRealtime();
                int code = connection.getResponseCode();
                if (code != 204) throw new java.io.IOException("HTTP " + code);
                if (!VpnController.running) throw new java.io.IOException("连接已断开");
                milliseconds = SystemClock.elapsedRealtime() - start;
                fastest = fastest < 0 ? milliseconds : Math.min(fastest, milliseconds);
                slowest = Math.max(slowest, milliseconds);
                failure = "";
            } catch (Exception e) {
                milliseconds = -1;
                failure = e.getMessage() == null ? "测试失败" : e.getMessage();
            } finally {
                if (connection != null) connection.disconnect();
                testing = false;
                if (complete != null) complete.run();
            }
        }, "skybox-latency").start();
    }
}
