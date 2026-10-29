package io.github.yuedaochangmendian.skybox

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import io.nekohasekai.libbox.Libbox
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Flutter host. The UI lives entirely in Dart; this activity only exposes the pieces that cannot
 * exist there: the sing-box kernel (a gomobile archive), the VpnService, the profile files and the
 * system VPN authorization flow.
 */
class MainActivity : FlutterActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    /** Result handed over while the system VPN authorization sheet is on screen. */
    private var permissionResult: MethodChannel.Result? = null

    /** Result handed over while the document picker is on screen. */
    private var pickResult: MethodChannel.Result? = null

    @Volatile
    private var coreVersion = "正在读取…"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = applicationContext
        worker.execute {
            val version = try {
                CoreRuntime.ensureInit(context)
                Libbox.version()
            } catch (e: Throwable) {
                "不可用：" + (e.message ?: e.javaClass.simpleName)
            }
            coreVersion = version
        }
        flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
            MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
                handle(call.method, call.arguments, result)
            }
        }
    }

    override fun onDestroy() {
        permissionResult?.error("closed", "界面已关闭", null)
        permissionResult = null
        pickResult?.error("closed", "界面已关闭", null)
        pickResult = null
        worker.shutdown()
        super.onDestroy()
    }

    private fun handle(method: String, arguments: Any?, result: MethodChannel.Result) {
        when (method) {
            "status" -> result.success(statusMap())
            "logs" -> result.success(VpnController.logs())
            "speedHistory" -> result.success(VpnController.speedHistory().toList())
            "uploadHistory" -> result.success(VpnController.uploadHistory().toList())
            "sampleIntervalMs" -> result.success(VpnController.speedSampleIntervalMs())
            "sampleCapacity" -> result.success(VpnController.speedSampleCapacity())
            "coreVersion" -> result.success(coreVersion)
            "groups" -> result.success(groupList())
            "profiles" -> result.success(profileList())
            "activeId" -> result.success(ProfileStore.activeId(this))
            "activeName" -> result.success(ProfileStore.activeName(this))
            "activeConfig" -> result.success(ProfileStore.activeConfig(this))
            "readProfile" -> {
                val id = arguments as? String ?: return result.error("bad_args", "id", null)
                runCatching(result) { ProfileStore.read(this, id) }
            }
            "saveProfile" -> {
                val args = arguments as? Map<*, *> ?: return result.error("bad_args", "map", null)
                runCatching(result) {
                    ProfileStore.save(
                        this,
                        args["id"] as? String,
                        args["name"] as? String,
                        args["config"] as? String ?: "",
                    )
                }
            }
            "deleteProfile" -> {
                val id = arguments as? String ?: return result.error("bad_args", "id", null)
                runCatching(result) {
                    ProfileStore.delete(this, id)
                    null
                }
            }
            "selectProfile" -> {
                val id = arguments as? String ?: return result.error("bad_args", "id", null)
                runCatching(result) {
                    ProfileStore.select(this, id)
                    null
                }
            }
            "validate" -> {
                val config = arguments as? String ?: return result.error("bad_args", "config", null)
                runCatching(result) {
                    ProfileStore.validate(this, config)
                    if (!Libbox.hasTunInbound(config)) {
                        throw IllegalArgumentException("Android VPN 配置必须包含 tun 入站")
                    }
                    null
                }
            }
            "selectOutbound" -> {
                val args = arguments as? Map<*, *> ?: return result.error("bad_args", "map", null)
                val group = args["group"] as? String ?: return result.error("bad_args", "group", null)
                val tag = args["outbound"] as? String ?: return result.error("bad_args", "outbound", null)
                VpnController.selectOutbound(group, tag) { message -> result.error("core", message, null) }
            }
            "urlTest" -> {
                val tag = arguments as? String ?: return result.error("bad_args", "tag", null)
                VpnController.urlTest(tag)
                result.success(null)
            }
            "vpnPrepared" -> result.success(VpnService.prepare(this) == null)
            "start" -> start(result)
            "stop" -> {
                runCatching(result) {
                    startService(Intent(this, VpnController::class.java).setAction(VpnController.ACTION_STOP))
                    null
                }
            }
            "pickConfig" -> pickConfig(result)
            "openVpnSettings" -> {
                runCatching(result) {
                    startActivity(Intent("android.settings.VPN_SETTINGS"))
                    null
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun statusMap(): Map<String, Any> = mapOf(
        "running" to VpnController.running,
        "state" to VpnController.state,
        "error" to VpnController.error,
        "downlink" to VpnController.downlink,
        "uplink" to VpnController.uplink,
        "downlinkTotal" to VpnController.downlinkTotal,
        "uplinkTotal" to VpnController.uplinkTotal,
        "groupsRevision" to VpnGroups.revision,
    )

    private fun groupList(): List<Map<String, Any?>> = VpnGroups.groups.map { group ->
        mapOf(
            "tag" to group.tag,
            "type" to group.type,
            "selectable" to group.selectable,
            "selected" to group.selected,
            "items" to group.items.map { item ->
                mapOf("tag" to item.tag, "type" to item.type, "delay" to item.delay)
            },
        )
    }

    private fun profileList(): List<Map<String, Any?>> = ProfileStore.list(this).map { profile ->
        mapOf("id" to profile.id, "name" to profile.name, "modified" to profile.modified)
    }

    private fun runCatching(result: MethodChannel.Result, body: () -> Any?) {
        try {
            result.success(body())
        } catch (e: Throwable) {
            result.error("core", e.message ?: e.javaClass.simpleName, null)
        }
    }

    /**
     * Mirrors the previous native flow: validate the checked configuration, ask the system for VPN
     * authorization when it is missing, then hand the start request to the service.
     */
    private fun start(result: MethodChannel.Result) {
        worker.execute {
            try {
                val config = ProfileStore.activeConfig(this)
                if (config.isEmpty()) {
                    postResult(result) { it.error("no_config", "请先导入并选择 sing-box JSON 配置", null) }
                    return@execute
                }
                ProfileStore.validate(this, config)
                if (!Libbox.hasTunInbound(config)) {
                    postResult(result) { it.error("no_tun", "Android VPN 配置必须包含 tun 入站", null) }
                    return@execute
                }
                val authorization = VpnService.prepare(this)
                if (authorization != null) {
                    runOnUiThread {
                        permissionResult?.error("superseded", "被新的请求取代", null)
                        permissionResult = result
                        startActivityForResult(authorization, VPN_PERMISSION)
                    }
                    return@execute
                }
                launchService()
                postResult(result) { it.success(null) }
            } catch (e: Throwable) {
                postResult(result) { it.error("core", e.message ?: e.javaClass.simpleName, null) }
            }
        }
    }

    private fun postResult(result: MethodChannel.Result, body: (MethodChannel.Result) -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) body(result)
        }
    }

    private fun launchService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 112)
        }
        val intent = Intent(this, VpnController::class.java).setAction(VpnController.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun pickConfig(result: MethodChannel.Result) {
        pickResult?.error("superseded", "被新的请求取代", null)
        pickResult = result
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
            pickResult = null
            result.error("core", "无法打开文件选择器", null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION) {
            val pending = permissionResult
            permissionResult = null
            if (pending == null) return
            if (resultCode == Activity.RESULT_OK) {
                try {
                    launchService()
                    pending.success(null)
                } catch (e: Exception) {
                    pending.error("core", e.message, null)
                }
            } else {
                pending.error("denied", "未授予 VPN 权限，连接未启动", null)
            }
            return
        }
        if (requestCode == IMPORT_JSON) {
            val pending = pickResult
            pickResult = null
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (pending == null) return
            if (uri == null) {
                pending.success(null)
                return
            }
            worker.execute { readPicked(uri, pending) }
        }
    }

    private fun readPicked(uri: Uri, result: MethodChannel.Result) {
        try {
            var name = "导入配置"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.let { name = it }
            }
            if (name.trim().isEmpty()) name = "导入配置"
            if (name.endsWith(".json")) name = name.substring(0, name.length - 5)
            if (name.length > 80) name = name.substring(0, 80)
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
            if (content.startsWith("﻿")) content = content.substring(1)
            postResult(result) { it.success(mapOf("name" to name, "config" to content)) }
        } catch (e: Throwable) {
            postResult(result) { it.error("core", e.message ?: "导入失败", null) }
        }
    }

    companion object {
        private const val CHANNEL = "skybox/core"
        private const val IMPORT_JSON = 110
        private const val VPN_PERMISSION = 111
    }
}
