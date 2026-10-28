package io.github.yuedaochangmendian.skybox;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.system.OsConstants;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.nekohasekai.libbox.*;

public final class VpnController extends VpnService implements PlatformInterface, CommandServerHandler, CommandClientHandler {
    public static final String ACTION_START = "skybox.START", ACTION_STOP = "skybox.STOP";
    public static volatile boolean running;
    public static volatile String state = "已断开", error = "";
    public static volatile long downlink, uplink, downlinkTotal, uplinkTotal;
    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();
    private static final ArrayDeque<Long> HISTORY = new ArrayDeque<>();
    private static long sampleAt, sampleTotal;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile VpnController owner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int latestStartId;
    private volatile boolean destroyed;
    private CommandServer server;
    private CommandClient client;
    private ParcelFileDescriptor tun;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private InterfaceUpdateListener listener;
    private volatile Network underlying;
    private boolean stopping;

    public static synchronized String logs() { return String.join("\n", LOGS); }
    public static synchronized long[] downloadHistory() {
        long[] result = new long[HISTORY.size()]; int i = 0;
        for (Long value : HISTORY) result[i++] = value;
        return result;
    }
    private static synchronized void log(String value) {
        LOGS.addLast(value); while (LOGS.size() > 300) LOGS.removeFirst();
    }
    @Override public void onCreate() {
        super.onCreate(); owner = this;
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        latestStartId = id;
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            state = "正在断开";
            worker.execute(() -> { stopCore(); finishRequest(id); });
        } else {
            foreground("正在连接");
            worker.execute(() -> { if (!destroyed && latestStartId == id && owner == this) startCore(id); });
        }
        return START_NOT_STICKY;
    }
    private void finishRequest(int id) {
        main.post(() -> {
            if (owner == this && !destroyed && latestStartId == id) {
                stopForeground(true);
                stopSelfResult(id);
            }
        });
    }
    private void foreground(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel("vpn", "VPN 连接", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, VpnController.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(this, "vpn") : new android.app.Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("SkyBox").setContentText(text).setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开", stop);
        startForeground(1, builder.build());
    }
    private void startCore(int requestId) {
        if (server != null || stopping || destroyed || owner != this) return;
        state = "连接中"; error = "";
        try {
            String config = ProfileStore.activeConfig(this);
            if (config.isEmpty()) throw new IllegalStateException("请先导入并选择 sing-box JSON 配置");
            ProfileStore.validate(this, config);
            if (!Libbox.hasTunInbound(config)) throw new IllegalArgumentException("Android VPN 配置必须包含 tun 入站");
            synchronized (VpnController.class) { HISTORY.clear(); sampleAt = SystemClock.elapsedRealtime(); sampleTotal = 0; }
            LatencyProbe.milliseconds = LatencyProbe.fastest = LatencyProbe.slowest = -1;
            LatencyProbe.failure = "";
            downlink = uplink = downlinkTotal = uplinkTotal = 0;
            server = Libbox.newCommandServer(this, this);
            server.start();
            server.startOrReloadService(config, new OverrideOptions());
            if (tun == null) throw new IllegalStateException("配置未建立 VPN 接口");
            if (destroyed || latestStartId != requestId || owner != this) { stopCore(); return; }
            running = true; state = "已连接";
            CommandClientOptions options = new CommandClientOptions();
            options.addCommand(Libbox.CommandStatus); options.addCommand(Libbox.CommandLog);
            options.setStatusInterval(1000000000L);
            client = Libbox.newCommandClient(this, options); client.connect();
            foreground(ProfileStore.activeName(this)); log("VPN 已连接");
        } catch (Exception | LinkageError e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            log("连接失败：" + error);
            stopCore();
            if (owner == this && !destroyed) state = "连接失败";
            finishRequest(requestId);
        }
    }
    private void stopCore() {
        stopping = true;
        try {
            if (client != null) { try { client.disconnect(); } catch (Exception ignored) {} client = null; }
            if (server != null) {
                try { server.closeService(); } catch (Exception e) { log("停止内核：" + e.getMessage()); }
                finally { server.close(); server = null; }
            }
            if (tun != null) { try { tun.close(); } catch (Exception ignored) {} tun = null; }
            if (callback != null) { try { connectivity.unregisterNetworkCallback(callback); } catch (Exception ignored) {} callback = null; }
            listener = null; underlying = null;
            if (owner == this) {
                running = false; downlink = uplink = 0;
                if (!destroyed) { state = "已断开"; log("VPN 已断开"); }
            }
        } finally { stopping = false; }
    }
    @Override public void onRevoke() { worker.execute(() -> { stopCore(); finishRequest(latestStartId); }); }
    @Override public void onDestroy() {
        destroyed = true;
        running = false; state = "已断开";
        if (owner == this) owner = null;
        worker.execute(this::stopCore);
        worker.shutdown();
        super.onDestroy();
    }
    @Override public boolean usePlatformAutoDetectInterfaceControl() { return true; }
    @Override public void autoDetectInterfaceControl(int fd) throws Exception {
        if (!protect(fd)) throw new java.io.IOException("无法保护内核套接字");
    }
    @Override public int openTun(TunOptions options) throws Exception {
        if (prepare(this) != null) throw new IllegalStateException("VPN 授权已失效");
        Builder builder = new Builder().setSession("SkyBox").setMtu(options.getMTU());
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
        addAddresses(builder, options.getInet4Address()); addAddresses(builder, options.getInet6Address());
        if (!options.getAutoRoute()) throw new IllegalArgumentException("Android VPN 需要 tun.auto_route=true");
        if (options.getDNSMode() != null && !Libbox.DNSModeDisabled.equals(options.getDNSMode().getValue())) {
            StringIterator dns = options.getDNSServerAddress(); while (dns.hasNext()) builder.addDnsServer(dns.next());
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addRoutes(builder, options.getInet4RouteAddress(), options.getInet4Address().hasNext() ? "0.0.0.0" : null);
            addRoutes(builder, options.getInet6RouteAddress(), options.getInet6Address().hasNext() ? "::" : null);
            excludeRoutes(builder, options.getInet4RouteExcludeAddress()); excludeRoutes(builder, options.getInet6RouteExcludeAddress());
        } else {
            addRoutes(builder, options.getInet4RouteRange(), null); addRoutes(builder, options.getInet6RouteRange(), null);
        }
        StringIterator include = options.getIncludePackage();
        int included = 0;
        while (include.hasNext()) { if (addPackage(builder, include.next(), true)) included++; }
        StringIterator exclude = options.getExcludePackage();
        while (exclude.hasNext()) addPackage(builder, exclude.next(), false);
        if (included > 0) log("分应用代理：" + included + " 个应用");
        if (options.isHTTPProxyEnabled() && Build.VERSION.SDK_INT >= 29) {
            List<String> bypass = new ArrayList<>(); StringIterator it = options.getHTTPProxyBypassDomain();
            while (it.hasNext()) bypass.add(it.next());
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(options.getHTTPProxyServer(), options.getHTTPProxyServerPort(), bypass));
        }
        ParcelFileDescriptor result = builder.establish();
        if (result == null) throw new IllegalStateException("VPN 接口建立失败");
        tun = result; return result.getFd();
    }
    private boolean addPackage(Builder b, String packageName, boolean include) {
        try {
            if (include) b.addAllowedApplication(packageName); else b.addDisallowedApplication(packageName);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            log("跳过未安装的应用：" + packageName);
            return false;
        }
    }
    private void addAddresses(Builder b, RoutePrefixIterator it) { while (it.hasNext()) { RoutePrefix p = it.next(); b.addAddress(p.address(), p.prefix()); } }
    private void addRoutes(Builder b, RoutePrefixIterator it, String fallback) {
        boolean any = false;
        while (it.hasNext()) { RoutePrefix p = it.next(); b.addRoute(p.address(), p.prefix()); any = true; }
        if (!any && fallback != null) b.addRoute(fallback, 0);
    }
    private void excludeRoutes(Builder b, RoutePrefixIterator it) throws java.net.UnknownHostException { if (Build.VERSION.SDK_INT >= 33) while (it.hasNext()) { RoutePrefix p = it.next(); b.excludeRoute(new IpPrefix(java.net.InetAddress.getByName(p.address()), p.prefix())); } }
    @Override public void startDefaultInterfaceMonitor(InterfaceUpdateListener value) {
        listener = value;
        Handler looper = new Handler(Looper.getMainLooper());
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { underlying = network; updateNetwork(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { underlying = network; updateNetwork(); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { underlying = network; updateNetwork(); }
            @Override public void onLost(Network network) { if (network.equals(underlying)) { underlying = null; updateNetwork(); } }
        };
        // A VPN itself also satisfies INTERNET, so ask for the network a new request would use
        // (REQUEST semantics) instead of scanning every available network.
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 31) connectivity.registerBestMatchingNetworkCallback(request, callback, looper);
            else if (Build.VERSION.SDK_INT >= 28) connectivity.requestNetwork(request, callback, looper);
            else if (Build.VERSION.SDK_INT >= 26) connectivity.registerDefaultNetworkCallback(callback, looper);
            else connectivity.registerDefaultNetworkCallback(callback);
        } catch (RuntimeException e) {
            log("默认网络监听失败：" + e.getMessage());
            try {
                if (Build.VERSION.SDK_INT >= 26) connectivity.registerDefaultNetworkCallback(callback, looper);
                else connectivity.registerDefaultNetworkCallback(callback);
            } catch (RuntimeException ignored) { }
        }
        Network active = connectivity.getActiveNetwork();
        if (active != null) { underlying = active; updateNetwork(); }
    }
    private void updateNetwork() {
        InterfaceUpdateListener target = listener; if (target == null) return;
        Network n = underlying;
        try {
            LinkProperties lp = n == null ? null : connectivity.getLinkProperties(n);
            java.net.NetworkInterface ni = lp == null || lp.getInterfaceName() == null ? null : java.net.NetworkInterface.getByName(lp.getInterfaceName());
            NetworkCapabilities caps = n == null ? null : connectivity.getNetworkCapabilities(n);
            setUnderlyingNetworks(n == null ? null : new Network[]{n});
            target.updateDefaultInterface(ni == null ? "" : ni.getName(), ni == null ? -1 : ni.getIndex(), caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), false);
        } catch (Exception e) { log("网络接口更新失败"); }
    }
    @Override public void closeDefaultInterfaceMonitor(InterfaceUpdateListener value) {
        listener = null;
        if (callback != null) { connectivity.unregisterNetworkCallback(callback); callback = null; }
    }
    @Override public NetworkInterfaceIterator getInterfaces() throws Exception {
        List<io.nekohasekai.libbox.NetworkInterface> list = new ArrayList<>();
        for (Network network : connectivity.getAllNetworks()) {
            LinkProperties lp = connectivity.getLinkProperties(network); NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (lp == null || caps == null || lp.getInterfaceName() == null) continue;
            java.net.NetworkInterface ni = java.net.NetworkInterface.getByName(lp.getInterfaceName()); if (ni == null) continue;
            io.nekohasekai.libbox.NetworkInterface item = new io.nekohasekai.libbox.NetworkInterface();
            item.setName(ni.getName()); item.setIndex(ni.getIndex()); item.setMTU(ni.getMTU());
            List<String> addresses = new ArrayList<>();
            for (java.net.InterfaceAddress a : ni.getInterfaceAddresses()) {
                String host = java.net.InetAddress.getByAddress(a.getAddress().getAddress()).getHostAddress();
                addresses.add(host + "/" + a.getNetworkPrefixLength());
            }
            item.setAddresses(strings(addresses));
            List<String> dns = new ArrayList<>(); for (java.net.InetAddress a : lp.getDnsServers()) dns.add(a.getHostAddress());
            item.setDNSServer(strings(dns)); item.setDNSSearchDomain(strings(Collections.emptyList())); item.setGateway(strings(Collections.emptyList()));
            item.setFlags((ni.isUp() ? OsConstants.IFF_UP | OsConstants.IFF_RUNNING : 0) | (ni.isLoopback() ? OsConstants.IFF_LOOPBACK : 0) | (ni.isPointToPoint() ? OsConstants.IFF_POINTOPOINT : 0) | (ni.supportsMulticast() ? OsConstants.IFF_MULTICAST : 0));
            item.setType(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? Libbox.InterfaceTypeWIFI : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? Libbox.InterfaceTypeCellular : Libbox.InterfaceTypeOther);
            item.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)); list.add(item);
        }
        Iterator<io.nekohasekai.libbox.NetworkInterface> it = list.iterator();
        return new NetworkInterfaceIterator() { public boolean hasNext() { return it.hasNext(); } public io.nekohasekai.libbox.NetworkInterface next() { return it.next(); } };
    }
    private static StringIterator strings(List<String> values) {
        Iterator<String> it = values.iterator(); return new StringIterator() { public boolean hasNext() { return it.hasNext(); } public String next() { return it.next(); } public int len() { return values.size(); } };
    }
    @Override public ConnectionOwner findConnectionOwner(int protocol, String source, int sourcePort, String dest, int destPort) throws Exception {
        ConnectionOwner owner;
        if (Build.VERSION.SDK_INT >= 29) {
            int uid = connectivity.getConnectionOwnerUid(protocol, new InetSocketAddress(source, sourcePort), new InetSocketAddress(dest, destPort));
            if (uid < 0) throw new IllegalStateException("找不到连接所属应用");
            owner = new ConnectionOwner(); owner.setUserId(uid);
        } else owner = Libbox.findConnectionOwner(protocol, source, sourcePort, dest, destPort);
        String[] names = getPackageManager().getPackagesForUid(owner.getUserId());
        List<String> packages = names == null ? Collections.emptyList() : Arrays.asList(names);
        owner.setAndroidPackageNames(strings(packages)); owner.setUserName(packages.isEmpty() ? "" : packages.get(0)); return owner;
    }
    @Override public LocalDNSTransport localDNSTransport() {
        return new LocalDNSTransport() {
            public boolean raw() { return Build.VERSION.SDK_INT >= 29; }
            public void lookup(ExchangeContext ctx, String family, String domain) throws Exception {
                Network network = underlying;
                if (network == null) throw new java.io.IOException("当前无可用网络");
                List<String> values = new ArrayList<>();
                for (java.net.InetAddress address : network.getAllByName(domain)) {
                    if ((family.equals("ip4") && address instanceof java.net.Inet4Address) || (family.equals("ip6") && address instanceof java.net.Inet6Address)) values.add(address.getHostAddress());
                }
                ctx.success(String.join("\n", values));
            }
            public void exchange(ExchangeContext ctx, byte[] message) throws Exception {
                if (Build.VERSION.SDK_INT < 29) throw new UnsupportedOperationException();
                Network network = underlying;
                if (network == null) throw new java.io.IOException("当前无可用网络");
                CancellationSignal signal = new CancellationSignal();
                ctx.onCancel(() -> signal.cancel());
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                DnsResolver.getInstance().rawQuery(network, message, DnsResolver.FLAG_NO_RETRY, Runnable::run, signal, new DnsResolver.Callback<byte[]>() {
                    public void onAnswer(byte[] answer, int rcode) { if (rcode == 0) ctx.rawSuccess(answer); else ctx.errorCode(rcode); done.countDown(); }
                    public void onError(DnsResolver.DnsException exception) { ctx.errnoCode(5); done.countDown(); }
                });
                if (!done.await(15, java.util.concurrent.TimeUnit.SECONDS)) { signal.cancel(); throw new java.io.IOException("DNS 查询超时"); }
            }
        };
    }
    @Override public boolean underNetworkExtension() { return false; }
    @Override public boolean includeAllNetworks() { return false; }
    @Override public WIFIState readWIFIState() { return Libbox.newWIFIState("", ""); }
    @Override public void clearDNSCache() {}
    @Override public void sendNotification(io.nekohasekai.libbox.Notification n) { log(n.getTitle() + ": " + n.getBody()); }
    @Override public void cancelNotification(String identifier, int typeID) {}
    @Override public void startNeighborMonitor(NeighborUpdateListener l) throws Exception { throw new UnsupportedOperationException("不支持邻居监视"); }
    @Override public void closeNeighborMonitor(NeighborUpdateListener l) {}
    @Override public void registerMyInterface(String name) {}
    @Override public boolean usePlatformShell() { return false; }
    @Override public void checkPlatformShell() throws Exception { throw new UnsupportedOperationException("不支持平台 Shell"); }
    @Override public ShellSession openShellSession(PlatformUser u, String c, StringIterator e, String t, int r, int cols) throws Exception { throw new UnsupportedOperationException(); }
    @Override public PlatformUser lookupUser(String name) throws Exception { throw new UnsupportedOperationException(); }
    @Override public String lookupSFTPServer() throws Exception { throw new UnsupportedOperationException(); }
    @Override public String readSystemSSHHostKey() throws Exception { throw new UnsupportedOperationException(); }
    @Override public String tailscaleHostname() { return "SkyBox-" + Build.MODEL; }
    @Override public boolean usePlatformBridge() { return false; }
    @Override public BridgeSession createBridge(BridgeOptions o) throws Exception { throw new UnsupportedOperationException(); }
    @Override public boolean usePlatformAutoRedirect() { return false; }
    @Override public int createAutoRedirectListener(boolean ipv6) throws Exception { throw new UnsupportedOperationException("不支持 root 自动重定向"); }
    @Override public AutoRedirectSession createAutoRedirect(byte[] b, AutoRedirectHandler h) throws Exception { throw new UnsupportedOperationException(); }
    @Override public void serviceStop() { worker.execute(() -> { stopCore(); finishRequest(latestStartId); }); }
    @Override public void serviceReload() { worker.execute(() -> { stopCore(); finishRequest(latestStartId); }); }
    @Override public SystemProxyStatus getSystemProxyStatus() { return new SystemProxyStatus(); }
    @Override public void setSystemProxyEnabled(boolean value) throws Exception { throw new UnsupportedOperationException(); }
    @Override public void triggerNativeCrash() throws Exception { throw new UnsupportedOperationException(); }
    @Override public int connectSSHAgent() throws Exception { throw new UnsupportedOperationException(); }
    @Override public void writeDebugMessage(String value) { log(value); }
    @Override public void connected() {}
    @Override public void disconnected(String reason) { if (running) log("状态订阅断开：" + reason); }
    @Override public void clearLogs() { synchronized (VpnController.class) { LOGS.clear(); } }
    @Override public void setDefaultLogLevel(int level) {}
    @Override public void initializeClashMode(StringIterator modes, String current) {}
    @Override public void updateClashMode(String mode) {}
    @Override public void writeConnectionEvents(ConnectionEvents events) {}
    @Override public void writeGroups(OutboundGroupIterator groups) {}
    @Override public void writeOutbounds(OutboundGroupItemIterator outbounds) {}
    @Override public void writeLogs(LogIterator entries) { while (entries.hasNext()) log(entries.next().getMessage()); }
    @Override public void writeStatus(StatusMessage status) {
        downlink = status.getDownlink(); uplink = status.getUplink(); downlinkTotal = status.getDownlinkTotal(); uplinkTotal = status.getUplinkTotal();
        synchronized (VpnController.class) {
            long now = SystemClock.elapsedRealtime();
            if (now - sampleAt >= 10000) { HISTORY.addLast(Math.max(0, downlinkTotal - sampleTotal)); while (HISTORY.size() > 6) HISTORY.removeFirst(); sampleAt = now; sampleTotal = downlinkTotal; }
        }
    }
}
