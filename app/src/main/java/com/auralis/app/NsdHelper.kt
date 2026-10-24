package com.auralis.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    /** 最后一次确认"连得上"的时间 */
    val lastSeenMs: Long = System.currentTimeMillis(),
    /**
     * 真的能连上吗？
     * 以前"在线"只表示"mDNS 发现了它"，但 mDNS 是多播，TCP 是单播——
     * 很多路由器（校园网、访客网络、开了客户端隔离的）放行多播却不放行设备之间的 TCP，
     * 结果界面显示在线，一传就失败。现在发现之后必须再 ping 通才算在线。
     */
    val reachable: Boolean = true,
    val everReachable: Boolean = false,
    val failCount: Int = 0,
    val viaNsd: Boolean = true,
    /**
     * 备用地址。mDNS 解析会返回对方所有网卡上的地址（Wi-Fi/热点、移动数据 IPv4、IPv6……），
     * 对方开着移动数据时，第一个地址常常是移动数据的，在局域网里根本连不上——
     * 这就是"开着 data 就显示发现了但连不上"的原因。所以把所有候选都留着，心跳时谁通用谁。
     */
    val altHosts: List<String> = emptyList(),
    /** 最近一次连接失败的原因分类（connect_timeout / read_timeout / refused / unreachable / other:xxx），界面用它给出具体提示。 */
    val lastError: String? = null
)

object NsdHelper {
    private const val TAG          = "NsdHelper"
    private const val SERVICE_TYPE = "_auralis._tcp."
    private const val SERVICE_NAME = "Auralis"

    // 心跳：5 秒一次，所有设备并发 ping
    private const val HEARTBEAT_INTERVAL_MS = 5_000L
    // 连续失败几次才判定"连不上"（避免偶发丢包造成在线/离线来回跳）
    private const val FAIL_THRESHOLD = 2
    // 曾经连上过、现在连不上：多久后从列表里移除（对方离开了）
    private const val GONE_REMOVE_MS = 20_000L
    // 从来没连上过（多半是网络隔离）：保留更久，让界面能给出"网络隔离"的提示
    private const val NEVER_REACHABLE_REMOVE_MS = 5 * 60_000L

