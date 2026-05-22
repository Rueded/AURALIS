package com.auralis.app

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
    QQ("QQ音乐"),
    KUGOU("酷狗"),
    LRCLIB("LrcLib"),
    NONE("无歌词")
}

data class LyricsResult(
    val lines: List<LrcLine>,
    val source: LyricsSource,
    val rawLrc: String = ""
)

data class LyricCandidate(
    val platform: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val score: Double = 0.0,
    val previewLrc: String = ""
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
        return File(dir, "${audioPath.hashCode()}.lrc")
    }

    private fun readDiskCache(audioPath: String, context: Context): LyricsResult? {
        return try {
            val file = diskCacheFile(audioPath, context)
            if (!file.exists()) return null
            val raw = file.readText()
            if (raw.isBlank()) return null
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

    fun writeDiskCache(audioPath: String, result: LyricsResult, context: Context) {
        try {
            if (result.source == LyricsSource.NONE || result.rawLrc.isBlank()) return
            val file = diskCacheFile(audioPath, context)
            file.writeText("${result.source.name}\n${result.rawLrc}")
        } catch (e: Exception) {
            Log.w(TAG, "写磁盘歌词缓存失败：${e.message}")
        }
    }

    // ── 歌词黑名单 ────────────────────────────────────────────────────────────

    private fun lyricsFingerprint(source: LyricsSource, rawLrc: String): String =
        "${source.name}_${rawLrc.take(200).hashCode()}"

    private fun getBannedFingerprints(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences("lyrics_blacklist", Context.MODE_PRIVATE)
        return prefs.getStringSet("banned", mutableSetOf())!!.toMutableSet()
    }

    fun banCurrentLyrics(audioPath: String, context: Context) {
        val cached = memoryCache[audioPath] ?: run {
            readDiskCache(audioPath, context)
        } ?: return
        if (cached.source == LyricsSource.NONE || cached.rawLrc.isBlank()) return
        val fp = lyricsFingerprint(cached.source, cached.rawLrc)
        val prefs = context.getSharedPreferences("lyrics_blacklist", Context.MODE_PRIVATE)
        val banned = getBannedFingerprints(context)
        banned.add(fp)
        prefs.edit().putStringSet("banned", banned).apply()
        Log.d(TAG, "歌词已加入黑名单：$fp")
    }

    private fun isBanned(source: LyricsSource, rawLrc: String, context: Context): Boolean =
        getBannedFingerprints(context).contains(lyricsFingerprint(source, rawLrc))

    // ── 公开 API ──────────────────────────────────────────────────────────────

    suspend fun getLyrics(
        audioPath: String,
        title: String,
        artist: String,
        context: Context,
        forceOnline: Boolean = false
    ): LyricsResult = withContext(Dispatchers.IO) {

        if (!forceOnline) {
            val local = LrcParser.parse(audioPath)
            if (local.isNotEmpty()) {
                Log.d(TAG, "使用本地 LRC：$audioPath")
                return@withContext LyricsResult(local, LyricsSource.LOCAL)
            }
        }

        if (!forceOnline) {
            val disk = readDiskCache(audioPath, context)
            if (disk != null) {
                memoryCache[audioPath] = disk
                Log.d(TAG, "使用磁盘歌词缓存：$audioPath")
                return@withContext disk
            }
        }

        if (!forceOnline) {
            memoryCache[audioPath]?.let {
                if (it.source != LyricsSource.NONE) return@withContext it
            }
        }

        val keyword = buildKeyword(title, artist)
        Log.d(TAG, "联网搜索歌词：\"$keyword\"（audioPath=$audioPath）")

        val prefs = context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val sourcePref = prefs.getString("online_lyrics_source", "auto") ?: "auto"

        val searchOrder = when (sourcePref) {
            "163" -> listOf(LyricsSource.NETEASE, LyricsSource.QQ, LyricsSource.KUGOU, LyricsSource.LRCLIB)
            "qq" -> listOf(LyricsSource.QQ, LyricsSource.NETEASE, LyricsSource.KUGOU, LyricsSource.LRCLIB)
            "kugou" -> listOf(LyricsSource.KUGOU, LyricsSource.NETEASE, LyricsSource.QQ, LyricsSource.LRCLIB)
            "lrclib" -> listOf(LyricsSource.LRCLIB, LyricsSource.NETEASE, LyricsSource.QQ, LyricsSource.KUGOU)
            else -> listOf(LyricsSource.NETEASE, LyricsSource.QQ, LyricsSource.KUGOU, LyricsSource.LRCLIB)
        }

        var result: LyricsResult? = null
        for (src in searchOrder) {
            result = when (src) {
                LyricsSource.NETEASE -> fetchNeteaseLyrics(keyword, context)
                LyricsSource.QQ -> fetchQQLyrics(keyword, context)
                LyricsSource.KUGOU -> fetchKugouLyrics(keyword, context)
                LyricsSource.LRCLIB -> fetchLrcLibLyrics(keyword, context)
                else -> null
            }
            if (result != null && result.lines.isNotEmpty()) {
                break
            }
        }

        val finalResult = result ?: LyricsResult(emptyList(), LyricsSource.NONE)

        memoryCache[audioPath] = finalResult
        writeDiskCache(audioPath, finalResult, context)

        finalResult
    }

    fun clearCache(audioPath: String, context: Context) {
        memoryCache.remove(audioPath)
        try {
            diskCacheFile(audioPath, context).delete()
            Log.d(TAG, "已清除歌词缓存：$audioPath")
        } catch (e: Exception) {
            Log.w(TAG, "清除磁盘歌词缓存失败：${e.message}")
        }
    }

    suspend fun refreshFromNetwork(
        audioPath: String,
        title: String,
        artist: String,
        context: Context
    ): LyricsResult {
        clearCache(audioPath, context)
        return getLyrics(audioPath, title, artist, context, forceOnline = true)
    }

    fun updateCacheMemoryAndDisk(audioPath: String, result: LyricsResult, context: Context) {
        memoryCache[audioPath] = result
        writeDiskCache(audioPath, result, context)
    }

    // ── 网易云音乐 API ────────────────────────────────────────────────────────

    private fun fetchNeteaseLyrics(keyword: String, context: Context): LyricsResult? {
        return try {
            val candidates = NeteaseLyricsFetcher.searchCandidates(keyword, 0)
            if (candidates.isEmpty()) return null
            val best = candidates.first()
            val raw = NeteaseLyricsFetcher.fetchLyric(best.id) ?: return null
            if (isBanned(LyricsSource.NETEASE, raw, context)) return null
            val lines = LrcParser.parseRaw(raw)
            if (lines.isEmpty()) return null
            Log.d(TAG, "网易云歌词获取成功：${lines.size} 行")
            LyricsResult(lines, LyricsSource.NETEASE, raw)
        } catch (e: Exception) {
            Log.w(TAG, "网易云歌词失败：${e.message}")
            null
        }
    }

    // ── QQ音乐 API ──────────────────────────────────────────────────────────

    private fun fetchQQLyrics(keyword: String, context: Context): LyricsResult? {
        return try {
            val candidates = QQMusicLyricsFetcher.searchCandidates(keyword, 0)
            if (candidates.isEmpty()) return null
            val best = candidates.first()
            val raw = QQMusicLyricsFetcher.fetchLyric(best.id) ?: return null
            if (isBanned(LyricsSource.QQ, raw, context)) return null
            val lines = LrcParser.parseRaw(raw)
            if (lines.isEmpty()) return null
            Log.d(TAG, "QQ音乐歌词获取成功：${lines.size} 行")
            LyricsResult(lines, LyricsSource.QQ, raw)
        } catch (e: Exception) {
            Log.w(TAG, "QQ音乐歌词失败：${e.message}")
            null
        }
    }

    // ── 酷狗音乐 API ──────────────────────────────────────────────────────────

    private fun fetchKugouLyrics(keyword: String, context: Context): LyricsResult? {
        return try {
            val candidates = KuGouLyricsFetcher.searchCandidates(keyword, 0)
            if (candidates.isEmpty()) return null
            val best = candidates.first()
            val raw = KuGouLyricsFetcher.fetchLyric(best.id) ?: return null
            if (isBanned(LyricsSource.KUGOU, raw, context)) return null
            val lines = LrcParser.parseRaw(raw)
            if (lines.isEmpty()) return null
            Log.d(TAG, "酷狗歌词获取成功：${lines.size} 行")
            LyricsResult(lines, LyricsSource.KUGOU, raw)
        } catch (e: Exception) {
            Log.w(TAG, "酷狗歌词失败：${e.message}")
            null
        }
    }

    // ── LrcLib API ────────────────────────────────────────────────────────────

    private fun fetchLrcLibLyrics(keyword: String, context: Context): LyricsResult? {
        return try {
            val candidates = LrcLibLyricsFetcher.searchCandidates(keyword, 0)
            if (candidates.isEmpty()) return null
            val best = candidates.first()
            val raw = if (best.previewLrc.isNotEmpty()) best.previewLrc else LrcLibLyricsFetcher.fetchLyric(best.id) ?: return null
            if (isBanned(LyricsSource.LRCLIB, raw, context)) return null
            val lines = LrcParser.parseRaw(raw)
            if (lines.isEmpty()) return null
            Log.d(TAG, "LrcLib歌词获取成功：${lines.size} 行")
            LyricsResult(lines, LyricsSource.LRCLIB, raw)
        } catch (e: Exception) {
            Log.w(TAG, "LrcLib歌词失败：${e.message}")
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