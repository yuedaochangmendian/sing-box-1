package io.github.yuedaochangmendian.skybox

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.io.IOException

/** One-time libbox initialization; must run before any Libbox API call. */
object CoreRuntime {
    @Volatile
    private var initialized = false

    @JvmStatic
    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        val base = File(context.filesDir, "core")
        val work = File(base, "work")
        val temp = File(context.cacheDir, "core")
        if ((!work.isDirectory && !work.mkdirs()) || (!temp.isDirectory && !temp.mkdirs())) {
            throw IOException("无法创建内核目录")
        }
        go.Seq.setContext(context.applicationContext)
        val options = SetupOptions()
        options.basePath = base.absolutePath
        options.workingPath = work.absolutePath
        options.tempPath = temp.absolutePath
        options.fixAndroidStack = true
        options.logMaxLines = 300
        options.crashReportSource = "skybox"
        options.appVersion = "1"
        options.appMarketingVersion = "1.0.0"
        Libbox.setup(options)
        initialized = true
    }
}
