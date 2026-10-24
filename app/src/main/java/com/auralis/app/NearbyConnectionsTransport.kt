package com.auralis.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通过 Google Nearby Connections（Google Play 服务）在两台手机之间传歌，作为"局域网 HTTP 直连"之外的备用方式。
 *
 * 它和局域网方式的区别：
 *  · 发现：用蓝牙，不依赖 mDNS（路由器拦截多播也没关系）；
 *  · 传输：连接建立后 Google 会自动在蓝牙 / Wi-Fi 局域网 / Wi-Fi Direct / 热点之间挑最快的升级过去，
 *          不经过路由器，所以路由器的"客户端隔离"、第三台手机当热点转发慢这类问题都绕得开；
 *  · 安全：连接时两台手机会显示同一个验证码，需要双方确认后才会开始传输。
 *
 * 使用流程：
 *   接收方：startReceiving()         → 等待对方连接
 *   发送方：startSending(歌曲列表)   → 搜索到对方后 connectTo(endpointId)
 *   双方：  state 里 auth 不为空时弹出验证码对话框 → acceptAuth() / rejectAuth()
 *   任何时候：cancel()
 *
 * 协议（都走 Nearby 的 BYTES 载荷，JSON）：
 *   {"t":"manifest","count":N,"totalBytes":T}               发送方 → 接收方：总览
 *   {"t":"file","pid":<FILE 载荷 id>,"filename":..,"title":..,"artist":..,"size":..}   每首歌的文件载荷之前
 *   {"t":"lrc","filename":"xx.lrc","text":".."}              歌词（小文件直接内嵌）
 *   {"t":"done"}                                             发送方 → 接收方：全部发完
 *   {"t":"ack","ok":n,"failed":m}                            接收方 → 发送方：处理结果
 * 注意：不同类型的载荷之间不保证到达顺序（BYTES 头和 FILE 文件可能谁先到都有可能），接收方两种顺序都处理。
 */
object NearbyConnectionsTransport {
    private const val TAG = "NearbyConn"
    private const val SERVICE_ID = "com.auralis.app.nearbyconn"
    // 点对点策略：只允许 1 对 1，但吞吐量最高（官方文档：适合传大文件）
    private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    private const val MAX_LRC_BYTES = 30_000

    enum class Role { NONE, SENDER, RECEIVER }
    enum class Phase { IDLE, ADVERTISING, DISCOVERING, CONNECTING, CONNECTED, DONE, ERROR }

    data class Endpoint(val id: String, val name: String)
    data class AuthRequest(val endpointId: String, val peerName: String, val digits: String)

