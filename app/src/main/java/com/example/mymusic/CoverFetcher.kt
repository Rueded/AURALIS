package com.example.mymusic

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
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
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

        // 🥈 第二级：如果苹果没有，降级去网易云音乐 API 找
        try {
            Log.d(TAG, "☁️ iTunes 未找到，降级使用网易云搜索...")
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
                        // 🔮 网易云强制请求 600x600 的清晰图
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

        Log.d(TAG, "❌ 网上没找到封面，将使用流体极光兜底")
        return@withContext null
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val req = Request.Builder().url(url).build()
            val response = client.newCall(req).execute()
            if (response.isSuccessful) {
                val inputStream = response.body?.byteStream()
                BitmapFactory.decodeStream(inputStream)
            } else null
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