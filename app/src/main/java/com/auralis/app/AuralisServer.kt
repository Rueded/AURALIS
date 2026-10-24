package com.auralis.app

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream

// 收到对方推送的歌曲请求
// 一批里的单首歌
data class PushItem(
    val filename: String,
    val title: String,
    val artist: String,
    val size: Long,        // 字节数：接收方靠它算总进度，不依赖 HTTP 响应头
    val hasLrc: Boolean
)

data class PushRequest(
    val senderName: String,
    val senderDeviceId: String,
    val senderIp: String,
    // 下面这几项是"第一首歌"的信息，保留是为了兼容还没升级的旧版接收方（它们只认单曲字段）
    val songTitle: String,
    val artist: String,
    val filename: String,
    val hasLrc: Boolean,
    val coverBase64: String = "",
    // 新版：一次推送整批歌曲。旧版发送方没有这个字段，Gson 反序列化后会是 null，所以用可空类型
    val items: List<PushItem>? = null
) {
    /** 统一取歌曲列表：新版用 items，旧版退化成只有一首。 */
    fun allItems(): List<PushItem> =
        items?.takeIf { it.isNotEmpty() } ?: listOf(PushItem(filename, songTitle, artist, 0L, hasLrc))
}

object AuralisPushManager {
    // 以前这里只有一个槽位：对方一次选 3 首歌，推送 200ms 一条地来，后一条直接覆盖前一条，
    // 接收方最多只能看到最后一首；而且处理完第一首调用 consume() 还会把后面到的也清掉。
    // 现在改成队列：来一条排一条，处理完一条再弹下一条。
    private val queue = ArrayDeque<PushRequest>()
    private val _incoming = MutableStateFlow<PushRequest?>(null)
    val incoming: StateFlow<PushRequest?> = _incoming.asStateFlow()

    @Synchronized
    fun post(req: PushRequest) {
        fun same(a: PushRequest) = a.senderDeviceId == req.senderDeviceId &&
                a.allItems().map { it.filename } == req.allItems().map { it.filename }
        if (_incoming.value?.let(::same) == true || queue.any(::same)) return // 重复推送忽略
        queue.addLast(req)
        if (_incoming.value == null) _incoming.value = queue.removeFirstOrNull()
    }

    /** 当前这条处理完了（接收或拒绝），轮到队列里的下一条。 */
    @Synchronized
    fun consume() { _incoming.value = queue.removeFirstOrNull() }
}

/**
 * NanoHTTPD 2.3.1 在每个新连接的 HTTPSession 构造函数里（NanoHTTPD.java 第 652 行）调用 inetAddress.getHostName()，
 * 也就是对客户端 IP 做【反向 DNS 查询】。它发生在读取请求之前、在处理这个连接的线程里：
 * DNS 查询没返回，这个连接就一个字节都不会回应。
 *
 * 手机在热点 / Wi-Fi 下开着移动数据时，这个查询要经过移动数据的 DNS，对 192.168.x.x 这样的私有地址
 * 常常要等好几秒才超时；关掉移动数据则立刻失败。症状正好是：
 *   · 连接能建立，但请求"超时没有应答"（read_timeout）；
 *   · 只要开着 data 就连不上、关了 data 就正常；
 *   · 对 IPv6 全局地址反而很快（反向查询方式不同）；
 *   · 每个下载请求的开头都会卡一下，进度条"有时不动"；"一起听"每 1.5 秒一次的轮询也会被拖垮。
 *
 * 做法：让 accept() 返回的 Socket 自带主机名（直接用 IP 字符串），getHostName() 就不会再去查 DNS。
 * 我们并不使用客户端主机名，只用 remoteIpAddress。
 */
private class NoReverseDnsServerSocket : java.net.ServerSocket() {
    override fun accept(): java.net.Socket {
        if (isClosed) throw java.net.SocketException("Socket is closed")
        if (!isBound) throw java.net.SocketException("Socket is not bound yet")
        val s = object : java.net.Socket() {
            override fun getInetAddress(): java.net.InetAddress? {
                val a = super.getInetAddress() ?: return null
                return try {
                    java.net.InetAddress.getByAddress(a.hostAddress, a.address)
                } catch (_: Exception) { a }
            }
        }
        implAccept(s)
        return s
    }
}

