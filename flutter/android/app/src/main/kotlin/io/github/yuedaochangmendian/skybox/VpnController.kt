package io.github.yuedaochangmendian.skybox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class VpnController : VpnService(), PlatformInterface, CommandServerHandler, CommandClientHandler {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var connectivity: ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var listener: InterfaceUpdateListener? = null
    private var server: CommandServer? = null
    private var client: CommandClient? = null
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var latestStartId = 0

    @Volatile
    private var destroyed = false

    @Volatile
    private var underlying: Network? = null

    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        owner = this
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent != null && ACTION_STOP == intent.action) {
            state = "正在断开"
            submit {
                stopCore()
                finishRequest(startId)
            }
        } else {
            foreground("正在连接")
            submit { if (!destroyed && latestStartId == startId && owner === this) startCore(startId) }
        }
        return Service.START_NOT_STICKY
    }

    private fun finishRequest(id: Int) {
        main.post {
            if (owner === this && !destroyed && latestStartId == id) {
                stopForeground(true)
                stopSelfResult(id)
            }
        }
    }

    private fun foreground(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("vpn", "VPN 连接", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VpnController::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "vpn")
        else android.app.Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("SkyBox")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开", stop)
        startForeground(1, builder.build())
    }

    private fun startCore(requestId: Int) {
        if (server != null || stopping || destroyed || owner !== this) return
        state = "连接中"
        error = ""
        try {
            val config = ProfileStore.activeConfig(this)
            if (config.isEmpty()) throw IllegalStateException("请先导入并选择 sing-box JSON 配置")
            ProfileStore.validate(this, config)
            if (!Libbox.hasTunInbound(config)) throw IllegalArgumentException("Android VPN 配置必须包含 tun 入站")
            synchronized(LOCK) {
                HISTORY.clear()
                sampleAt = SystemClock.elapsedRealtime()
                sampleTotal = 0
            }
            VpnGroups.clear()
            LatencyProbe.milliseconds = -1
            LatencyProbe.fastest = -1
            LatencyProbe.slowest = -1
            LatencyProbe.failure = ""
            downlink = 0
            uplink = 0
            downlinkTotal = 0
            uplinkTotal = 0
            val createdServer = Libbox.newCommandServer(this, this)
            server = createdServer
            createdServer.start()
            createdServer.startOrReloadService(config, OverrideOptions())
            if (tun == null) throw IllegalStateException("配置未建立 VPN 接口")
            if (destroyed || latestStartId != requestId || owner !== this) {
                stopCore()
                return
            }
            running = true
            state = "已连接"
            val options = CommandClientOptions()
            options.addCommand(Libbox.CommandStatus)
            options.addCommand(Libbox.CommandLog)
            options.addCommand(Libbox.CommandGroup)
            // Per-outbound delay updates: WriteOutbounds only fires when this command is subscribed.
            options.addCommand(Libbox.CommandOutbounds)
            options.setStatusInterval(1000000000L)
            val createdClient = Libbox.newCommandClient(this, options)
            client = createdClient
            currentClient = createdClient
            createdClient.connect()
            foreground(ProfileStore.activeName(this))
            log("VPN 已连接")
            // First reading for the home latency pill; later refreshes come from the UI ticker.
            LatencyProbe.test()
        } catch (e: Exception) {
            connectFailed(e, requestId)
        } catch (e: LinkageError) {
            connectFailed(e, requestId)
        }
    }

    private fun connectFailed(cause: Throwable, requestId: Int) {
        error = cause.message ?: cause.toString()
        log("连接失败：$error")
        stopCore()
        if (owner === this && !destroyed) state = "连接失败"
        finishRequest(requestId)
    }

    private fun stopCore() {
        stopping = true
        try {
            val activeClient = client
            if (activeClient != null) {
                try {
                    activeClient.disconnect()
                } catch (ignored: Exception) {
                }
                client = null
                currentClient = null
            }
            val currentServer = server
            if (currentServer != null) {
                try {
                    currentServer.closeService()
                } catch (e: Exception) {
                    log("停止内核：" + e.message)
                } finally {
                    currentServer.close()
                    server = null
                }
            }
            val currentTun = tun
            if (currentTun != null) {
                try {
                    currentTun.close()
                } catch (ignored: Exception) {
                }
                tun = null
            }
            VpnGroups.clear()
            val currentCallback = callback
            if (currentCallback != null) {
                try {
                    connectivity.unregisterNetworkCallback(currentCallback)
                } catch (ignored: Exception) {
                }
                callback = null
            }
            listener = null
            underlying = null
            if (owner === this) {
                running = false
                downlink = 0
                uplink = 0
                if (!destroyed) {
                    state = "已断开"
                    log("VPN 已断开")
                }
            }
        } finally {
            stopping = false
        }
    }

    override fun onRevoke() {
        submit {
            stopCore()
            finishRequest(latestStartId)
        }
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        state = "已断开"
        if (owner === this) owner = null
        submit { stopCore() }
        // The worker is process-wide: shutting it down here would kill every later service instance.
        super.onDestroy()
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) throw IOException("无法保护内核套接字")
    }

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(this) != null) throw IllegalStateException("VPN 授权已失效")
        val builder = Builder().setSession("SkyBox").setMtu(options.getMTU())
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        addAddresses(builder, options.getInet4Address())
        addAddresses(builder, options.getInet6Address())
        if (!options.getAutoRoute()) throw IllegalArgumentException("Android VPN 需要 tun.auto_route=true")
        val dnsMode = options.getDNSMode()
        if (dnsMode != null && Libbox.DNSModeDisabled != dnsMode.getValue()) {
            val dns = options.getDNSServerAddress()
            while (dns.hasNext()) builder.addDnsServer(dns.next())
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addRoutes(builder, options.getInet4RouteAddress(), if (options.getInet4Address().hasNext()) "0.0.0.0" else null)
            addRoutes(builder, options.getInet6RouteAddress(), if (options.getInet6Address().hasNext()) "::" else null)
            excludeRoutes(builder, options.getInet4RouteExcludeAddress())
            excludeRoutes(builder, options.getInet6RouteExcludeAddress())
        } else {
            addRoutes(builder, options.getInet4RouteRange(), null)
            addRoutes(builder, options.getInet6RouteRange(), null)
        }
        val include = options.getIncludePackage()
        var included = 0
        while (include.hasNext()) {
            if (addPackage(builder, include.next(), true)) included++
        }
        val exclude = options.getExcludePackage()
        while (exclude.hasNext()) addPackage(builder, exclude.next(), false)
        if (included > 0) log("分应用代理：$included 个应用")
        if (options.isHTTPProxyEnabled() && Build.VERSION.SDK_INT >= 29) {
            val bypass = ArrayList<String>()
            val domains = options.getHTTPProxyBypassDomain()
            while (domains.hasNext()) bypass.add(domains.next())
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    options.getHTTPProxyServer(),
                    options.getHTTPProxyServerPort(),
                    bypass
                )
            )
        }
        val result = builder.establish() ?: throw IllegalStateException("VPN 接口建立失败")
        tun = result
        return result.fd
    }

    private fun addPackage(b: VpnService.Builder, packageName: String, include: Boolean): Boolean {
        try {
            if (include) b.addAllowedApplication(packageName) else b.addDisallowedApplication(packageName)
            return true
        } catch (e: PackageManager.NameNotFoundException) {
            log("跳过未安装的应用：$packageName")
            return false
        }
    }

    private fun addAddresses(b: VpnService.Builder, iterator: RoutePrefixIterator) {
        while (iterator.hasNext()) {
            val prefix = iterator.next()
            b.addAddress(prefix.address(), prefix.prefix())
        }
    }

    private fun addRoutes(b: VpnService.Builder, iterator: RoutePrefixIterator, fallback: String?) {
        var any = false
        while (iterator.hasNext()) {
            val prefix = iterator.next()
            b.addRoute(prefix.address(), prefix.prefix())
            any = true
        }
        if (!any && fallback != null) b.addRoute(fallback, 0)
    }

    private fun excludeRoutes(b: VpnService.Builder, iterator: RoutePrefixIterator) {
        if (Build.VERSION.SDK_INT >= 33) {
            while (iterator.hasNext()) {
                val prefix = iterator.next()
                b.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix()))
            }
        }
    }

    override fun startDefaultInterfaceMonitor(value: InterfaceUpdateListener) {
        listener = value
        val looper = Handler(Looper.getMainLooper())
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                underlying = network
                updateNetwork()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                underlying = network
                updateNetwork()
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                underlying = network
                updateNetwork()
            }

            override fun onLost(network: Network) {
                if (network == underlying) {
                    underlying = null
                    updateNetwork()
                }
            }
        }
        callback = registered
        // A VPN itself also satisfies INTERNET, so ask for the network a new request would use
        // (REQUEST semantics) instead of scanning every available network.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 31) connectivity.registerBestMatchingNetworkCallback(request, registered, looper)
            else if (Build.VERSION.SDK_INT >= 28) connectivity.requestNetwork(request, registered, looper)
            else if (Build.VERSION.SDK_INT >= 26) connectivity.registerDefaultNetworkCallback(registered, looper)
            else connectivity.registerDefaultNetworkCallback(registered)
        } catch (e: RuntimeException) {
            log("默认网络监听失败：" + e.message)
            try {
                if (Build.VERSION.SDK_INT >= 26) connectivity.registerDefaultNetworkCallback(registered, looper)
                else connectivity.registerDefaultNetworkCallback(registered)
            } catch (ignored: RuntimeException) {
            }
        }
        val active = connectivity.getActiveNetwork()
        if (active != null) {
            underlying = active
            updateNetwork()
        }
    }

    private fun updateNetwork() {
        val target = listener ?: return
        val network = underlying
        try {
            val properties = if (network == null) null else connectivity.getLinkProperties(network)
            val name = properties?.interfaceName
            val osInterface = if (name == null) null else java.net.NetworkInterface.getByName(name)
            val caps = if (network == null) null else connectivity.getNetworkCapabilities(network)
            setUnderlyingNetworks(if (network == null) null else arrayOf(network))
            target.updateDefaultInterface(
                if (osInterface == null) "" else osInterface.name,
                if (osInterface == null) -1 else osInterface.index,
                caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                false
            )
        } catch (e: Exception) {
            log("网络接口更新失败")
        }
    }

    override fun closeDefaultInterfaceMonitor(value: InterfaceUpdateListener) {
        listener = null
        val currentCallback = callback
        if (currentCallback != null) {
            connectivity.unregisterNetworkCallback(currentCallback)
            callback = null
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = ArrayList<LibboxNetworkInterface>()
        for (network in connectivity.getAllNetworks()) {
            val properties = connectivity.getLinkProperties(network)
            val caps = connectivity.getNetworkCapabilities(network)
            if (properties == null || caps == null || properties.interfaceName == null) continue
            val osInterface = java.net.NetworkInterface.getByName(properties.interfaceName) ?: continue
            val item = LibboxNetworkInterface()
            item.setName(osInterface.name)
            item.setIndex(osInterface.index)
            item.setMTU(osInterface.mtu)
            val addresses = ArrayList<String>()
            for (address in osInterface.interfaceAddresses) {
                val host = InetAddress.getByAddress(address.address.address).hostAddress
                addresses.add("$host/${address.networkPrefixLength}")
            }
            item.setAddresses(strings(addresses))
            val dns = ArrayList<String>()
            for (address in properties.dnsServers) dns.add(address.hostAddress ?: "")
            item.setDNSServer(strings(dns))
            item.setDNSSearchDomain(strings(emptyList()))
            item.setGateway(strings(emptyList()))
            var flags = 0
            if (osInterface.isUp) flags = flags or (OsConstants.IFF_UP or OsConstants.IFF_RUNNING)
            if (osInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (osInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (osInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            item.setFlags(flags)
            item.setType(
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) Libbox.InterfaceTypeWIFI
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) Libbox.InterfaceTypeCellular
                else Libbox.InterfaceTypeOther
            )
            item.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            list.add(item)
        }
        val iterator = list.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = iterator.hasNext()

            override fun next(): LibboxNetworkInterface = iterator.next()
        }
    }

    override fun findConnectionOwner(
        protocol: Int,
        source: String,
        sourcePort: Int,
        dest: String,
        destPort: Int
    ): ConnectionOwner {
        val result: ConnectionOwner
        if (Build.VERSION.SDK_INT >= 29) {
            val uid = connectivity.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(source, sourcePort),
                InetSocketAddress(dest, destPort)
            )
            if (uid < 0) throw IllegalStateException("找不到连接所属应用")
            result = ConnectionOwner()
            result.setUserId(uid)
        } else {
            result = Libbox.findConnectionOwner(protocol, source, sourcePort, dest, destPort)
        }
        val names = packageManager.getPackagesForUid(result.getUserId())
        val packages: List<String> = names?.asList() ?: emptyList()
        result.setAndroidPackageNames(strings(packages))
        result.setUserName(if (packages.isEmpty()) "" else packages[0])
        return result
    }

    override fun localDNSTransport(): LocalDNSTransport = object : LocalDNSTransport {
        override fun raw(): Boolean = Build.VERSION.SDK_INT >= 29

        override fun lookup(ctx: ExchangeContext, family: String, domain: String) {
            val network = underlying ?: throw IOException("当前无可用网络")
            val values = ArrayList<String>()
            for (address in network.getAllByName(domain)) {
                if ((family == "ip4" && address is Inet4Address) || (family == "ip6" && address is Inet6Address)) {
                    values.add(address.hostAddress ?: "")
                }
            }
            ctx.success(values.joinToString("\n"))
        }

        override fun exchange(ctx: ExchangeContext, message: ByteArray) {
            if (Build.VERSION.SDK_INT < 29) throw UnsupportedOperationException()
            val network = underlying ?: throw IOException("当前无可用网络")
            val signal = CancellationSignal()
            ctx.onCancel { signal.cancel() }
            val done = CountDownLatch(1)
            DnsResolver.getInstance().rawQuery(
                network,
                message,
                DnsResolver.FLAG_NO_RETRY,
                DIRECT_EXECUTOR,
                signal,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                        done.countDown()
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        ctx.errnoCode(5)
                        done.countDown()
                    }
                }
            )
            if (!done.await(15, TimeUnit.SECONDS)) {
                signal.cancel()
                throw IOException("DNS 查询超时")
            }
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState = Libbox.newWIFIState("", "")

    override fun clearDNSCache() {}

    override fun sendNotification(n: io.nekohasekai.libbox.Notification) {
        log(n.getTitle() + ": " + n.getBody())
    }

    override fun cancelNotification(identifier: String, typeID: Int) {}

    override fun startNeighborMonitor(l: NeighborUpdateListener) {
        throw UnsupportedOperationException("不支持邻居监视")
    }

    override fun closeNeighborMonitor(l: NeighborUpdateListener) {}

    override fun registerMyInterface(name: String) {}

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        throw UnsupportedOperationException("不支持平台 Shell")
    }

    override fun openShellSession(
        u: PlatformUser,
        c: String,
        e: StringIterator,
        t: String,
        r: Int,
        cols: Int
    ): ShellSession {
        throw UnsupportedOperationException()
    }

    override fun lookupUser(name: String): PlatformUser {
        throw UnsupportedOperationException()
    }

    override fun lookupSFTPServer(): String {
        throw UnsupportedOperationException()
    }

    override fun readSystemSSHHostKey(): String {
        throw UnsupportedOperationException()
    }

    override fun tailscaleHostname(): String = "SkyBox-" + Build.MODEL

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(o: BridgeOptions): BridgeSession {
        throw UnsupportedOperationException()
    }

    override fun usePlatformAutoRedirect(): Boolean = false

    override fun createAutoRedirectListener(ipv6: Boolean): Int {
        throw UnsupportedOperationException("不支持 root 自动重定向")
    }

    override fun createAutoRedirect(b: ByteArray, h: AutoRedirectHandler): AutoRedirectSession {
        throw UnsupportedOperationException()
    }

    override fun serviceStop() {
        submit {
            stopCore()
            finishRequest(latestStartId)
        }
    }

    override fun serviceReload() {
        submit {
            stopCore()
            finishRequest(latestStartId)
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

    override fun setSystemProxyEnabled(value: Boolean) {
        throw UnsupportedOperationException()
    }

    override fun triggerNativeCrash() {
        throw UnsupportedOperationException()
    }

    override fun connectSSHAgent(): Int {
        throw UnsupportedOperationException()
    }

    override fun writeDebugMessage(value: String) {
        log(value)
    }

    override fun connected() {}

    override fun disconnected(reason: String) {
        if (running) log("状态订阅断开：$reason")
    }

    override fun clearLogs() {
        synchronized(LOCK) { LOGS.clear() }
    }

    override fun setDefaultLogLevel(level: Int) {}

    override fun initializeClashMode(modes: StringIterator, current: String) {}

    override fun updateClashMode(mode: String) {}

    override fun writeConnectionEvents(events: ConnectionEvents) {}

    override fun writeGroups(groups: OutboundGroupIterator) {
        VpnGroups.replace(groups)
    }

    override fun writeOutbounds(outbounds: OutboundGroupItemIterator) {
        VpnGroups.patchDelays(outbounds)
        // Delay patches arrive in bursts during a urlTest; the 1-second ticker re-reads
        // VpnGroups.revision and renders once instead of rebuilding the rows per patch.
    }

    override fun writeLogs(entries: LogIterator) {
        while (entries.hasNext()) log(entries.next().getMessage())
    }

    override fun writeStatus(status: StatusMessage) {
        downlink = status.getDownlink()
        uplink = status.getUplink()
        downlinkTotal = status.getDownlinkTotal()
        uplinkTotal = status.getUplinkTotal()
        synchronized(LOCK) {
            val now = SystemClock.elapsedRealtime()
            if (now - sampleAt >= SAMPLE_INTERVAL_MS) {
                HISTORY.addLast((downlinkTotal - sampleTotal).coerceAtLeast(0))
                while (HISTORY.size > HISTORY_CAPACITY) HISTORY.removeFirst()
                sampleAt = now
                sampleTotal = downlinkTotal
            }
        }
    }

    companion object {
        @JvmField
        val ACTION_START = "skybox.START"

        @JvmField
        val ACTION_STOP = "skybox.STOP"

        @Volatile
        private var currentClient: CommandClient? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        /** Runs on a worker thread; the group stream pushes the fresh selection back. */
        @JvmStatic
        fun selectOutbound(groupTag: String, outboundTag: String, onError: (String) -> Unit) {
            submit {
                val target = currentClient
                if (target == null) {
                    mainHandler.post { onError("VPN 未运行") }
                    return@submit
                }
                try {
                    target.selectOutbound(groupTag, outboundTag)
                } catch (e: Exception) {
                    val message = e.message ?: "切换失败"
                    mainHandler.post { onError(message) }
                }
            }
        }

        @JvmStatic
        fun urlTest(outboundTag: String) {
            submit {
                try {
                    currentClient?.urlTest(outboundTag)
                } catch (ignored: Exception) {
                }
            }
        }

        @JvmField
        @Volatile
        var running = false

        @JvmField
        @Volatile
        var state = "已断开"

        @JvmField
        @Volatile
        var error = ""

        @JvmField
        @Volatile
        var downlink = 0L

        @JvmField
        @Volatile
        var uplink = 0L

        @JvmField
        @Volatile
        var downlinkTotal = 0L

        @JvmField
        @Volatile
        var uplinkTotal = 0L

        private val LOCK = Any()
        private val LOGS = java.util.ArrayDeque<String>()

        /** Download bytes per [SAMPLE_INTERVAL_MS] bucket, chronological, capped at [HISTORY_CAPACITY]. */
        private val HISTORY = java.util.ArrayDeque<Long>()
        private const val SAMPLE_INTERVAL_MS = 1_000L

        /** 15 buckets × 1 s = the 15-second window the speed chart axis advertises. */
        private const val HISTORY_CAPACITY = 15
        private var sampleAt = 0L
        private var sampleTotal = 0L

        private val workerLock = Any()

        @Volatile
        private var worker: ExecutorService = Executors.newSingleThreadExecutor()

        private fun workerExecutor(): ExecutorService = synchronized(workerLock) {
            if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
            worker
        }

        /**
         * Queues service work without ever throwing. A task submitted while the old service instance
         * tears its executor down is dropped instead of raising RejectedExecutionException on the
         * caller's thread.
         */
        private fun submit(task: () -> Unit) {
            try {
                workerExecutor().execute(task)
            } catch (ignored: RejectedExecutionException) {
            }
        }

        private val DIRECT_EXECUTOR: Executor = Executor { command -> command.run() }

        @Volatile
        private var owner: VpnController? = null

        @JvmStatic
        fun logs(): String = synchronized(LOCK) { LOGS.joinToString("\n") }

        @JvmStatic
        fun speedHistory(): LongArray = synchronized(LOCK) { HISTORY.toLongArray() }

        @JvmStatic
        fun speedSampleIntervalMs(): Long = SAMPLE_INTERVAL_MS

        /** One slot per [SAMPLE_INTERVAL_MS]; the chart prints every slot so the labels stay honest. */
        @JvmStatic
        fun speedSampleCapacity(): Int = HISTORY_CAPACITY

        private fun log(value: String) {
            synchronized(LOCK) {
                LOGS.addLast(value)
                while (LOGS.size > 300) LOGS.removeFirst()
            }
        }

        private fun strings(values: List<String>): StringIterator {
            val iterator = values.iterator()
            return object : StringIterator {
                override fun hasNext(): Boolean = iterator.hasNext()

                override fun next(): String = iterator.next()

                override fun len(): Int = values.size
            }
        }
    }
}
