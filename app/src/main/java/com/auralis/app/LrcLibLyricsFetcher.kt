package com.auralis.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object LrcLibLyricsFetcher {
    private const val TAG = "LrcLibLyricsFetcher"

    fun searchCandidates(keywords: String, durationSec: Int): List<LyricCandidate> {
        val searchUrl = "https://lrclib.net/api/search?q=${URLEncoder.encode(keywords, "UTF-8")}"
        val res = makeGetRequest(searchUrl) ?: return emptyList()
        val candidates = mutableListOf<LyricCandidate>()

        try {
            val arr = JSONArray(res)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optInt("id").toString()
                val title = item.optString("name")
                val artist = item.optString("artistName")
                val album = item.optString("albumName")
                val duration = item.optInt("duration", 0)
                
                // Store the synced/plain lyrics in our preview or cache to avoid another request!
                val synced = item.optString("syncedLyrics")
                val plain = item.optString("plainLyrics")
                val bestLrc = if (!synced.isNullOrEmpty()) synced else (plain ?: "")

                candidates.add(
                    LyricCandidate(
                        platform = "LrcLib",
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = duration,
                        score = 0.0,
                        previewLrc = bestLrc // Stash full lyric in previewLrc for direct application!
                    )
                )
            }
        } catch (e: Exception) {
            // silent
        }
        return candidates
    }

    fun fetchLyric(id: String): String? {
        // Since we stash the lyrics in previewLrc during search, we can use that,
        // but if called directly, we fetch from the get API:
        val getUrl = "https://lrclib.net/api/get/$id"
        val res = makeGetRequest(getUrl) ?: return null
        return try {
            val json = JSONObject(res)
            val synced = json.optString("syncedLyrics")
            val plain = json.optString("plainLyrics")
            if (!synced.isNullOrEmpty()) synced else plain
        } catch (e: Exception) {
            null
        }
    }

    private fun makeGetRequest(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Auralis/1.0 (https://github.com/auralis)")
            
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