    data class UiState(
        val role: Role = Role.NONE,
        val phase: Phase = Phase.IDLE,
        val localName: String = "",
        val endpoints: List<Endpoint> = emptyList(),
        val peerName: String? = null,
        val auth: AuthRequest? = null,
        val progress: TransferProgress = TransferProgress(),
        val currentTitle: String? = null,
        val okCount: Int = 0,
        val failedCount: Int = 0,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private lateinit var appContext: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client get() = Nearby.getConnectionsClient(appContext)

    // ── 会话状态 ──
    @Volatile private var connectedEndpoint: String? = null
    @Volatile private var sendQueue: List<Song> = emptyList()
    @Volatile private var finished = false
    @Volatile private var lockHeld = false
    private var sendJob: Job? = null

    // 发送方：每个 FILE 载荷 id → 等待它完成的信号
    private val pendingSend = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    @Volatile private var ackDeferred: CompletableDeferred<JSONObject?>? = null

    // 接收方
    private val incomingPayloads = ConcurrentHashMap<Long, Payload>()   // 还没传完的 FILE 载荷
    private val completedPayloads = ConcurrentHashMap<Long, Payload>()  // 已传完、等待头信息的 FILE 载荷
    private val headers = ConcurrentHashMap<Long, JSONObject>()         // FILE 载荷 id → 头信息
    @Volatile private var expectCount = 0
    @Volatile private var senderDone = false
    private val doneFiles = AtomicInteger(0)
    private val failedFiles = AtomicInteger(0)

    // 进度（两个方向共用）：载荷 id → 已传字节
    private val bytesByPayload = ConcurrentHashMap<Long, Long>()
    @Volatile private var totalBytes = 0L
    @Volatile private var transferStartNs = 0L
    @Volatile private var lastEmitNs = 0L

    // ───────────────────────── 对外接口 ─────────────────────────

    fun startReceiving(context: Context) {
        appContext = context.applicationContext
        resetInternal()
        val name = AuralisDeviceId.getName(appContext)
        _state.value = UiState(role = Role.RECEIVER, phase = Phase.ADVERTISING, localName = name)
        client.startAdvertising(
            name, SERVICE_ID, lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener { Log.i(TAG, "开始广播: $name") }
            .addOnFailureListener { fail(it) }
    }

    fun startSending(context: Context, songs: List<Song>) {
        appContext = context.applicationContext
        resetInternal()
        val existing = songs.filter { File(it.data).exists() }
        val name = AuralisDeviceId.getName(appContext)
        if (existing.isEmpty()) {
            _state.value = UiState(role = Role.SENDER, phase = Phase.ERROR, localName = name,
                error = appContext.getString(R.string.nc_err_no_files))
            return
        }
        sendQueue = existing
        totalBytes = existing.sumOf { File(it.data).length() }
        _state.value = UiState(
            role = Role.SENDER, phase = Phase.DISCOVERING, localName = name,
            progress = TransferProgress(totalFiles = existing.size, totalBytes = totalBytes)
        )
        client.startDiscovery(
            SERVICE_ID, discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener { Log.i(TAG, "开始搜索附近设备，待发送 ${existing.size} 首") }
            .addOnFailureListener { fail(it) }
    }

    /** 发送方：选中搜索到的某台设备，发起连接。 */
    fun connectTo(endpointId: String) {
        val name = _state.value.localName
        _state.update { s ->
            s.copy(phase = Phase.CONNECTING, peerName = s.endpoints.firstOrNull { it.id == endpointId }?.name)
        }
        client.requestConnection(name, endpointId, lifecycleCallback)
            .addOnFailureListener { fail(it) }
    }

    /** 用户确认两台手机上的验证码一致，接受连接。 */
    fun acceptAuth() {
        val a = _state.value.auth ?: return
        _state.update { it.copy(auth = null) }
        client.acceptConnection(a.endpointId, payloadCallback)
            .addOnFailureListener { fail(it) }
    }

    fun rejectAuth() {
        val a = _state.value.auth ?: return
        _state.update { it.copy(auth = null) }
        client.rejectConnection(a.endpointId)
    }

    fun cancel() {
        if (!::appContext.isInitialized) { _state.value = UiState(); return }   // 还没开始过
        Log.i(TAG, "取消")
        resetInternal()
        _state.value = UiState()
    }

    // ───────────────────────── 发现 ─────────────────────────

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "发现设备: ${info.endpointName} ($endpointId)")
            _state.update { s ->
                s.copy(endpoints = s.endpoints.filterNot { it.id == endpointId } + Endpoint(endpointId, info.endpointName))
            }
        }

        override fun onEndpointLost(endpointId: String) {
            _state.update { s -> s.copy(endpoints = s.endpoints.filterNot { it.id == endpointId }) }
        }
    }

