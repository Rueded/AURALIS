package com.auralis.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class CoverCandidate(
    val title: String,
    val artist: String,
    val album: String,
    val imageUrl: String,
    val platform: String
)

object CoverFetcher {
    private const val TAG = "CoverFetcher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // 🏆 高阶 PC 浏览器仿真 User-Agent
    private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun normalize(s: String): String {
        return s.lowercase()
            .replace(Regex("[\\s\\-_()（）【】「」『』\\[\\]]"), "")
            .trim()
    }

    private fun isMatch(queryTitle: String, queryArtist: String, resultTitle: String, resultArtist: String): Boolean {
        val qTitle = normalize(queryTitle)
        val rTitle = normalize(resultTitle)
        val qArtist = normalize(queryArtist)
        val rArtist = normalize(resultArtist)

        if (qTitle.isEmpty() || rTitle.isEmpty()) return false
        val titleMatch = qTitle.contains(rTitle) || rTitle.contains(qTitle)

        val isUnknownArtist = qArtist.contains("未知歌手") || qArtist.isEmpty()
        val artistMatch = isUnknownArtist || qArtist.contains(rArtist) || rArtist.contains(qArtist) ||
                qArtist.split(Regex("[/&,\\s]+")).any { q -> q.isNotEmpty() && rArtist.contains(q) } ||
                rArtist.split(Regex("[/&,\\s]+")).any { r -> r.isNotEmpty() && qArtist.contains(r) }

        return titleMatch && artistMatch
    }

