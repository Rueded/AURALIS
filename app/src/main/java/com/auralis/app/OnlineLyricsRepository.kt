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
    KUGOU("酷狗"),
    KUWOU("酷我"),
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

    // ── 歌词黑名单（用户主动删除的歌词，刷新时不再加载）────────────────────────────

    /** 生成一条歌词记录的唯一指纹（source + rawLrc 前200字符 hash）*/
    private fun lyricsFingerprint(source: LyricsSource, rawLrc: String): String =
        "${source.name}_${rawLrc.take(200).hashCode()}"

    private fun getBannedFingerprints(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences("lyrics_blacklist", Context.MODE_PRIVATE)
        return prefs.getStringSet("banned", mutableSetOf())!!.toMutableSet()
    }

    /**
     * 把当前缓存的歌词加入黑名单，刷新时跳过。
     * 在用户点「垃圾桶」时调用（先于 clearCache）。
     */
    fun banCurrentLyrics(audioPath: String, context: Context) {
        val cached = memoryCache[audioPath] ?: run {
            // 尝试读磁盘缓存
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

    /** 判断某条 rawLrc 是否在黑名单中 */
    private fun isBanned(source: LyricsSource, rawLrc: String, context: Context): Boolean =
        getBannedFingerprints(context).contains(lyricsFingerprint(source, rawLrc))

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

        val result = fetchNeteaseLyrics(keyword, context)
            ?: fetchKugouLyrics(keyword, context)
            ?: fetchKuwouLyrics(keyword, context)
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

    private fun fetchNeteaseLyrics(keyword: String, context: Context): LyricsResult? {
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

            // Try up to 5 candidates, skip blacklisted ones
            for (i in 0 until songs.length()) {
                val songId = songs.getJSONObject(i).optLong("id")

                val lrcUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1"
                val lrcResp = getJson(lrcUrl, headers) ?: continue

                val raw = lrcResp.optJSONObject("lrc")?.optString("lyric") ?: continue
                val lines = LrcParser.parseRaw(raw)
                if (lines.isEmpty()) continue

                if (isBanned(LyricsSource.NETEASE, raw, context)) {
                    Log.d(TAG, "网易云：跳过黑名单歌词 (候选 $i)")
                    continue
                }

                Log.d(TAG, "网易云歌词获取成功：${lines.size} 行（候选 $i）")
                return LyricsResult(lines, LyricsSource.NETEASE, raw)
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "网易云歌词失败：${e.message}")
            null
        }
    }

    // ── 酷狗音乐 API ──────────────────────────────────────────────────────────

    private fun fetchKugouLyrics(keyword: String, context: Context): LyricsResult? {
        return try {
            val searchUrl =
                "https://msearch.kugou.com/api/v3/search/song?keyword=" +
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

            if (isBanned(LyricsSource.KUGOU, raw, context)) {
                Log.d(TAG, "酷狗：歌词在黑名单，跳过")
                return null
            }

            val lines = LrcParser.parseRaw(raw)
            if (lines.isEmpty()) return null

            Log.d(TAG, "酷狗歌词获取成功：${lines.size} 行")
            LyricsResult(lines, LyricsSource.KUGOU, raw)
        } catch (e: Exception) {
            Log.w(TAG, "酷狗歌词失败：${e.message}")
            null
        }
    }

    private fun fetchKuwouLyrics(keyword: String, context: Context): LyricsResult? {
        return try {
            val searchUrl = "https://search.kuwo.cn/r.s?all=${URLEncoder.encode(keyword, "UTF-8")}" +
                "&ft=music&newsearch=1&itemset=web_2013&client=kt&cluster=0&vermerge=1&mobi=1" +
                "&issubtitle=1&show_copyright_off=1&pcmp4=1&newver=1&type=convert_url&format=json&count=5"
            val searchResp = getJson(searchUrl, mapOf("User-Agent" to PC_UA)) ?: return null
            val musicId = searchResp.optJSONArray("abslist")
                ?.optJSONObject(0)?.optString("MUSICRID")
                ?.replace("MUSIC_", "") ?: return null

            val lrcUrl = "https://m.kuwo.cn/newh5app/api/pc/lyric/mutil?musicIds=$musicId&type=lrc"
            val lrcBody = getJson(lrcUrl) ?: return null
            val raw = lrcBody.optJSONArray("data")
                ?.optJSONObject(0)?.optString("lrclist") ?: return null

            if (isBanned(LyricsSource.KUWOU, raw, context)) return null
            val lines = LrcParser.parseRaw(raw)
            if (lines.isEmpty()) return null

            Log.d(TAG, "酷我歌词获取成功：${lines.size} 行")
            LyricsResult(lines, LyricsSource.KUWOU, raw)
        } catch (e: Exception) {
            Log.w(TAG, "酷我歌词失败：${e.message}")
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