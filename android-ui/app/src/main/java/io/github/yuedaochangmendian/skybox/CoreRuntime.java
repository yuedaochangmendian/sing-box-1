package io.github.yuedaochangmendian.skybox;

import android.content.Context;
import java.io.File;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.SetupOptions;

public final class CoreRuntime {
    private static boolean initialized;
    private CoreRuntime() {}

    public static synchronized void ensureInit(Context context) throws Exception {
        if (initialized) return;
        File base = new File(context.getFilesDir(), "core");
        File work = new File(base, "work");
        File temp = new File(context.getCacheDir(), "core");
        if ((!work.isDirectory() && !work.mkdirs()) || (!temp.isDirectory() && !temp.mkdirs())) {
            throw new java.io.IOException("无法创建内核目录");
        }
        go.Seq.setContext(context.getApplicationContext());
        SetupOptions options = new SetupOptions();
        options.setBasePath(base.getAbsolutePath());
        options.setWorkingPath(work.getAbsolutePath());
        options.setTempPath(temp.getAbsolutePath());
        options.setFixAndroidStack(true);
        options.setLogMaxLines(300);
        options.setCrashReportSource("skybox");
        options.setAppVersion("1");
        options.setAppMarketingVersion("1.0.0");
        Libbox.setup(options);
        initialized = true;
    }
}
