package io.github.yuedaochangmendian.skybox

import android.content.Context
import android.util.AtomicFile
import io.nekohasekai.libbox.Libbox
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Private, validated sing-box profiles. No configuration is written to shared storage. */
object ProfileStore {
    const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
    private const val PREFS = "skybox_profiles"
    private const val ACTIVE = "active_id"

    class Profile(val id: String, val name: String, val modified: Long)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun directory(context: Context): File {
        val dir = File(context.filesDir, "profiles")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建私有配置目录")
        }
        return dir
    }

    private fun validId(id: String?): Boolean =
        id != null && id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

    private fun file(context: Context, id: String): AtomicFile {
        if (!validId(id)) throw IOException("配置标识无效")
        return AtomicFile(File(directory(context), "$id.json"))
    }

    @JvmStatic
    @Synchronized
    fun list(context: Context): List<Profile> {
        val files = directory(context).listFiles() ?: throw IOException("无法读取配置目录")
        val result = ArrayList<Profile>()
        val storage = prefs(context)
        for (entry in files) {
            val name = entry.name
            // AtomicFile may leave a backup after process death; openRead recovers it.
            val suffix = if (name.endsWith(".json.bak")) ".json.bak" else ".json"
            if (!name.endsWith(suffix)) continue
            val id = name.substring(0, name.length - suffix.length)
            if (!validId(id)) continue
            if (result.any { it.id == id }) continue
            result.add(Profile(id, storage.getString("name_$id", "未命名配置") ?: "未命名配置", entry.lastModified()))
        }
        result.sortWith(compareByDescending { it.modified })
        return result
    }

    @JvmStatic
    @Synchronized
    fun activeId(context: Context): String = prefs(context).getString(ACTIVE, "") ?: ""

    /** Empty means no readable selected profile; the service must refuse to start. */
    @JvmStatic
    @Synchronized
    fun activeConfig(context: Context): String {
        val id = activeId(context)
        if (!validId(id)) return ""
        return try {
            read(context, id)
        } catch (error: IOException) {
            ""
        }
    }

    @JvmStatic
    @Synchronized
    fun activeName(context: Context): String {
        val id = activeId(context)
        if (!validId(id)) return "未选择配置"
        return prefs(context).getString("name_$id", "未命名配置") ?: "未命名配置"
    }

    @JvmStatic
    @Synchronized
    fun read(context: Context, id: String): String {
        file(context, id).openRead().use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > MAX_CONFIG_BYTES) throw IOException("配置超过 2 MiB 限制")
                    output.write(buffer, 0, count)
                }
                return String(output.toByteArray(), StandardCharsets.UTF_8)
            }
        }
    }

    /** Run on a worker thread. Successful validation is required before every write. */
    @JvmStatic
    fun validate(context: Context, config: String?) {
        if (config == null || config.trim().isEmpty()) throw IOException("配置内容为空")
        if (config.toByteArray(StandardCharsets.UTF_8).size > MAX_CONFIG_BYTES) {
            throw IOException("配置超过 2 MiB 限制")
        }
        val document = JSONObject(config) // Require a JSON object, then let libbox check its schema.
        val route = document.optJSONObject("route")
        if (route == null || !route.optBoolean("auto_detect_interface", false)) {
            throw IOException("Android VPN 配置需要 route.auto_detect_interface=true，以避免内核连接重新进入 VPN")
        }
        CoreRuntime.ensureInit(context.applicationContext)
        Libbox.checkConfig(config)
    }

    /** Null id creates a new profile. Saving preserves the selected profile unless none exists. */
    @JvmStatic
    @Synchronized
    fun save(context: Context, id: String?, name: String?, config: String): String {
        val trimmed = name?.trim() ?: ""
        if (trimmed.isEmpty()) throw IOException("请填写配置名称")
        if (trimmed.length > 80) throw IOException("配置名称最多 80 个字符")
        validate(context, config)
        var targetId = id
        val create = targetId == null
        if (create) targetId = UUID.randomUUID().toString()
        val target = file(context, targetId!!)
        if (!create && !target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) {
            throw IOException("配置已被删除，请重新新建")
        }
        try {
            val stream = target.startWrite()
            try {
                stream.write(config.toByteArray(StandardCharsets.UTF_8))
                target.finishWrite(stream)
            } catch (error: IOException) {
                target.failWrite(stream)
                throw error
            }
        } catch (error: IOException) {
            throw error
        }
        val edit = prefs(context).edit().putString("name_$targetId", trimmed)
        if (activeId(context).isEmpty()) edit.putString(ACTIVE, targetId)
        if (!edit.commit()) throw IOException("配置已写入，但名称或选用状态保存失败，请重新选择")
        return targetId
    }

    @JvmStatic
    @Synchronized
    fun select(context: Context, id: String) {
        read(context, id) // Recover AtomicFile and reject absent/unreadable profiles.
        if (!prefs(context).edit().putString(ACTIVE, id).commit()) {
            throw IOException("无法保存选用状态")
        }
    }

    @JvmStatic
    @Synchronized
    fun delete(context: Context, id: String) {
        val target = file(context, id)
        if (id == activeId(context)) {
            if (!prefs(context).edit().remove(ACTIVE).commit()) throw IOException("无法清除选用状态")
        }
        target.delete()
        if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) {
            throw IOException("删除配置失败")
        }
        if (!prefs(context).edit().remove("name_$id").commit()) {
            throw IOException("配置已删除，名称记录清理失败")
        }
    }
}
