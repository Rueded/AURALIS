package com.auralis.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val lastSeenMs: Long = System.currentTimeMillis()
)

object NsdHelper {
    private const val TAG          = "NsdHelper"
    private const val SERVICE_TYPE = "_auralis._tcp."
    private const val SERVICE_NAME = "Auralis"
    // 超过这个时间没有心跳响应，视为离线
    private const val DEVICE_TTL_MS = 30_000L
    // 心跳间隔
    private const val HEARTBEAT_INTERVAL_MS = 12_000L

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDevice>> = _discovered.asStateFlow()

    // ── 注册自己（广播存在）────────────────────────────────────

    fun register(context: Context) {
        val deviceId   = AuralisDeviceId.getId(context)
        val deviceName = AuralisDeviceId.getName(context)

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${SERVICE_NAME}_${deviceName.take(10)}"
            serviceType = SERVICE_TYPE
            port        = AuralisServer.PORT
            setAttribute("deviceId",   deviceId)
            setAttribute("deviceName", deviceName)
            setAttribute("version",    "7.5")
        }

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "mDNS 注册成功: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "mDNS 注册失败: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("AuralisNSD").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    fun unregister() {
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
        multicastLock?.release()
        multicastLock = null
    }

    // ── 持续扫描 + 心跳检测 ────────────────────────────────────

    fun startDiscovery(context: Context) {
        if (nsdManager == null) {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        }

        // 先停掉旧的再重启，但不清空已知列表（避免闪烁）
        stopDiscoveryListener()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.e(TAG, "扫描启动失败: $code，2秒后重试")
                // 自动重试
                scope.launch {
                    delay(2000)
                    startDiscovery(context)
                }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onDiscoveryStarted(type: String) {
                Log.d(TAG, "持续扫描已启动")
            }
            override fun onDiscoveryStopped(type: String) {
                Log.d(TAG, "扫描意外停止，自动重启")
                // ✅ 关键：系统停掉 discovery 时自动重启，保持持续扫描
                scope.launch {
                    delay(1000)
                    startDiscovery(context)
                }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("auralis", ignoreCase = true)) return
                nsdManager?.resolveService(info, buildResolveListener(context))
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                // onServiceLost 不可靠，但有触发时也处理
                val current = _discovered.value.toMutableList()
                current.removeAll { it.deviceName == info.serviceName }
                _discovered.value = current
                Log.d(TAG, "设备离线(NSD): ${info.serviceName}")
            }
        }

        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        // 启动心跳检测（如果还没启动）
        startHeartbeat()
    }

    fun stopDiscovery() {
        // ⚠️ 对外的 stopDiscovery 改为只停心跳和监听器，但不从外部调用
        // 正常运行时不应该调用这个，除非 app 完全退出
        heartbeatJob?.cancel()
        heartbeatJob = null
        stopDiscoveryListener()
        _discovered.value = emptyList()
    }

    private fun stopDiscoveryListener() {
        try { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discoveryListener = null
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
        val current = _discovered.value.toMutableList()
        if (current.isEmpty()) return

        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<DiscoveredDevice>()

        current.forEach { device ->
            val reachable = pingDevice(device)
            if (reachable) {
                // 更新 lastSeenMs
                val idx = current.indexOf(device)
                if (idx >= 0) current[idx] = device.copy(lastSeenMs = now)
            } else if (now - device.lastSeenMs > DEVICE_TTL_MS) {
                // 超过 TTL 没响应 → 离线
                toRemove.add(device)
                Log.d(TAG, "心跳超时，移除离线设备: ${device.deviceName}")
            }
        }

        if (toRemove.isNotEmpty()) {
            current.removeAll(toRemove)
            _discovered.value = current.toList()
        }
    }

    private fun pingDevice(device: DiscoveredDevice): Boolean {
        return try {
            val req  = Request.Builder()
                .url("http://${device.host}:${device.port}/auralis/info")
                .build()
            val resp = pingClient.newCall(req).execute()
            resp.isSuccessful.also { resp.close() }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildResolveListener(context: Context) =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "Resolve 失败: $code")
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val myId = AuralisDeviceId.getId(context)
                val attrs = info.attributes

                val deviceId = attrs["deviceId"]?.let { String(it) } ?: return
                if (deviceId == myId) return

                val deviceName = attrs["deviceName"]?.let { String(it) } ?: info.serviceName
                val host = info.host?.hostAddress ?: return

                val device = DiscoveredDevice(
                    deviceId    = deviceId,
                    deviceName  = deviceName,
                    host        = host,
                    port        = info.port,
                    lastSeenMs  = System.currentTimeMillis()
                )

                val current = _discovered.value.toMutableList()
                current.removeAll { it.deviceId == deviceId }
                current.add(0, device)
                _discovered.value = current

                BoundDeviceStore.updateSeen(context, deviceId)
                Log.d(TAG, "发现设备: $deviceName @ $host:${info.port}")
            }
        }
}