    // ───────────────────────── 连接生命周期 ─────────────────────────

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "连接请求: ${info.endpointName}，验证码 ${info.authenticationDigits}")
            // 官方文档明确要求：不做验证的连接是不安全的。这里把验证码交给界面，由用户在两台手机上核对后手动接受。
            _state.update { s ->
                s.copy(auth = AuthRequest(endpointId, info.endpointName, info.authenticationDigits), peerName = info.endpointName)
            }
            // 点对点策略只会有一个连接：停掉搜索 / 广播，减少无线电干扰
            try { client.stopDiscovery() } catch (_: Exception) {}
            try { client.stopAdvertising() } catch (_: Exception) {}
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.i(TAG, "已连接 $endpointId")
                    connectedEndpoint = endpointId
                    onConnected(endpointId)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    fail(appContext.getString(R.string.nc_err_rejected))
                else ->
                    fail(appContext.getString(R.string.nc_err_generic,
                        ConnectionsStatusCodes.getStatusCodeString(result.status.statusCode)))
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "连接断开 $endpointId")
            // 传完以后主动断开是正常的；中途断开才是错误
            if (!finished && _state.value.phase != Phase.ERROR && _state.value.phase != Phase.IDLE) {
                pendingSend.values.forEach { it.complete(false) }
                fail(appContext.getString(R.string.nc_err_disconnected))
            }
        }
    }

    private fun onConnected(endpointId: String) {
        TransferLocks.acquire(appContext, 30 * 60 * 1000L)
        lockHeld = true
        transferStartNs = System.nanoTime()
        _state.update { it.copy(phase = Phase.CONNECTED) }
        if (_state.value.role == Role.SENDER) {
            sendJob = scope.launch { sendAll(endpointId) }
        }
    }

    // ───────────────────────── 发送方 ─────────────────────────

    private suspend fun sendAll(endpointId: String) {
        val songs = sendQueue
        var ok = 0
        var failed = 0
        try {
            client.sendPayload(endpointId, Payload.fromBytes(
                JSONObject().put("t", "manifest").put("count", songs.size).put("totalBytes", totalBytes)
                    .toString().toByteArray()))

            for ((index, song) in songs.withIndex()) {
                if (finished) return
                val file = File(song.data)
                _state.update { it.copy(currentTitle = song.title) }

                val pfd = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
                          catch (e: Exception) { Log.w(TAG, "打不开 ${file.name}: ${e.message}"); failed++; continue }
                val payload = Payload.fromFile(pfd)
                val signal = CompletableDeferred<Boolean>()
                pendingSend[payload.id] = signal

                try {
                    // 先发这首歌的头信息（名字、大小等），再发文件本身
                    client.sendPayload(endpointId, Payload.fromBytes(
                        JSONObject().put("t", "file").put("pid", payload.id).put("filename", file.name)
                            .put("title", song.title).put("artist", song.artist).put("size", file.length())
                            .toString().toByteArray()))
                    client.sendPayload(endpointId, payload)

                    // 歌词很小，直接内嵌在一个 BYTES 载荷里，不用单独走文件
                    val lrc = File(file.parent, "${file.nameWithoutExtension}.lrc")
                    if (lrc.exists() && lrc.length() in 1..MAX_LRC_BYTES) {
                        try {
                            client.sendPayload(endpointId, Payload.fromBytes(
                                JSONObject().put("t", "lrc").put("filename", lrc.name)
                                    .put("text", lrc.readText()).toString().toByteArray()))
                        } catch (e: Exception) { Log.w(TAG, "歌词发送失败: ${e.message}") }
                    }

                    val success = signal.await()          // 这首歌传完（成功/失败）才发下一首
                    if (success) ok++ else failed++
                    _state.update { it.copy(progress = it.progress.copy(doneFiles = ok, failedFiles = failed)) }
                    Log.i(TAG, "第 ${index + 1}/${songs.size} 首 ${if (success) "成功" else "失败"}: ${file.name}")
                } finally {
                    pendingSend.remove(payload.id)
                    try { pfd.close() } catch (_: Exception) {}
                }
            }

            client.sendPayload(endpointId, Payload.fromBytes(JSONObject().put("t", "done").toString().toByteArray()))

            // 等接收方处理完（它还要把文件写进媒体库）并回一个确认；最多等 30 秒，等不到就用本地统计
            val deferred = CompletableDeferred<JSONObject?>().also { ackDeferred = it }
            val ack = withTimeoutOrNull(30_000) { deferred.await() }
            val finalOk = ack?.optInt("ok", ok) ?: ok
            val finalFailed = ack?.optInt("failed", failed) ?: failed
            finish(endpointId, finalOk, finalFailed)
        } catch (e: Exception) {
            Log.e(TAG, "发送出错: ${e.javaClass.simpleName}: ${e.message}", e)
            fail(e)
        }
    }

    // ───────────────────────── 载荷收发 ─────────────────────────

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val text = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
                    handleMessage(endpointId, text)
                }
                Payload.Type.FILE -> incomingPayloads[payload.id] = payload   // 开始收到第一个字节，不代表传完
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val pid = update.payloadId
            // 进度：按载荷累计字节（BYTES 的头信息不计入）
            if (incomingPayloads.containsKey(pid) || pendingSend.containsKey(pid)) {
                bytesByPayload[pid] = update.bytesTransferred
                emitProgress()
            }

            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return

            // ── 发送方：一首传完 ──
            pendingSend[pid]?.let { signal ->
                signal.complete(update.status == PayloadTransferUpdate.Status.SUCCESS)
                return
            }

            // ── 接收方：一个 FILE 载荷结束 ──
            val payload = incomingPayloads.remove(pid) ?: return
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                completedPayloads[pid] = payload
                tryFinalizeFile(pid)
            } else {
                Log.w(TAG, "接收失败 pid=$pid status=${update.status}")
                headers.remove(pid)
                failedFiles.incrementAndGet()
                checkReceiverDone(endpointId)
            }
        }
    }

    private fun handleMessage(endpointId: String, text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) { Log.w(TAG, "无法解析消息: $text"); return }
        when (json.optString("t")) {
            "manifest" -> {
                expectCount = json.optInt("count")
                totalBytes = json.optLong("totalBytes")
                Log.i(TAG, "收到清单: $expectCount 首，共 $totalBytes 字节")
                _state.update { it.copy(progress = it.progress.copy(totalFiles = expectCount, totalBytes = totalBytes)) }
            }
            "file" -> {
                val pid = json.optLong("pid")
                headers[pid] = json
                _state.update { it.copy(currentTitle = json.optString("title")) }
                tryFinalizeFile(pid)                  // FILE 可能比头信息先到
            }
            "lrc" -> scope.launch { saveLyrics(json.optString("filename"), json.optString("text")) }
            "done" -> {
                senderDone = true
                checkReceiverDone(endpointId)
            }
            "ack" -> ackDeferred?.complete(json)
        }
    }

    /** 头信息和文件都到齐了才处理：把收到的文件导入媒体库。 */
    private fun tryFinalizeFile(pid: Long) {
        val header = headers[pid] ?: return
        val payload = completedPayloads[pid] ?: return
        headers.remove(pid)
        completedPayloads.remove(pid)

        scope.launch {
            val filename = File(header.optString("filename")).name
            val ok = try { importReceivedFile(payload, filename) } catch (e: Exception) {
                Log.e(TAG, "导入失败 $filename: ${e.javaClass.simpleName}: ${e.message}", e); false
            }
            if (ok) doneFiles.incrementAndGet() else failedFiles.incrementAndGet()
            _state.update { it.copy(progress = it.progress.copy(doneFiles = doneFiles.get(), failedFiles = failedFiles.get())) }
            connectedEndpoint?.let { checkReceiverDone(it) }
        }
    }

    private fun checkReceiverDone(endpointId: String) {
        if (_state.value.role != Role.RECEIVER) return
        if (!senderDone || expectCount <= 0) return
        if (doneFiles.get() + failedFiles.get() < expectCount) return
        val ok = doneFiles.get(); val failed = failedFiles.get()
        try {
            client.sendPayload(endpointId, Payload.fromBytes(
                JSONObject().put("t", "ack").put("ok", ok).put("failed", failed).toString().toByteArray()))
        } catch (_: Exception) {}
        finish(endpointId, ok, failed)
    }

    // ───────────────────────── 接收后写入媒体库 ─────────────────────────

    private fun importReceivedFile(payload: Payload, filename: String): Boolean {
        val resolver = appContext.contentResolver
        val src: Uri = payload.asFile()?.asUri() ?: run {
            Log.e(TAG, "收到的文件没有 Uri: $filename"); return false
        }

        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = Environment.DIRECTORY_MUSIC + "/"

        // Music/ 里已有同名文件：视为已经有了，不重复导入（否则会生成 "xxx (1).flac"）
        var exists = false
        resolver.query(
            collection, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${MediaStore.Audio.Media.RELATIVE_PATH}=?",
            arrayOf(filename, relPath), null
        )?.use { c -> exists = c.moveToFirst() }
        if (exists) {
            Log.i(TAG, "Music/ 里已有 $filename，跳过")
            try { resolver.delete(src, null, null) } catch (_: Exception) {}
            return true
        }

        val ext = File(filename).extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val dest = resolver.insert(collection, values) ?: return false
        return try {
            val input = resolver.openInputStream(src) ?: throw java.io.IOException("打不开收到的临时文件")
            val raw = resolver.openOutputStream(dest) ?: throw java.io.IOException("无法写入 $dest")
            input.use { i ->
                // 和 HTTP 下载一样用 256KB 缓冲，避免小块写入穿过 FUSE 太慢
                BufferedOutputStream(raw, 256 * 1024).use { o -> i.copyTo(o, 256 * 1024) }
            }
            resolver.update(dest, android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "写入媒体库失败 $filename: ${e.message}")
            try { resolver.delete(dest, null, null) } catch (_: Exception) {}
            false
        } finally {
            // Nearby 把收到的文件放在 Downloads 里的临时位置，用完要删掉
            try { resolver.delete(src, null, null) } catch (_: Exception) {}
        }
    }

    private fun saveLyrics(name: String, text: String) {
        try {
            val safe = File(name).name
            if (safe.isBlank() || text.isBlank()) return
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).apply { mkdirs() }
            File(dir, safe).writeText(text)
        } catch (e: Exception) {
            // 没有"所有文件访问"权限时写不进 Music 目录，歌曲本身不受影响
            Log.w(TAG, "歌词保存失败（通常是没有文件写权限）: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ───────────────────────── 进度 / 结束 / 错误 ─────────────────────────

    private fun emitProgress(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastEmitNs < 100_000_000L) return   // 最多每 100ms 刷新一次
        lastEmitNs = now
        val bytes = bytesByPayload.values.sum()
        val elapsed = (now - transferStartNs) / 1e9
        val speed = if (elapsed > 0.5) (bytes / elapsed).toLong() else 0L
        _state.update { s ->
            s.copy(progress = s.progress.copy(bytes = bytes, totalBytes = totalBytes, speedBps = speed))
        }
    }

    private fun finish(endpointId: String, ok: Int, failed: Int) {
        if (finished) return
        finished = true
        emitProgress(force = true)
        val wall = (System.nanoTime() - transferStartNs) / 1e9
        val mb = bytesByPayload.values.sum() / 1048576.0
        Log.i(TAG, "完成: 成功 $ok 失败 $failed，${"%.1f".format(mb)}MB 用时 ${"%.1f".format(wall)}s 平均 ${"%.1f".format(if (wall > 0) mb / wall else 0.0)}MB/s")
        _state.update { it.copy(phase = Phase.DONE, okCount = ok, failedCount = failed) }
        releaseLock()
        // 接收方：新文件已经写进媒体库，扫一遍让歌曲库里出现它们
        if (_state.value.role == Role.RECEIVER && ok > 0) {
            scope.launch {
                try {
                    val prefs = appContext.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
                    val allowed = prefs.getStringSet("allowed_folders", setOf()) ?: setOf()
                    MusicUtils.syncLocalMusicToDatabase(appContext, AppDatabase.getDatabase(appContext).songDao(), allowed)
                } catch (e: Exception) { Log.w(TAG, "刷新歌曲库失败: ${e.message}") }
            }
        }
        scope.launch {
            delay(1000)    // 给对端一点时间收完最后的确认再断开
            try { client.disconnectFromEndpoint(endpointId) } catch (_: Exception) {}
        }
    }

    private fun fail(e: Exception) {
        Log.e(TAG, "出错: ${e.javaClass.simpleName}: ${e.message}")
        val msg = if (e is ApiException)
            appContext.getString(R.string.nc_err_generic, ConnectionsStatusCodes.getStatusCodeString(e.statusCode))
        else appContext.getString(R.string.nc_err_generic, e.message ?: e.javaClass.simpleName)
        fail(msg)
    }

    private fun fail(message: String) {
        Log.e(TAG, "失败: $message")
        finished = true
        _state.update { it.copy(phase = Phase.ERROR, error = message, auth = null) }
        try { client.stopAdvertising() } catch (_: Exception) {}
        try { client.stopDiscovery() } catch (_: Exception) {}
        try { client.stopAllEndpoints() } catch (_: Exception) {}
        releaseLock()
    }

    private fun releaseLock() {
        if (lockHeld) { lockHeld = false; TransferLocks.release() }
    }

    private fun resetInternal() {
        try { client.stopAdvertising() } catch (_: Exception) {}
        try { client.stopDiscovery() } catch (_: Exception) {}
        try { client.stopAllEndpoints() } catch (_: Exception) {}
        sendJob?.cancel(); sendJob = null
        pendingSend.values.forEach { it.complete(false) }
        pendingSend.clear()
        ackDeferred = null
        incomingPayloads.clear(); completedPayloads.clear(); headers.clear(); bytesByPayload.clear()
        connectedEndpoint = null
        sendQueue = emptyList()
        expectCount = 0; senderDone = false
        doneFiles.set(0); failedFiles.set(0)
        totalBytes = 0L; transferStartNs = 0L; lastEmitNs = 0L
        finished = false
        releaseLock()
    }
}

/** 运行时需要的权限，以及使用前的环境检查。按官方文档的清单，随系统版本不同而不同。 */
object NearbyConnectionsPermissions {
    fun required(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += android.Manifest.permission.BLUETOOTH_ADVERTISE
            list += android.Manifest.permission.BLUETOOTH_CONNECT
            list += android.Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT <= 31) list += android.Manifest.permission.ACCESS_FINE_LOCATION   // 文档：API 29–31
        if (Build.VERSION.SDK_INT >= 33) list += android.Manifest.permission.NEARBY_WIFI_DEVICES
        return list.toTypedArray()
    }

    fun missing(context: Context): List<String> = required().filter {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothEnabled(context: Context): Boolean = try {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        bm?.adapter?.isEnabled == true
    } catch (_: Exception) { true }   // 查询失败（缺权限等）就不拦着，让 Nearby 自己报错

    /** Android 12 及以下，发现附近设备要求系统的"定位"开关是打开的。 */
    fun locationServiceOk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT > 31) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return true
        return androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
    }

    fun playServicesAvailable(context: Context): Boolean = try {
        com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
    } catch (_: Exception) { false }
}
