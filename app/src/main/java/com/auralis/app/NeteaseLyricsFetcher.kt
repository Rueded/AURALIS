package com.auralis.app

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object NeteaseLyricsFetcher {
    private const val TAG = "NeteaseLyricsFetcher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun makeNeteaseRequest(context: Context, url: String): String? {
        val prefs = context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val customCookie = prefs.getString("netease_custom_cookie", "")

        val randomDeviceId = java.util.UUID.randomUUID().toString().replace("-", "").uppercase()
        val defaultCookie = "os=pc; osver=Microsoft-Windows-10-Professional-build-19045-64bit; appver=3.0.1; deviceId=$randomDeviceId; _ntes_nuid=$randomDeviceId"
        val finalCookie = if (!customCookie.isNullOrBlank()) customCookie else defaultCookie
        val randomIp = "114.114.${(1..254).random()}.${(1..254).random()}"

        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://music.163.com/")
                .addHeader("Cookie", finalCookie)
                .addHeader("X-Real-IP", randomIp)
                .addHeader("X-Forwarded-For", randomIp)
                .build()
            val response = client.newCall(req).execute()
            if (response.isSuccessful) response.body?.string() else null
        } catch (e: Exception) {
            null
        }
    }

    fun searchCandidates(context: Context, keywords: String, durationSec: Int): List<LyricCandidate> {
        val candidates = mutableListOf<LyricCandidate>()
        try {
            val searchUrl = "https://music.163.com/api/search/get?s=${URLEncoder.encode(keywords, "UTF-8")}&type=1&limit=10"
            val body = makeNeteaseRequest(context, searchUrl) ?: return emptyList()
            val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()

            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                val id = song.optString("id")
                val name = song.optString("name")
                val artists = song.optJSONArray("artists")
                val artistName = if (artists != null && artists.length() > 0) artists.optJSONObject(0)?.optString("name") ?: "" else ""
                val albumName = song.optJSONObject("album")?.optString("name") ?: ""
                val duration = song.optInt("duration", 0) / 1000

                candidates.add(LyricCandidate(
                    platform = "网易云", id = id, title = name, artist = artistName, album = albumName, durationSec = duration, score = 0.0, previewLrc = ""
                ))
            }
        } catch (e: Exception) {
            // silent
        }
        return candidates
    }

    fun fetchLyric(context: Context, id: String): String? {
        return try {
            val lrcUrl = "https://music.163.com/api/song/lyric?id=$id&kv=1&tv=-1"
            val body = makeNeteaseRequest(context, lrcUrl) ?: return null
            val json = JSONObject(body)
            val lrc = json.optJSONObject("lrc")?.optString("lyric")
            if (!lrc.isNullOrEmpty()) lrc else null
        } catch (e: Exception) {
            null
        }
    }
}