    private var appContext: Context? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 调用方是否希望一直扫描。只有这个为 true，且"系统自己意外停掉了当前监听器"时才自动重启。
    @Volatile private var discoveryWanted = false

    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // mDNS 已经告诉我们"这里有一台 Auralis"，探测它不需要那么急：
    // 以前复用扫描用的 800ms 读超时，对方稍微慢一点就被误判成"连不上"
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // 子网扫描用的客户端：局域网里连接要么瞬间成功要么没人应，超时可以很短
    private val scanClient = OkHttpClient.Builder()
        .connectTimeout(600, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDevice>> = _discovered.asStateFlow()

    /** 构造到对方的 URL：IPv6 要加方括号、去掉 %wlan0 这种作用域后缀，字符串拼接做不到这点。 */
    fun peerUrl(host: String, port: Int, vararg segments: String): HttpUrl =
        HttpUrl.Builder().scheme("http").host(host.substringBefore('%')).port(port).apply {
            segments.forEach { addPathSegment(it) }
        }.build()

    private fun lanClientFor(base: OkHttpClient, host: String): OkHttpClient {
        val ctx = appContext ?: return base
        return LanNet.clientFor(ctx, base, host)
    }

    /** 当前最新的设备信息（地址可能被心跳切换过）。一起听等功能应该用它，而不是加入时记下的旧快照。 */
    fun currentDevice(deviceId: String): DiscoveredDevice? = _discovered.value.firstOrNull { it.deviceId == deviceId }

    // ── 注册自己（广播存在）────────────────────────────────────

    @Synchronized
    fun register(context: Context) {
        appContext = context.applicationContext
        // Activity 重建会重复调用：已经注册过就不要再注册一份（否则对方会看到重名的"Auralis (2)"）
        if (registrationListener != null) return

        val deviceId   = AuralisDeviceId.getId(context)
        val deviceName = AuralisDeviceId.getName(context)

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${SERVICE_NAME}_${deviceName.take(10)}"
            serviceType = SERVICE_TYPE
            port        = AuralisServer.PORT
            setAttribute("deviceId",   deviceId)
            setAttribute("deviceName", deviceName)
            setAttribute("version",    "7.6")
        }

        nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "mDNS 注册成功: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "mDNS 注册失败: $code")
                // 失败了就清掉，下次 register() 才有机会重试，而不是永远被上面的"已注册"挡住
                synchronized(this@NsdHelper) { if (registrationListener === this) registrationListener = null }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

        if (multicastLock == null) {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("AuralisNSD").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    @Synchronized
    fun unregister() {
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    // ── 持续扫描 + 心跳检测 ────────────────────────────────────

    /** 开始（或重新开始）扫描。不会清空已发现的列表，避免界面闪烁。 */
    @Synchronized
    fun startDiscovery(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (nsdManager == null) {
            nsdManager = appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager
        }
        discoveryWanted = true

        // 先停掉旧的。注意 stopDiscoveryListener 会先把 discoveryListener 置空，
        // 旧监听器随后收到 onDiscoveryStopped 时发现自己已经不是"当前监听器"，就不会再触发重启。
        stopDiscoveryListener()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.e(TAG, "扫描启动失败: $code")
                synchronized(this@NsdHelper) { if (discoveryListener === this) discoveryListener = null }
                if (discoveryWanted) scope.launch { delay(2000); if (discoveryWanted) startDiscovery(appCtx) }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onDiscoveryStarted(type: String) { Log.d(TAG, "持续扫描已启动") }
            override fun onDiscoveryStopped(type: String) {
                val unexpected = synchronized(this@NsdHelper) { discoveryListener === this }
                Log.d(TAG, "扫描已停止 (意外=$unexpected, wanted=$discoveryWanted)")
                if (unexpected && discoveryWanted) {
                    scope.launch { delay(1000); if (discoveryWanted) startDiscovery(appCtx) }
                }
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("auralis", ignoreCase = true)) return
                enqueueResolve(appCtx, info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                // onServiceLost 不一定会来，但来了就是个强烈信号：马上测一遍，不用等下一轮心跳
                Log.d(TAG, "设备离线(NSD): ${info.serviceName}，立即复核")
                scope.launch { checkAllDevices() }
            }
        }
        discoveryListener = listener

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices 抛异常: ${e.message}")
            discoveryListener = null
        }

        startHeartbeat()
        registerNetworkWatcher(appCtx)
    }

    /** 只有 app 真的要退出时才该调用。界面上的"重新扫描"请直接再调 startDiscovery。 */
    @Synchronized
    fun stopDiscovery() {
        discoveryWanted = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        stopDiscoveryListener()
        unregisterNetworkWatcher()
        synchronized(resolveLock) { resolveQueue.clear(); resolving = false }
        _discovered.value = emptyList()
    }

    private fun stopDiscoveryListener() {
        val l = discoveryListener
        discoveryListener = null
        try { l?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
    }

    // ── 网络切换监听 ───────────────────────────────────────────
    // 换了 Wi-Fi / 断网后，旧网络里发现的设备肯定都不在了，不清掉就会一直显示"在线"直到心跳超时；
    // 新网络连上后也要重新扫描，mDNS 的缓存不会自己更新。

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var firstNetCallbackConsumed = false
    @Volatile private var lastWifiHandle = -1L

    private fun registerNetworkWatcher(ctx: Context) {
        if (netCallback != null) return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val h = network.networkHandle
                    if (!firstNetCallbackConsumed) {      // 注册时系统会立刻回调一次当前网络，忽略它
                        firstNetCallbackConsumed = true
                        lastWifiHandle = h
                        return
                    }
                    if (h == lastWifiHandle) return
                    lastWifiHandle = h
                    Log.i(TAG, "Wi-Fi 网络变化，清空设备列表并重新扫描")
                    _discovered.value = emptyList()
                    scope.launch { delay(1500); if (discoveryWanted) startDiscovery(ctx) }
                }
                override fun onLost(network: Network) {
                    if (network.networkHandle == lastWifiHandle) {
                        lastWifiHandle = -1L
                        Log.i(TAG, "Wi-Fi 已断开，清空设备列表")
                        _discovered.value = emptyList()
                    }
                }
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb
            )
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "注册网络监听失败: ${e.message}")
        }
    }

    private fun unregisterNetworkWatcher() {
        val cb = netCallback ?: return
        try {
            (appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        netCallback = null
        firstNetCallbackConsumed = false
    }

    // ── 解析：系统在 Android 14 以前同一时间只允许一个 resolveService ──
    // 同时发现多个服务（或扫描被反复重启）时，第二个会直接失败（FAILURE_ALREADY_ACTIVE = 3），
    // 这里串行化 + 失败重试。

    private val resolveLock = Any()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun enqueueResolve(context: Context, info: NsdServiceInfo) {
        synchronized(resolveLock) {
            if (resolveQueue.none { it.serviceName == info.serviceName }) resolveQueue.addLast(info)
        }
        pumpResolve(context)
    }

    private fun pumpResolve(context: Context) {
        val next = synchronized(resolveLock) {
            if (resolving) return
            val n = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        val finish = { retry: Boolean ->
            synchronized(resolveLock) {
                resolving = false
                if (retry) resolveQueue.addLast(next)
            }
            scope.launch { delay(if (retry) 800 else 0); pumpResolve(context) }
        }
        try {
            nsdManager?.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "Resolve 失败: $code (${info.serviceName})")
                    finish(code == NsdManager.FAILURE_ALREADY_ACTIVE)
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    handleResolved(context, info)
                    finish(false)
                }
            }) ?: finish(false)
        } catch (e: Exception) {
            Log.w(TAG, "resolveService 抛异常: ${e.message}")
            finish(false)
        }
    }

    /** 解析结果里的全部候选地址，按"最可能连得上"排序，最多 4 个。 */
    private fun pickHosts(info: NsdServiceInfo): List<String> {
        val raw: List<java.net.InetAddress> = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            val h = info.host
            listOfNotNull(h)
        }
        val locals = LanNet.localIpv4().mapNotNull { la ->
            try { java.net.InetAddress.getByName(la.ip) to la.prefix } catch (_: Exception) { null }
        }
        fun score(a: java.net.InetAddress): Int = when {
            a is Inet4Address && locals.any { (ip, prefix) -> LanNet.sameSubnet(ip, prefix, a) } -> 0 // 和本机同一网段：几乎肯定是对的
            a is Inet4Address && a.isSiteLocalAddress -> 1
            a is Inet4Address -> 2
            else -> 3                                                                                  // IPv6
        }
        return raw.filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
            .sortedBy(::score)
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
            .take(4)
    }

    /** 请求 /auralis/info，并确认应答的确实是我们要找的那台设备。 */
    /** 返回 null 表示连得上；否则返回失败原因分类。 */
    private fun probeError(host: String, port: Int, expectedId: String): String? = try {
        val req = Request.Builder().url(peerUrl(host, port, "auralis", "info")).build()
        lanClientFor(probeClient, host).newCall(req).execute().use { r ->
            when {
                !r.isSuccessful -> "other:HTTP${r.code}"
                JSONObject(r.body?.string().orEmpty()).optString("deviceId") != expectedId -> "other:wrong_device"
                else -> null
            }
        }
    } catch (e: Exception) { classifyError(e) }

    /**
     * 把异常归类，方便区分"为什么连不上"：
     *  connect_timeout —— 数据包发出去石沉大海：路由器的设备隔离 / 防火墙最典型的表现
     *  read_timeout    —— 连上了但没人应答：对方 app 在后台被系统冻结了（服务器线程不跑）
     *  refused         —— 对方明确拒绝：那台手机在这个端口上没有 Auralis 服务
     *  unreachable     —— 根本没有路由：走错了网络（比如移动数据）或不在同一网段
     */
    private fun classifyError(e: Exception): String {
        val msg = generateSequence(e as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            e is java.net.SocketTimeoutException && msg.contains("connect") -> "connect_timeout"
            e is java.net.SocketTimeoutException -> "read_timeout"
            msg.contains("refused") -> "refused"
            msg.contains("unreachable") || msg.contains("no route") -> "unreachable"
            else -> "other:${e.javaClass.simpleName}"
        }
    }

    private fun handleResolved(context: Context, info: NsdServiceInfo) {
        val myId = AuralisDeviceId.getId(context)
        val attrs = info.attributes

        val deviceId = attrs["deviceId"]?.let { String(it) } ?: return
        if (deviceId == myId) return

        val deviceName = attrs["deviceName"]?.let { String(it) } ?: info.serviceName
        val hosts = pickHosts(info)
        if (hosts.isEmpty()) return
        val port = info.port
        Log.d(TAG, "mDNS 发现设备: $deviceName 候选地址=$hosts port=$port")

        // 没有记录时先放一个"正在连接"的占位（reachable=false），界面能看到它
        _discovered.update { list ->
            if (list.any { it.deviceId == deviceId }) list
            else listOf(DiscoveredDevice(deviceId, deviceName, hosts.first(), port, reachable = false,
                altHosts = hosts.drop(1), viaNsd = true)) + list
        }

        scope.launch {
            // 已经有一条"确认连得上"的记录（比如子网扫描或上一次心跳验证过的）：
            // 不要用 mDNS 的地址去覆盖它（以前就是被这样覆盖成了连不上的地址），只把这些地址记成备用
            val existing = _discovered.value.firstOrNull { it.deviceId == deviceId }
            if (existing != null && existing.reachable) {
                _discovered.update { list ->
                    list.map { d ->
                        if (d.deviceId == deviceId)
                            d.copy(altHosts = (d.altHosts + hosts).distinct().filter { it != d.host }.take(4))
                        else d
                    }
                }
                return@launch
            }

            // 把所有候选地址逐个试一遍，用第一个真正应答的；全都失败就记下首选地址的失败原因
            // 并发探测：以前是一个一个试，前面的超时会把后面的都拖慢
            val probed: List<Pair<String, String?>> = coroutineScope {
                hosts.map { h -> async { h to probeError(h, port, deviceId) } }.awaitAll()
            }
            probed.forEach { (h, err) -> if (err != null) Log.d(TAG, "$deviceName 候选地址 $h 连不上: $err") }
            val working: String? = probed.firstOrNull { it.second == null }?.first   // 按优先级顺序取第一个能通的
            val firstError: String? = probed.firstOrNull { it.second != null }?.second
            val chosen = working ?: hosts.first()
            Log.d(TAG, "$deviceName 候选地址验证: 选用 $chosen，可连接=${working != null}，原因=${if (working == null) firstError else "-"}")
            _discovered.update { list ->
                list.map { d ->
                    if (d.deviceId == deviceId && !d.reachable)
                        d.copy(host = chosen, port = port, altHosts = hosts.filter { it != chosen },
                            reachable = working != null, everReachable = d.everReachable || working != null,
                            lastError = if (working != null) null else firstError,
                            lastSeenMs = System.currentTimeMillis())
                    else d
                }
            }
            if (working != null) BoundDeviceStore.updateSeen(context, deviceId)
        }
    }

    // ── 不依赖 mDNS 的发现：子网扫描 + 手动输入 IP ───────────────
    // mDNS 是多播，不少网络（校园网/访客网络/中继器/部分路由器）会拦截，但设备之间的 TCP 往往还是通的。
    // 直接对本网段每个地址请求 /auralis/info，谁应答谁就是 Auralis 设备。
    // 如果 TCP 也被隔离了（路由器开了"客户端隔离"），这个办法也没用，只能换手机热点之类的网络。

    /** 探测一个地址：是 Auralis 设备且不是自己就返回设备信息。 */
    suspend fun probe(context: Context, host: String, port: Int = AuralisServer.PORT, client: OkHttpClient? = null): DiscoveredDevice? =
        withContext(Dispatchers.IO) {
            try {
                val c = client ?: LanNet.clientFor(context, scanClient, host)
                c.newCall(Request.Builder().url(peerUrl(host, port, "auralis", "info")).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val j = JSONObject(r.body?.string().orEmpty())
                    val id = j.optString("deviceId")
                    if (id.isBlank() || id == AuralisDeviceId.getId(context)) return@use null
                    DiscoveredDevice(
                        deviceId = id, deviceName = j.optString("deviceName", host),
                        host = host, port = port, reachable = true, everReachable = true, viaNsd = false
                    )
                }
            } catch (_: Exception) { null }
        }

    private fun addOrUpdate(dev: DiscoveredDevice) {
        _discovered.update { list ->
            val old = list.firstOrNull { it.deviceId == dev.deviceId }
            // 旧记录里的地址留作备用，不丢
            val alts = ((old?.let { listOf(it.host) + it.altHosts } ?: emptyList()) + dev.altHosts)
                .distinct().filter { it != dev.host }.take(4)
            listOf(dev.copy(altHosts = alts)) + list.filterNot { it.deviceId == dev.deviceId }
        }
    }

    /** 手动输入 IP 连接。成功返回设备，失败返回 null。 */
    suspend fun connectManually(context: Context, host: String, port: Int = AuralisServer.PORT): DiscoveredDevice? {
        val dev = probe(context.applicationContext, host.trim(), port, LanNet.clientFor(context.applicationContext, pingClient, host.trim()))
        if (dev != null) {
            appContext = context.applicationContext
            addOrUpdate(dev)
            startHeartbeat()
        }
        return dev
    }

    /** 扫描本机所在网段，返回找到的设备数。 */
    suspend fun scanSubnet(context: Context): Int = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        appContext = ctx
        val targets = LanNet.localIpv4().flatMap { subnetHosts(it) }.distinct()
        if (targets.isEmpty()) return@withContext 0
        Log.d(TAG, "子网扫描 ${targets.size} 个地址")

        val sem = Semaphore(48)
        val found = AtomicInteger(0)
        coroutineScope {
            targets.map { ip ->
                async {
                    sem.withPermit {
                        probe(ctx, ip, AuralisServer.PORT, LanNet.clientFor(ctx, scanClient, ip))?.let {
                            addOrUpdate(it); found.incrementAndGet()
                            Log.d(TAG, "子网扫描发现: ${it.deviceName} @ $ip")
                        }
                    }
                }
            }.awaitAll()
        }
        if (found.get() > 0) startHeartbeat()
        found.get()
    }

    private fun subnetHosts(a: LanNet.LocalAddr): List<String> {
        val parts = a.ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return emptyList()
        val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val prefix = a.prefix.coerceIn(24, 30)              // 最多扫一个 /24（254 个地址），更小的网段按实际大小
        val mask = -1 shl (32 - prefix)
        val net = ipInt and mask
        val broadcast = net or mask.inv()
        return ((net + 1) until broadcast).filter { it != ipInt }.map {
            "${(it ushr 24) and 255}.${(it ushr 16) and 255}.${(it ushr 8) and 255}.${it and 255}"
        }
    }

    // ── 心跳：定期 HTTP ping 每个已发现设备 ─────────────────────

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return  // 已在跑，不重复启动
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                checkAllDevices()
            }
        }
    }

    private suspend fun checkAllDevices() {
        val snapshot = _discovered.value
        if (snapshot.isEmpty()) return

        // 并发 ping：设备多、其中有离线的时候，串行 ping 会被超时一个个拖慢。
        // 每台设备依次试 首选地址 + 备用地址，返回第一个能通的（都不通返回 null）
        val results: Map<String, Pair<String?, String?>> = coroutineScope {
            snapshot.map { d -> async { d.deviceId to findWorkingHost(d) } }.awaitAll().toMap()
        }
        val now = System.currentTimeMillis()

        // 用 update 合并，不会覆盖 ping 期间新发现的设备
        _discovered.update { list ->
            list.mapNotNull { d ->
                if (!results.containsKey(d.deviceId)) return@mapNotNull d   // 刚加入的，下一轮再测
                val (working, error) = results[d.deviceId] ?: (null to null)
                if (working != null) {
                    val alts = if (working == d.host) d.altHosts
                               else (listOf(d.host) + d.altHosts).filter { it != working }.distinct().take(4)
                    if (working != d.host) Log.i(TAG, "${d.deviceName} 地址切换: ${d.host} → $working")
                    d.copy(host = working, altHosts = alts, reachable = true, everReachable = true,
                        failCount = 0, lastError = null, lastSeenMs = now)
                } else {
                    val fails = d.failCount + 1
                    val reachable = fails < FAIL_THRESHOLD && d.reachable
                    val limit = if (d.everReachable) GONE_REMOVE_MS else NEVER_REACHABLE_REMOVE_MS
                    if (!reachable && now - d.lastSeenMs > limit) {
                        Log.d(TAG, "移除离线设备: ${d.deviceName}")
                        null
                    } else d.copy(reachable = reachable, failCount = fails, lastError = error ?: d.lastError)
                }
            }
        }
    }

    /** 返回 (能通的地址, 首选地址的失败原因)。 */
    private fun findWorkingHost(d: DiscoveredDevice): Pair<String?, String?> {
        var firstError: String? = null
        for (h in listOf(d.host) + d.altHosts) {
            val err = pingError(h, d.port)
            if (err == null) return h to null
            if (firstError == null) firstError = err
        }
        return null to firstError
    }

    private fun pingError(host: String, port: Int): String? = try {
        val req = Request.Builder().url(peerUrl(host, port, "auralis", "info")).build()
        lanClientFor(pingClient, host).newCall(req).execute().use { if (it.isSuccessful) null else "other:HTTP${it.code}" }
    } catch (e: Exception) { classifyError(e) }
}