    /**
     * 智能获取高清封面：网易云全量对齐免风控安全标准优先
     */
    suspend fun fetchHighResCover(title: String, artist: String): Bitmap? = withContext(Dispatchers.IO) {
        val keyword = buildKeyword(title, artist)

        // 🏆 第一顺位最高优先级：网易云音乐 (全量同步歌词抓取专属免风控凭证)
        try {
            val neteaseUrl = "https://music.163.com/api/search/get?s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&limit=5"
            val req = Request.Builder()
                .url(neteaseUrl)
                .addHeader("User-Agent", PC_UA)
                .addHeader("Referer", "https://music.163.com/")
                // ✨ 完美对齐歌词免限 Cookie 标准
                .addHeader("Cookie", "os=pc; appver=2.9.7; osver=Microsoft-Windows-10-Professional-build-19045-64bit; channel=netease;")
                .build()
            val body = client.newCall(req).execute().body?.string()
            if (body != null) {
                val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
                if (songs != null) {
                    for (i in 0 until songs.length()) {
                        val song = songs.getJSONObject(i)
                        val trackName = song.optString("name")
                        val artistsArr = song.optJSONArray("artists")
                        val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                            artistsArr.optJSONObject(0)?.optString("name") ?: ""
                        } else ""

                        if (isMatch(title, artist, trackName, artistName)) {
                            var picUrl = song.optJSONObject("album")?.optString("picUrl")
                            if (!picUrl.isNullOrEmpty()) {
                                if (picUrl.startsWith("http://")) picUrl = picUrl.replace("http://", "https://")
                                picUrl = if (picUrl.contains("?")) "$picUrl&param=800y800" else "$picUrl?param=800y800"
                                val bitmap = downloadBitmap(picUrl)
                                if (bitmap != null) {
                                    Log.d(TAG, "☁️ 采用免风控标准从网易云成功抓取高清封面！")
                                    return@withContext bitmap
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "网易云优先封面检索出错: ${e.message}")
        }

        // 🥈 第二顺位优先级：QQ音乐 (同步身份校验凭证)
        try {
            val qqUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${URLEncoder.encode(keyword, "UTF-8")}&p=1&n=5&format=json"
            val req = Request.Builder()
                .url(qqUrl)
                .addHeader("User-Agent", PC_UA)
                // ✨ 注入专属身份效验跟踪 Cookie 绕过空凭证风控
                .addHeader("Cookie", "pgv_pvi=22038528; pgv_si=s3156287488; os_name=windows;")
                .build()
            val body = client.newCall(req).execute().body?.string()
            if (body != null) {
                val list = JSONObject(body).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                if (list != null) {
                    for (i in 0 until list.length()) {
                        val song = list.getJSONObject(i)
                        val trackName = song.optString("songname")
                        val artistName = song.optJSONArray("singer")?.optJSONObject(0)?.optString("name") ?: ""

                        if (isMatch(title, artist, trackName, artistName)) {
                            val albumId = song.optString("albummid")
                            if (albumId.isNotEmpty()) {
                                val imgUrl = "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumId.jpg"
                                val bitmap = downloadBitmap(imgUrl)
                                if (bitmap != null) {
                                    Log.d(TAG, "🐧 从 QQ音乐 成功匹配并拉取高清封面！")
                                    return@withContext bitmap
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "QQ音乐封面检索失败: ${e.message}")
        }

        // 🥉 第三顺位及更后优先级：海外综合库 (iTunes 平台)
        try {
            val itunesUrl = "https://itunes.apple.com/search?term=${URLEncoder.encode(keyword, "UTF-8")}&media=music&entity=song&limit=5"
            val req = Request.Builder().url(itunesUrl).build()
            val body = client.newCall(req).execute().body?.string()
            if (body != null) {
                val results = JSONObject(body).optJSONArray("results")
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val trackName = item.optString("trackName")
                        val artistName = item.optString("artistName")
                        if (isMatch(title, artist, trackName, artistName)) {
                            val artwork100 = item.optString("artworkUrl100")
                            if (artwork100.isNotEmpty()) {
                                val artwork800 = artwork100.replace("100x100bb", "800x800bb")
                                val bitmap = downloadBitmap(artwork800)
                                if (bitmap != null) return@withContext bitmap
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        // 4️⃣ 第四顺位：Deezer 欧美库解析
        try {
            val deezerUrl = "https://api.deezer.com/search?q=${URLEncoder.encode(keyword, "UTF-8")}&limit=5"
            val req = Request.Builder().url(deezerUrl).build()
            val body = client.newCall(req).execute().body?.string()
            if (body != null) {
                val data = JSONObject(body).optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        if (isMatch(title, artist, item.optString("title"), item.optJSONObject("artist")?.optString("name") ?: "")) {
                            val coverXl = item.optJSONObject("album")?.optString("cover_xl")
                            if (!coverXl.isNullOrEmpty()) {
                                val bitmap = downloadBitmap(coverXl)
                                if (bitmap != null) return@withContext bitmap
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        // 5️⃣ 第五顺位：MusicBrainz 国际实体库 (补充合法合规联系头，防止 403)
        try {
            val mbUrl = "https://musicbrainz.org/ws/2/recording/?query=${URLEncoder.encode(keyword, "UTF-8")}&limit=5&fmt=json"
            val req = Request.Builder()
                .url(mbUrl)
                // ✨ 补充合规核心联系人邮箱，绕过 403 封锁
                .addHeader("User-Agent", "Auralis/1.0 (auralis_dev@gmail.com)")
                .build()
            val body = client.newCall(req).execute().body?.string()
            if (body != null) {
                val recordings = JSONObject(body).optJSONArray("recordings")
                if (recordings != null && recordings.length() > 0) {
                    val rec = recordings.optJSONObject(0)
                    val releaseId = rec?.optJSONArray("releases")?.optJSONObject(0)?.optString("id")
                    if (!releaseId.isNullOrEmpty()) {
                        val bitmap = downloadBitmap("https://coverartarchive.org/release/$releaseId/front-500")
                        if (bitmap != null) return@withContext bitmap
                    }
                }
            }
        } catch (e: Exception) {}

        Log.d(TAG, "❌ 所有公共网络封面存储均无匹配")
        return@withContext null
    }

    suspend fun searchCoverCandidates(title: String, artist: String): List<CoverCandidate> = coroutineScope {
        val keyword = buildKeyword(title, artist)
        val encodedKw = URLEncoder.encode(keyword, "UTF-8")

        val deferredQQ = async(Dispatchers.IO) {
            val candidates = mutableListOf<CoverCandidate>()
            try {
                val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$encodedKw&p=1&n=5&format=json"
                val req = Request.Builder().url(url).addHeader("User-Agent", PC_UA).addHeader("Cookie", "pgv_pvi=22038528; pgv_si=s3156287488; os_name=windows;").build()
                val body = client.newCall(req).execute().body?.string()
                if (body != null) {
                    val list = JSONObject(body).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            val song = list.getJSONObject(i)
                            val albumId = song.optString("albummid")
                            if (albumId.isNotEmpty()) {
                                val t = song.optString("songname")
                                val a = song.optJSONArray("singer")?.optJSONObject(0)?.optString("name") ?: ""
                                val albumName = song.optString("albumname")
                                val imgUrl = "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumId.jpg"
                                candidates.add(CoverCandidate(t, a, albumName, imgUrl, "QQ音乐"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            candidates
        }

        val deferredNetease = async(Dispatchers.IO) {
            val candidates = mutableListOf<CoverCandidate>()
            try {
                val url = "https://music.163.com/api/search/get/web?s=$encodedKw&type=1&limit=5"
                val req = Request.Builder().url(url).addHeader("User-Agent", PC_UA).addHeader("Referer", "https://music.163.com/").addHeader("Cookie", "os=pc; appver=2.9.7; osver=Microsoft-Windows-10-Professional-build-19045-64bit; channel=netease;").build()
                val body = client.newCall(req).execute().body?.string()
                if (body != null) {
                    val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
                    if (songs != null) {
                        for (i in 0 until songs.length()) {
                            val song = songs.getJSONObject(i)
                            val t = song.optString("name")
                            val a = song.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
                            val album = song.optJSONObject("album")
                            var picUrl = album?.optString("picUrl")
                            if (!picUrl.isNullOrEmpty()) {
                                picUrl = if (picUrl.contains("?")) "$picUrl&param=500y500" else "$picUrl?param=500y500"
                                candidates.add(CoverCandidate(t, a, album?.optString("name") ?: "", picUrl.replace("http://", "https://"), "网易云"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            candidates
        }

        val deferredItunes = async(Dispatchers.IO) {
            val candidates = mutableListOf<CoverCandidate>()
            try {
                val url = "https://itunes.apple.com/search?term=$encodedKw&media=music&entity=song&limit=5"
                val req = Request.Builder().url(url).build()
                val body = client.newCall(req).execute().body?.string()
                if (body != null) {
                    val results = JSONObject(body).optJSONArray("results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val item = results.getJSONObject(i)
                            val t = item.optString("trackName")
                            val a = item.optString("artistName")
                            val album = item.optString("collectionName")
                            val artwork100 = item.optString("artworkUrl100")
                            if (artwork100.isNotEmpty()) {
                                candidates.add(CoverCandidate(t, a, album, artwork100.replace("100x100bb", "800x800bb"), "iTunes"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            candidates
        }

        val deferredDeezer = async(Dispatchers.IO) {
            val candidates = mutableListOf<CoverCandidate>()
            try {
                val url = "https://api.deezer.com/search?q=$encodedKw&limit=5"
                val req = Request.Builder().url(url).build()
                val body = client.newCall(req).execute().body?.string()
                if (body != null) {
                    val data = JSONObject(body).optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)
                            val t = item.optString("title")
                            val a = item.optJSONObject("artist")?.optString("name") ?: ""
                            val albumObj = item.optJSONObject("album")
                            val cover = albumObj?.optString("cover_xl") ?: albumObj?.optString("cover_big")
                            if (!cover.isNullOrEmpty()) {
                                candidates.add(CoverCandidate(t, a, albumObj?.optString("title") ?: "", cover, "Deezer"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            candidates
        }

        val results = awaitAll(deferredQQ, deferredNetease, deferredItunes, deferredDeezer)
        results.flatten().filter { it.imageUrl.isNotEmpty() }
    }

    fun downloadBitmap(url: String): Bitmap? {
        return try {
            var currentUrl = url
            repeat(3) {
                val req = Request.Builder().url(currentUrl).build()
                val response = client.newCall(req).execute()
                val location = response.header("Location")
                if (response.isRedirect && location != null) {
                    currentUrl = location
                    return@repeat
                }
                if (response.isSuccessful) {
                    return BitmapFactory.decodeStream(response.body?.byteStream())
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun buildKeyword(title: String, artist: String): String {
        val t = title.trim()
        val a = artist.split("/").firstOrNull()?.trim() ?: artist.trim()
        return if (a.isEmpty() || a == "未知歌手") t else "$a $t"
    }
}