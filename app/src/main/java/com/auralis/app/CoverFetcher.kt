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

object CoverFetcher {
    private const val TAG = "CoverFetcher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)     // OkHttp handles same-protocol redirects
        .followSslRedirects(true)  // Also follow http→https redirects (needed for CAA)
        .build()

    // 伪装成 PC 浏览器
    private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"

    /**
     * 智能获取高清封面：iTunes 优先，网易云兜底
     */
    suspend fun fetchHighResCover(title: String, artist: String): Bitmap? = withContext(Dispatchers.IO) {
        val keyword = buildKeyword(title, artist)

        // 🏆 第一级：尝试 Apple Music (iTunes API) 获取顶级画质
        try {
            val itunesUrl = "https://itunes.apple.com/search?term=${URLEncoder.encode(keyword, "UTF-8")}&media=music&entity=song&limit=1"
            val req = Request.Builder().url(itunesUrl).build()
            val body = client.newCall(req).execute().body?.string()

            if (body != null) {
                val results = JSONObject(body).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val artwork100 = results.getJSONObject(0).optString("artworkUrl100")
                    if (artwork100.isNotEmpty()) {
                        // 🔮 黑科技：把 100x100 的小图链接强行改成 800x800 的高清大图！
                        val artwork800 = artwork100.replace("100x100bb", "800x800bb")
                        val bitmap = downloadBitmap(artwork800)
                        if (bitmap != null) {
                            Log.d(TAG, "🍎 从 iTunes 获取高清封面成功！($title)")
                            return@withContext bitmap
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "iTunes 搜索失败: ${e.message}")
        }

        // 🥈 第二级：Deezer（无需 API key，最简单，速度快）
        try {
            val deezerUrl = "https://api.deezer.com/search?q=${URLEncoder.encode(keyword, "UTF-8")}&limit=1"
            val req = Request.Builder().url(deezerUrl).build()
            val body = client.newCall(req).execute().body?.string()
            if (body != null) {
                val data = JSONObject(body).optJSONArray("data")
                if (data != null && data.length() > 0) {
                    val coverXl = data.getJSONObject(0)
                        .optJSONObject("album")?.optString("cover_xl")
                    if (!coverXl.isNullOrEmpty()) {
                        val bitmap = downloadBitmap(coverXl)
                        if (bitmap != null) {
                            Log.d(TAG, "🎵 从 Deezer 获取封面成功！($title)")
                            return@withContext bitmap
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Deezer 封面失败: ${e.message}")
        }

        // 🥉 第三级：MusicBrainz + Cover Art Archive（开源，国际乐最准）
        try {
            val mbUrl = "https://musicbrainz.org/ws/2/recording/?query=${URLEncoder.encode(keyword, "UTF-8")}&limit=3&fmt=json"
            val req = Request.Builder()
                .url(mbUrl)
                .addHeader("User-Agent", "Auralis/1.0 (https://github.com/auralis)")
                .build()
            val body = client.newCall(req).execute().body?.string()
            if (body != null) {
                val recordings = JSONObject(body).optJSONArray("recordings")
                if (recordings != null) {
                    for (i in 0 until recordings.length()) {
                        val releaseId = recordings.optJSONObject(i)
                            ?.optJSONArray("releases")?.optJSONObject(0)?.optString("id")
                        if (!releaseId.isNullOrEmpty()) {
                            // CAA returns the cover directly at this URL (follows redirect)
                            val caaUrl = "https://coverartarchive.org/release/$releaseId/front-500"
                            val bitmap = downloadBitmap(caaUrl)
                            if (bitmap != null) {
                                Log.d(TAG, "🎼 从 MusicBrainz/CAA 获取封面成功！($title)")
                                return@withContext bitmap
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MusicBrainz/CAA 封面失败: ${e.message}")
        }

        // 4️⃣ 第四级：网易云音乐
        try {
            Log.d(TAG, "☁️ 降级使用网易云搜索...")
            val neteaseUrl = "https://music.163.com/api/search/get/web?csrf_token=hlpretag=&hlposttag=&s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&offset=0&total=true&limit=1"
            val req = Request.Builder()
                .url(neteaseUrl)
                .addHeader("User-Agent", PC_UA)
                .addHeader("Referer", "https://music.163.com/")
                .build()
            val body = client.newCall(req).execute().body?.string()
            if (body != null) {
                val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
                if (songs != null && songs.length() > 0) {
                    var picUrl = songs.getJSONObject(0).optJSONObject("album")?.optString("picUrl")
                    if (!picUrl.isNullOrEmpty()) {
                        if (picUrl.startsWith("http://")) picUrl = picUrl.replace("http://", "https://")
                        picUrl = if (picUrl.contains("?")) "$picUrl&param=600y600" else "$picUrl?param=600y600"
                        val bitmap = downloadBitmap(picUrl)
                        if (bitmap != null) {
                            Log.d(TAG, "☁️ 从网易云获取封面成功！($title)")
                            return@withContext bitmap
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "网易云搜索封面失败: ${e.message}")
        }

        // 5️⃣ 第五级：Last.fm（需要在 buildKeyword 找到歌曲后获取 album art）
        try {
            // Last.fm public API — no key needed for basic track.search
            val lfmUrl = "https://ws.audioscrobbler.com/2.0/?method=track.search&track=${URLEncoder.encode(keyword, "UTF-8")}&api_key=43651f7ef33c9ed83a0b549e6f1b0c7f&format=json&limit=1"
            val req = Request.Builder().url(lfmUrl).addHeader("User-Agent", PC_UA).build()
            val body = client.newCall(req).execute().body?.string()
            if (body != null) {
                val tracks = JSONObject(body)
                    .optJSONObject("results")?.optJSONObject("trackmatches")
                    ?.optJSONArray("track")
                if (tracks != null && tracks.length() > 0) {
                    val track    = tracks.getJSONObject(0)
                    val trackName = track.optString("name")
                    val artistName = track.optString("artist")
                    // Fetch full track info to get album art
                    val infoUrl = "https://ws.audioscrobbler.com/2.0/?method=track.getInfo&artist=${URLEncoder.encode(artistName, "UTF-8")}&track=${URLEncoder.encode(trackName, "UTF-8")}&api_key=43651f7ef33c9ed83a0b549e6f1b0c7f&format=json"
                    val infoBody = client.newCall(Request.Builder().url(infoUrl).build()).execute().body?.string()
                    if (infoBody != null) {
                        val images = JSONObject(infoBody)
                            .optJSONObject("track")?.optJSONObject("album")
                            ?.optJSONArray("image")
                        if (images != null) {
                            // Last.fm image sizes: small, medium, large, extralarge, mega — pick the last/largest
                            for (j in images.length() - 1 downTo 0) {
                                val imgUrl = images.optJSONObject(j)?.optString("#text")
                                if (!imgUrl.isNullOrEmpty()) {
                                    val bitmap = downloadBitmap(imgUrl)
                                    if (bitmap != null) {
                                        Log.d(TAG, "🎸 从 Last.fm 获取封面成功！($title)")
                                        return@withContext bitmap
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Last.fm 封面失败: ${e.message}")
        }

        Log.d(TAG, "❌ 所有来源均未找到封面，将使用流体极光兜底")
        return@withContext null
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            // Follow up to 3 redirects (needed for Cover Art Archive)
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