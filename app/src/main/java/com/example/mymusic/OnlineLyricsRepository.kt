package com.example.mymusic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class LyricsSource(val label: String) {
    LOCAL("本地"),
    NETEASE("网易云"),
    KUGOU("酷狗"),
    NONE("无歌词")
}

data class LyricsResult(
    val lines: List<LrcLine>,
    val source: LyricsSource,
    val rawLrc: String = ""
)

object OnlineLyricsRepository {

    private const val TAG = "OnlineLyricsRepo"
    private val memoryCache = ConcurrentHashMap<String, LyricsResult>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // 伪装成浏览器
    private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"

    suspend fun getLyrics(
        audioPath: String,
        title: String,
        artist: String,
        context: Context,
        forceOnline: Boolean = false
    ): LyricsResult = withContext(Dispatchers.IO) {

        // 1. 本地 LRC 优先
        if (!forceOnline) {
            val local = LrcParser.parse(audioPath)
            if (local.isNotEmpty()) return@withContext LyricsResult(local, LyricsSource.LOCAL)
        }

        val cacheKey = "${title}_${artist}"
        if (!forceOnline) {
            memoryCache[cacheKey]?.let { if (it.source != LyricsSource.NONE) return@withContext it }
        }

        val keyword = buildKeyword(title, artist)

        // 2. 网易云优先策略！
        val result = fetchNeteaseLyrics(keyword)
            ?: fetchKugouLyrics(keyword)
            ?: LyricsResult(emptyList(), LyricsSource.NONE)

        memoryCache[cacheKey] = result
        result
    }

    // ── 网易云音乐 API (第一优先级) ────────────────────────────
    private fun fetchNeteaseLyrics(keyword: String): LyricsResult? {
        return try {
            // Step 1: 搜索拿 ID
            val searchUrl = "https://music.163.com/api/search/get/web?csrf_token=hlpretag=&hlposttag=&s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&offset=0&total=true&limit=5"
            val headers = mapOf("User-Agent" to PC_UA, "Referer" to "https://music.163.com/")

            val searchResp = getJson(searchUrl, headers) ?: return null
            val songs = searchResp.optJSONObject("result")?.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null

            val songId = songs.getJSONObject(0).optLong("id")

            // Step 2: 拿歌词
            val lrcUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1"
            val lrcResp = getJson(lrcUrl, headers) ?: return null

            val raw = lrcResp.optJSONObject("lrc")?.optString("lyric") ?: return null
            val lines = LrcParser.parseRaw(raw)
            if (lines.isEmpty()) return null

            Log.d(TAG, "网易云歌词获取成功：${lines.size} 行")
            LyricsResult(lines, LyricsSource.NETEASE, raw)
        } catch (e: Exception) {
            Log.w(TAG, "网易云歌词失败: ${e.message}")
            null
        }
    }

    // ── 酷狗音乐 API (备选降级) ────────────────────────────
    private fun fetchKugouLyrics(keyword: String): LyricsResult? {
        return try {
            val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=${URLEncoder.encode(keyword, "UTF-8")}&page=1&pagesize=5&format=json"
            val searchResp = getJson(searchUrl) ?: return null
            val songs = searchResp.optJSONObject("data")?.optJSONArray("info") ?: return null
            if (songs.length() == 0) return null

            val song = songs.getJSONObject(0)
            val hash = song.optString("hash").takeIf { it.isNotEmpty() } ?: return null
            val duration = song.optLong("duration") * 1000

            val lrcSearchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${URLEncoder.encode(keyword, "UTF-8")}&hash=$hash&duration=$duration"
            val lrcSearchResp = getJson(lrcSearchUrl) ?: return null

            val cand = lrcSearchResp.optJSONArray("candidates")?.optJSONObject(0)
                ?: lrcSearchResp.optJSONArray("ugccandidates")?.optJSONObject(0) ?: return null

            val id = cand.optString("id")
            val accesskey = cand.optString("accesskey")

            val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8"
            val dlResp = getJson(dlUrl) ?: return null
            val rawB64 = dlResp.optString("content").takeIf { it.isNotEmpty() } ?: return null
            val raw = String(android.util.Base64.decode(rawB64, android.util.Base64.DEFAULT))

            val lines = LrcParser.parseRaw(raw)
            if (lines.isEmpty()) return null

            Log.d(TAG, "酷狗歌词获取成功：${lines.size} 行")
            LyricsResult(lines, LyricsSource.KUGOU, raw)
        } catch (e: Exception) {
            null
        }
    }

    private fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? {
        return try {
            val req = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return null
            JSONObject(body)
        } catch (e: Exception) { null }
    }

    private fun buildKeyword(title: String, artist: String): String {
        val t = title.trim()
        val a = artist.trim()
        return if (a.isEmpty() || a == "未知歌手") t else "$a $t"
    }
}