class AuralisServer(
    private val context: Context,
    port: Int = PORT
) : NanoHTTPD(port) {

    init {
        setServerSocketFactory(NanoHTTPD.ServerSocketFactory { NoReverseDnsServerSocket() })
    }

    companion object {
        const val PORT = 5001
        private const val TAG = "AuralisServer"

        @Volatile private var instance: AuralisServer? = null

        // 允许被下载的文件白名单：文件名 → 绝对路径 + 过期时间。
        // 以前 /auralis/download 可以按文件名下载整个音乐库里的任何歌，同一个 Wi-Fi 里任何设备都能拉；
        // 而且每个请求都要 getAllSongs() 把整张歌曲表读出来再逐个比文件名，歌多的时候很慢。
        // 现在只有"用户刚主动推送给对方"的歌才能下载，查表是 O(1)，不碰数据库。
        private class Offered(val path: String, val expiresAt: Long)
        private val offered = java.util.concurrent.ConcurrentHashMap<String, Offered>()
        private const val OFFER_TTL_MS = 30 * 60 * 1000L

        /** 发送前调用：登记这批歌（以及同名 .lrc）允许在接下来 30 分钟内被对方下载。 */
        fun offer(context: Context, audioPaths: List<String>) {
            // 发送方这边也要保持 CPU/Wi-Fi 不休眠（10 分钟兜底自动释放），
            // 否则对方确认前我这边息屏了，传输会很慢甚至卡住
            TransferLocks.acquire(context, 10 * 60 * 1000L)
            val now = System.currentTimeMillis()
            val expire = now + OFFER_TTL_MS
            offered.entries.removeIf { it.value.expiresAt < now }
            audioPaths.forEach { p ->
                val f = File(p)
                if (!f.exists()) return@forEach
                offered[f.name] = Offered(f.absolutePath, expire)
                val lrc = File(f.parent, "${f.nameWithoutExtension}.lrc")
                if (lrc.exists()) offered[lrc.name] = Offered(lrc.absolutePath, expire)
            }
        }

        /**
         * 只保证有一个服务器在跑。以前每次 MusicAppScreen 重新进入组合（切语言/旋转/Activity 重建）
         * 都会 new 一个新服务器去绑 5001，旧的还占着端口 → BindException，
         * 而它后面的 NsdHelper.register / startDiscovery 也被异常跳过了。
         */
        @Synchronized
        fun ensureStarted(context: Context) {
            val cur = instance
            if (cur != null && cur.isAlive) return
            val s = AuralisServer(context.applicationContext) // 用 applicationContext，别抓着 Activity 不放
            s.start()
            instance = s
            Log.i(TAG, "AuralisServer 已启动，端口 $PORT")
        }
    }

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
                uri == "/auralis/info" && method == Method.GET ->
                    handleInfo(session)

                uri == "/auralis/list" && method == Method.GET ->
                    handleList()

                uri.startsWith("/auralis/download/") && method == Method.GET ->
                    handleDownload(uri.removePrefix("/auralis/download/"))

                uri == "/auralis/push" && method == Method.POST ->
                    handlePush(session)

                uri == "/auralis/room/state" && method == Method.GET ->
                    handleRoomState()

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error"
            )
        }
    }

    // GET /auralis/info → 设备信息
    private fun handleInfo(session: IHTTPSession): Response {
        val info = mapOf(
            "deviceId"   to AuralisDeviceId.getId(context),
            "deviceName" to AuralisDeviceId.getName(context),
            "version"    to "7.6"
        )
        return jsonResponse(info)
    }

    // GET /auralis/list → 当前"已登记可下载"的歌曲（不再暴露整个音乐库）
    private fun handleList(): Response {
        val now = System.currentTimeMillis()
        val list = offered.entries
            .filter { it.value.expiresAt > now && !it.key.endsWith(".lrc", ignoreCase = true) }
            .map { (name, o) ->
                mapOf(
                    "filename" to name,
                    "size"     to File(o.path).length(),
                    "md5"      to "",
                    "has_lrc"  to offered.containsKey(File(name).nameWithoutExtension + ".lrc")
                )
            }
        return jsonResponse(list)
    }

    // GET /auralis/download/:filename → 流式传输文件（仅限已登记的文件）
    private fun handleDownload(filename: String): Response {
        val entry = offered[filename]
        if (entry == null || entry.expiresAt < System.currentTimeMillis()) {
            Log.w(TAG, "拒绝下载未登记/已过期的文件: $filename")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not offered")
        }
        val targetFile = File(entry.path)

        if (!targetFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found on disk")
        }

        val mime = when (targetFile.extension.lowercase()) {
            "mp3"  -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav"  -> "audio/wav"
            "aac"  -> "audio/aac"
            "m4a"  -> "audio/mp4"
            "ogg"  -> "audio/ogg"
            "lrc"  -> "text/plain"
            else   -> "application/octet-stream"
        }

        // 以前用 newChunkedResponse 再手动加 Content-Length：分块传输和 Content-Length 同时出现，
        // 不合规；OkHttp 会以分块为准，于是 contentLength() = -1，接收方的进度条永远是 0%。
        // 文件大小是已知的，直接用定长响应。
        // NanoHTTPD 每次只从流里读 16KB；包一层 256KB 的缓冲，磁盘读取次数少 16 倍，弱一点的手机上 CPU 占用明显更低
        val stream = java.io.BufferedInputStream(FileInputStream(targetFile), 256 * 1024)
        val response = newFixedLengthResponse(Response.Status.OK, mime, stream, targetFile.length())
        val encodedName = java.net.URLEncoder.encode(targetFile.name, "UTF-8").replace("+", "%20")
        response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
        return response
    }

    // POST /auralis/push → 收到对方推送
    // Body JSON: { senderName, senderDeviceId, songTitle, artist, filename, hasLrc, coverBase64? }
    private fun handlePush(session: IHTTPSession): Response {
        // 检查接收开关
        val prefs = context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auralis_receive_enabled", true)) {
            return jsonResponse(mapOf("ok" to false, "reason" to "receiver_disabled"))
        }

        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = body["postData"] ?: body.values.firstOrNull() ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Empty body"
        )

        val req = try {
            gson.fromJson(json, PushRequest::class.java).copy(
                senderIp = session.remoteIpAddress ?: ""
            )
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad JSON")
        }

        if (req.allItems().size > 1000) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Too many items")
        }
        Log.d(TAG, "收到推送: ${req.senderName} → ${req.songTitle} 等 ${req.allItems().size} 首")
        AuralisPushManager.post(req)

        return jsonResponse(mapOf("ok" to true))
    }

    // GET /auralis/room/state → “一起听歌”：本机作为房主时，把当前播放状态原样吐出去
    private fun handleRoomState(): Response {
        val state = RoomManager.hostState.value
        return if (RoomManager.isHosting.value && state != null) {
            jsonResponse(state)
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not_hosting")
        }
    }

    private fun jsonResponse(data: Any): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(data)
        )
}