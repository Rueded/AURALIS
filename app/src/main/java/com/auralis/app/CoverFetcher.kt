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

    private const val PC_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ✅ 与 NeteaseLyricsFetcher 完全对齐的网易云请求方法
    // appver=3.0.1，去掉 channel=netease，已验证可绕过反爬
    private fun makeNeteaseRequest(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", PC_UA)
                .addHeader("Referer", "https://music.163.com/")
                .addHeader(
                    "Cookie",
                    "os=pc; osver=Microsoft-Windows-10-Professional-build-19045-64bit; appver=3.0.1"
                )
                .build()
            val response = client.newCall(req).execute()
            if (response.isSuccessful) response.body?.string() else null
        } catch (e: Exception) {
            Log.w(TAG, "网易云请求失败: ${e.message}")
            null
        }
    }

    private fun normalize(s: String): String {
        return s.lowercase()
            .replace(Regex("[\\s\\-_()（）【】「」『』\\[\\]]"), "")
            .trim()
    }

    private fun isMatch(
        queryTitle: String, queryArtist: String,
        resultTitle: String, resultArtist: String
    ): Boolean {
        val qTitle = normalize(queryTitle)
        val rTitle = normalize(resultTitle)
        val qArtist = normalize(queryArtist)
        val rArtist = normalize(resultArtist)

        if (qTitle.isEmpty() || rTitle.isEmpty()) return false
        val titleMatch = qTitle.contains(rTitle) || rTitle.contains(qTitle)

        val isUnknownArtist = qArtist.contains("未知歌手") || qArtist.isEmpty()
        val artistMatch = isUnknownArtist ||
                qArtist.contains(rArtist) || rArtist.contains(qArtist) ||
                qArtist.split(Regex("[/&,\\s]+")).any { q -> q.isNotEmpty() && rArtist.contains(q) } ||
                rArtist.split(Regex("[/&,\\s]+")).any { r -> r.isNotEmpty() && qArtist.contains(r) }

        return titleMatch && artistMatch
    }

    // ✅ 新增：从网易云歌曲详情接口获取专辑高清封面
    // 当搜索结果的 picUrl 不可用时作为补充
    private fun fetchNeteaseAlbumCoverById(songId: Long): String? {
        return try {
            val detailUrl = "https://music.163.com/api/song/detail?ids=[$songId]"
            val body = makeNeteaseRequest(detailUrl) ?: return null
            val json = JSONObject(body)
            val songs = json.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null
            val song = songs.optJSONObject(0) ?: return null
            var picUrl = song.optJSONObject("album")?.optString("picUrl")
            if (picUrl.isNullOrEmpty()) return null
            if (picUrl.startsWith("http://")) picUrl = picUrl.replace("http://", "https://")
            if (picUrl.contains("?")) "$picUrl&param=800y800" else "$picUrl?param=800y800"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 智能获取高清封面：网易云第一优先（与歌词接口完全一致请求方式）→ QQ音乐 → 其他
     */
    suspend fun fetchHighResCover(title: String, artist: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val keyword = buildKeyword(title, artist)

            // ========== 🏆 第一优先：网易云（与 NeteaseLyricsFetcher 完全一致） ==========
            try {
                val searchUrl =
                    "https://music.163.com/api/search/get?s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&limit=10"
                val body = makeNeteaseRequest(searchUrl)
                if (body != null) {
                    val songs = JSONObject(body)
                        .optJSONObject("result")
                        ?.optJSONArray("songs")

                    if (songs != null) {
                        for (i in 0 until songs.length()) {
                            val song = songs.getJSONObject(i)
                            val trackName = song.optString("name")
                            val artistsArr = song.optJSONArray("artists")
                            val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                                artistsArr.optJSONObject(0)?.optString("name") ?: ""
                            } else ""

                            if (isMatch(title, artist, trackName, artistName)) {
                                val songId = song.optLong("id")
                                val albumObj = song.optJSONObject("album")
                                var picUrl = albumObj?.optString("picUrl")

                                // ✅ picUrl 协议修正 + 分辨率参数
                                if (!picUrl.isNullOrEmpty()) {
                                    if (picUrl.startsWith("http://")) {
                                        picUrl = picUrl.replace("http://", "https://")
                                    }
                                    picUrl = if (picUrl.contains("?")) {
                                        "$picUrl&param=800y800"
                                    } else {
                                        "$picUrl?param=800y800"
                                    }
                                    val bitmap = downloadBitmap(picUrl)
                                    if (bitmap != null) {
                                        Log.d(TAG, "✅ 网易云搜索封面成功（$trackName）")
                                        return@withContext bitmap
                                    }
                                }

                                // ✅ 如果 picUrl 下载失败，用 song detail 接口再试一次
                                if (songId > 0) {
                                    val detailUrl = fetchNeteaseAlbumCoverById(songId)
                                    if (detailUrl != null) {
                                        val bitmap = downloadBitmap(detailUrl)
                                        if (bitmap != null) {
                                            Log.d(TAG, "✅ 网易云详情接口封面成功（id=$songId）")
                                            return@withContext bitmap
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "网易云封面检索异常: ${e.message}")
            }

            // ========== 🥈 第二优先：QQ音乐 ==========
            try {
                val qqUrl =
                    "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${URLEncoder.encode(keyword, "UTF-8")}&p=1&n=5&format=json"
                val req = Request.Builder()
                    .url(qqUrl)
                    .addHeader("User-Agent", PC_UA)
                    .addHeader("Cookie", "pgv_pvi=22038528; pgv_si=s3156287488; os_name=windows;")
                    .build()
                val body = client.newCall(req).execute().body?.string()
                if (body != null) {
                    val list = JSONObject(body)
                        .optJSONObject("data")
                        ?.optJSONObject("song")
                        ?.optJSONArray("list")
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            val song = list.getJSONObject(i)
                            val trackName = song.optString("songname")
                            val artistName =
                                song.optJSONArray("singer")?.optJSONObject(0)?.optString("name") ?: ""
                            if (isMatch(title, artist, trackName, artistName)) {
                                val albumId = song.optString("albummid")
                                if (albumId.isNotEmpty()) {
                                    val imgUrl =
                                        "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumId.jpg"
                                    val bitmap = downloadBitmap(imgUrl)
                                    if (bitmap != null) {
                                        Log.d(TAG, "✅ QQ音乐封面成功（$trackName）")
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

            // ========== 🥉 第三：iTunes ==========
            try {
                val itunesUrl =
                    "https://itunes.apple.com/search?term=${URLEncoder.encode(keyword, "UTF-8")}&media=music&entity=song&limit=5"
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
                                    val bitmap = downloadBitmap(artwork100.replace("100x100bb", "800x800bb"))
                                    if (bitmap != null) return@withContext bitmap
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}

            // ========== 4️⃣ 第四：Deezer ==========
            try {
                val deezerUrl =
                    "https://api.deezer.com/search?q=${URLEncoder.encode(keyword, "UTF-8")}&limit=5"
                val req = Request.Builder().url(deezerUrl).build()
                val body = client.newCall(req).execute().body?.string()
                if (body != null) {
                    val data = JSONObject(body).optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)
                            if (isMatch(
                                    title, artist,
                                    item.optString("title"),
                                    item.optJSONObject("artist")?.optString("name") ?: ""
                                )
                            ) {
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

            // ========== 5️⃣ 第五：MusicBrainz ==========
            try {
                val mbUrl =
                    "https://musicbrainz.org/ws/2/recording/?query=${URLEncoder.encode(keyword, "UTF-8")}&limit=5&fmt=json"
                val req = Request.Builder()
                    .url(mbUrl)
                    .addHeader("User-Agent", "Auralis/1.0 (auralis_dev@gmail.com)")
                    .build()
                val body = client.newCall(req).execute().body?.string()
                if (body != null) {
                    val recordings = JSONObject(body).optJSONArray("recordings")
                    if (recordings != null && recordings.length() > 0) {
                        val releaseId = recordings.optJSONObject(0)
                            ?.optJSONArray("releases")
                            ?.optJSONObject(0)
                            ?.optString("id")
                        if (!releaseId.isNullOrEmpty()) {
                            val bitmap =
                                downloadBitmap("https://coverartarchive.org/release/$releaseId/front-500")
                            if (bitmap != null) return@withContext bitmap
                        }
                    }
                }
            } catch (e: Exception) {}

            Log.d(TAG, "❌ 所有封面源均无匹配")
            null
        }

    suspend fun searchCoverCandidates(title: String, artist: String): List<CoverCandidate> =
        coroutineScope {
            val keyword = buildKeyword(title, artist)
            val encodedKw = URLEncoder.encode(keyword, "UTF-8")

            val deferredQQ = async(Dispatchers.IO) {
                val candidates = mutableListOf<CoverCandidate>()
                try {
                    val url =
                        "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$encodedKw&p=1&n=5&format=json"
                    val req = Request.Builder().url(url)
                        .addHeader("User-Agent", PC_UA)
                        .addHeader("Cookie", "pgv_pvi=22038528; pgv_si=s3156287488; os_name=windows;")
                        .build()
                    val body = client.newCall(req).execute().body?.string()
                    if (body != null) {
                        val list = JSONObject(body)
                            .optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                        if (list != null) {
                            for (i in 0 until list.length()) {
                                val song = list.getJSONObject(i)
                                val albumId = song.optString("albummid")
                                if (albumId.isNotEmpty()) {
                                    candidates.add(
                                        CoverCandidate(
                                            title = song.optString("songname"),
                                            artist = song.optJSONArray("singer")?.optJSONObject(0)
                                                ?.optString("name") ?: "",
                                            album = song.optString("albumname"),
                                            imageUrl = "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumId.jpg",
                                            platform = "QQ音乐"
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
                candidates
            }

            // ✅ 候选列表的网易云查询也统一到新的 makeNeteaseRequest
            val deferredNetease = async(Dispatchers.IO) {
                val candidates = mutableListOf<CoverCandidate>()
                try {
                    val url =
                        "https://music.163.com/api/search/get?s=$encodedKw&type=1&limit=10"
                    val body = makeNeteaseRequest(url)
                    if (body != null) {
                        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
                        if (songs != null) {
                            for (i in 0 until songs.length()) {
                                val song = songs.getJSONObject(i)
                                val t = song.optString("name")
                                val a = song.optJSONArray("artists")?.optJSONObject(0)
                                    ?.optString("name") ?: ""
                                val albumObj = song.optJSONObject("album")
                                val songId = song.optLong("id")
                                var picUrl = albumObj?.optString("picUrl")

// ✅ 关键兜底：search 接口返回的 picUrl 有时为空，用 detail 接口补全
                                if (picUrl.isNullOrEmpty() && songId > 0) {
                                    picUrl = fetchNeteaseAlbumCoverById(songId)
                                }

                                if (!picUrl.isNullOrEmpty()) {
                                    if (picUrl.startsWith("http://")) {
                                        picUrl = picUrl.replace("http://", "https://")
                                    }
                                    picUrl = if (picUrl.contains("?")) {
                                        "$picUrl&param=500y500"
                                    } else {
                                        "$picUrl?param=500y500"
                                    }
                                    candidates.add(
                                        CoverCandidate(
                                            title = t,
                                            artist = a,
                                            album = albumObj?.optString("name") ?: "",
                                            imageUrl = picUrl,
                                            platform = "网易云"
                                        )
                                    )
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
                    val url =
                        "https://itunes.apple.com/search?term=$encodedKw&media=music&entity=song&limit=5"
                    val req = Request.Builder().url(url).build()
                    val body = client.newCall(req).execute().body?.string()
                    if (body != null) {
                        val results = JSONObject(body).optJSONArray("results")
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                val artwork100 = item.optString("artworkUrl100")
                                if (artwork100.isNotEmpty()) {
                                    candidates.add(
                                        CoverCandidate(
                                            title = item.optString("trackName"),
                                            artist = item.optString("artistName"),
                                            album = item.optString("collectionName"),
                                            imageUrl = artwork100.replace("100x100bb", "800x800bb"),
                                            platform = "iTunes"
                                        )
                                    )
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
                                val albumObj = item.optJSONObject("album")
                                val cover = albumObj?.optString("cover_xl")
                                    ?: albumObj?.optString("cover_big")
                                if (!cover.isNullOrEmpty()) {
                                    candidates.add(
                                        CoverCandidate(
                                            title = item.optString("title"),
                                            artist = item.optJSONObject("artist")?.optString("name") ?: "",
                                            album = albumObj?.optString("title") ?: "",
                                            imageUrl = cover,
                                            platform = "Deezer"
                                        )
                                    )
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