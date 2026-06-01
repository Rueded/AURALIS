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
data class PushRequest(
    val senderName: String,
    val senderDeviceId: String,
    val senderIp: String,
    val songTitle: String,
    val artist: String,
    val filename: String,
    val hasLrc: Boolean,
    val coverBase64: String = ""
)

object AuralisPushManager {
    private val _incoming = MutableStateFlow<PushRequest?>(null)
    val incoming: StateFlow<PushRequest?> = _incoming.asStateFlow()

    fun post(req: PushRequest) { _incoming.value = req }
    fun consume() { _incoming.value = null }
}

class AuralisServer(
    private val context: Context,
    port: Int = PORT
) : NanoHTTPD(port) {

    companion object {
        const val PORT = 5001
        private const val TAG = "AuralisServer"
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
            "version"    to "7.5"
        )
        return jsonResponse(info)
    }

    // GET /auralis/list → 歌曲列表（与 SyncManager 兼容的 RemoteSong 格式）
    private fun handleList(): Response {
        val dao = AppDatabase.getDatabase(context).songDao()
        // 直接用阻塞查询（服务器线程）
        val songs = runCatching {
            kotlinx.coroutines.runBlocking { dao.getAllSongs().first() }
        }.getOrDefault(emptyList())

        val list = songs.map { song ->
            val file = File(song.data)
            val lrcFile = File(file.parent, "${file.nameWithoutExtension}.lrc")
            mapOf(
                "filename" to file.name,
                "size"     to file.length(),
                "md5"      to "",          // 保持兼容，可留空
                "has_lrc"  to lrcFile.exists()
            )
        }
        return jsonResponse(list)
    }

    // GET /auralis/download/:filename → 流式传输文件
    private fun handleDownload(filename: String): Response {
        val dao = AppDatabase.getDatabase(context).songDao()
        val songs = runCatching {
            kotlinx.coroutines.runBlocking { dao.getAllSongs().first() }
        }.getOrDefault(emptyList())

        val song = songs.firstOrNull { File(it.data).name == filename }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Song not found")

        // 同时支持下载配套 LRC
        val targetFile = if (filename.endsWith(".lrc")) {
            val audioFile = songs.firstOrNull {
                File(it.data).nameWithoutExtension == File(filename).nameWithoutExtension
            }?.data ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "LRC not found")
            val audio = File(audioFile)
            File(audio.parent, "${audio.nameWithoutExtension}.lrc")
        } else {
            File(song.data)
        }

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

        val stream = FileInputStream(targetFile)
        val response = newChunkedResponse(Response.Status.OK, mime, stream)
        response.addHeader("Content-Disposition", "attachment; filename=\"${targetFile.name}\"")
        response.addHeader("Content-Length", targetFile.length().toString())
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

        Log.d(TAG, "收到推送: ${req.senderName} → ${req.songTitle}")
        AuralisPushManager.post(req)

        return jsonResponse(mapOf("ok" to true))
    }

    private fun jsonResponse(data: Any): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(data)
        )
}