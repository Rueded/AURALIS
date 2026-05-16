package com.example.mymusic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // 💡 修复1：内存缓存 key 改为 audioPath，避免同名歌曲串歌词
    private val memoryCache = ConcurrentHashMap<String, LyricsResult>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private const val PC_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"

    // ── 磁盘缓存路径 ──────────────────────────────────────────────────────────

    // 💡 修复2：用 context.cacheDir（internal），卸载 app 时自动清除
    private fun diskCacheFile(audioPath: String, context: Context): File {
        val dir = File(context.cacheDir, "lyrics_cache").also { it.mkdirs() }
        // key = audioPath 的 hash，避免文件名包含非法字符
        return File(dir, "${audioPath.hashCode()}.lrc")
    }

    private fun readDiskCache(audioPath: String, context: Context): LyricsResult? {
        return try {
            val file = diskCacheFile(audioPath, context)
            if (!file.exists()) return null
            val raw = file.readText()
            if (raw.isBlank()) return null
            // 第一行存 source 枚举名，其余是原始 LRC
            val lines = raw.lines()
            val source = LyricsSource.entries.firstOrNull { it.name == lines[0] }
                ?: LyricsSource.NETEASE
            val lrcRaw = lines.drop(1).joinToString("\n")
            val parsed = LrcParser.parseRaw(lrcRaw)
            if (parsed.isEmpty()) null
            else LyricsResult(parsed, source, lrcRaw)
        } catch (e: Exception) {
            Log.w(TAG, "读磁盘歌词缓存失败：${e.message}")
            null
        }
    }

    private fun writeDiskCache(audioPath: String, result: LyricsResult, context: Context) {
        try {
            if (result.source == LyricsSource.NONE || result.rawLrc.isBlank()) return
            val file = diskCacheFile(audioPath, context)
            // 第一行写 source 枚举名，方便反序列化
            file.writeText("${result.source.name}\n${result.rawLrc}")
        } catch (e: Exception) {
            Log.w(TAG, "写磁盘歌词缓存失败：${e.message}")
        }
    }

    // ── 公开 API ──────────────────────────────────────────────────────────────

    /**
     * 获取歌词。优先级：本地 LRC → 磁盘缓存 → 内存缓存 → 网络
     *
     * @param forceOnline 强制重新联网拉取（用于"刷新"按钮）
     */
    suspend fun getLyrics(
        audioPath: String,
        title: String,
        artist: String,
        context: Context,
        forceOnline: Boolean = false
    ): LyricsResult = withContext(Dispatchers.IO) {

        // 1. 本地 LRC 文件优先（不受 forceOnline 影响，本地永远优先）
        if (!forceOnline) {
            val local = LrcParser.parse(audioPath)
            if (local.isNotEmpty()) {
                Log.d(TAG, "使用本地 LRC：$audioPath")
                return@withContext LyricsResult(local, LyricsSource.LOCAL)
            }
        }

        // 2. 磁盘缓存（非强制刷新时读取）
        if (!forceOnline) {
            val disk = readDiskCache(audioPath, context)
            if (disk != null) {
                memoryCache[audioPath] = disk
                Log.d(TAG, "使用磁盘歌词缓存：$audioPath")
                return@withContext disk
            }
        }

        // 3. 内存缓存（非强制刷新时读取）
        if (!forceOnline) {
            memoryCache[audioPath]?.let {
                if (it.source != LyricsSource.NONE) return@withContext it
            }
        }

        // 4. 联网获取
        val keyword = buildKeyword(title, artist)
        Log.d(TAG, "联网搜索歌词：\"$keyword\"（audioPath=$audioPath）")

        val result = fetchNeteaseLyrics(keyword)
            ?: fetchKugouLyrics(keyword)
            ?: LyricsResult(emptyList(), LyricsSource.NONE)

        // 写回缓存
        memoryCache[audioPath] = result
        writeDiskCache(audioPath, result, context)

        result
    }

    /**
     * 删除指定歌曲的所有歌词缓存（用于"删除错误歌词"按钮）。
     */
    fun clearCache(audioPath: String, context: Context) {
        memoryCache.remove(audioPath)
        try {
            diskCacheFile(audioPath, context).delete()
            Log.d(TAG, "已清除歌词缓存：$audioPath")
        } catch (e: Exception) {
            Log.w(TAG, "清除磁盘歌词缓存失败：${e.message}")
        }
    }

    /**
     * 强制重新联网获取并覆盖缓存。
     * UI 侧"刷新"按钮直接调用这个即可。
     */
    suspend fun refreshFromNetwork(
        audioPath: String,
        title: String,
        artist: String,
        context: Context
    ): LyricsResult {
        clearCache(audioPath, context)
        return getLyrics(audioPath, title, artist, context, forceOnline = true)
    }

    // ── 网易云音乐 API ────────────────────────────────────────────────────────

    private fun fetchNeteaseLyrics(keyword: String): LyricsResult? {
        return try {
            val searchUrl =
                "https://music.163.com/api/search/get/web?csrf_token=hlpretag=&hlposttag=&s=" +
                        "${URLEncoder.encode(keyword, "UTF-8")}&type=1&offset=0&total=true&limit=5"
            val headers = mapOf(
                "User-Agent" to PC_UA,
                "Referer"    to "https://music.163.com/"
            )

            val searchResp = getJson(searchUrl, headers) ?: return null
            val songs = searchResp.optJSONObject("result")?.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null

            val songId = songs.getJSONObject(0).optLong("id")

            val lrcUrl =
                "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1"
            val lrcResp = getJson(lrcUrl, headers) ?: return null

            val raw = lrcResp.optJSONObject("lrc")?.optString("lyric") ?: return null
            val lines = LrcParser.parseRaw(raw)
            if (lines.isEmpty()) return null

            Log.d(TAG, "网易云歌词获取成功：${lines.size} 行")
            LyricsResult(lines, LyricsSource.NETEASE, raw)
        } catch (e: Exception) {
            Log.w(TAG, "网易云歌词失败：${e.message}")
            null
        }
    }

    // ── 酷狗音乐 API ──────────────────────────────────────────────────────────

    private fun fetchKugouLyrics(keyword: String): LyricsResult? {
        return try {
            val searchUrl =
                "http://mobilecdn.kugou.com/api/v3/search/song?keyword=" +
                        "${URLEncoder.encode(keyword, "UTF-8")}&page=1&pagesize=5&format=json"
            val searchResp = getJson(searchUrl) ?: return null
            val songs =
                searchResp.optJSONObject("data")?.optJSONArray("info") ?: return null
            if (songs.length() == 0) return null

            val song     = songs.getJSONObject(0)
            val hash     = song.optString("hash").takeIf { it.isNotEmpty() } ?: return null
            val duration = song.optLong("duration") * 1000

            val lrcSearchUrl =
                "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" +
                        "${URLEncoder.encode(keyword, "UTF-8")}&hash=$hash&duration=$duration"
            val lrcSearchResp = getJson(lrcSearchUrl) ?: return null

            val cand =
                lrcSearchResp.optJSONArray("candidates")?.optJSONObject(0)
                    ?: lrcSearchResp.optJSONArray("ugccandidates")?.optJSONObject(0)
                    ?: return null

            val id        = cand.optString("id")
            val accesskey = cand.optString("accesskey")

            val dlUrl =
                "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8"
            val dlResp  = getJson(dlUrl) ?: return null
            val rawB64  = dlResp.optString("content").takeIf { it.isNotEmpty() } ?: return null
            val raw     =
                String(android.util.Base64.decode(rawB64, android.util.Base64.DEFAULT))

            val lines = LrcParser.parseRaw(raw)
            if (lines.isEmpty()) return null

            Log.d(TAG, "酷狗歌词获取成功：${lines.size} 行")
            LyricsResult(lines, LyricsSource.KUGOU, raw)
        } catch (e: Exception) {
            Log.w(TAG, "酷狗歌词失败：${e.message}")
            null
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? {
        return try {
            val req = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            val body = client.newCall(req).execute().use { it.body?.string() }
                ?: return null
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildKeyword(title: String, artist: String): String {
        val t = title.trim()
        val a = artist.trim()
        return if (a.isEmpty() || a == "未知歌手") t else "$a $t"
    }
}