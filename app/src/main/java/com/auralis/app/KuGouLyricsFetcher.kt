package com.auralis.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

object KuGouLyricsFetcher {
    private const val TAG = "KuGouLyricsFetcher"

    fun searchCandidates(keywords: String, durationSec: Int): List<LyricCandidate> {
        val candidates = mutableListOf<LyricCandidate>()
        try {
            val searchUrl = "https://msearch.kugou.com/api/v3/search/song?keyword=${URLEncoder.encode(keywords, "UTF-8")}&page=1&pagesize=5&format=json"
            val searchRes = makeGetRequest(searchUrl) ?: return emptyList()
            val searchJson = JSONObject(searchRes)
            val songs = searchJson.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
            
            for (i in 0 until songs.length()) {
                val song = songs.optJSONObject(i) ?: continue
                val hash = song.optString("hash").takeIf { it.isNotEmpty() } ?: continue
                val songName = song.optString("songname")
                val singerName = song.optString("singername")
                val albumName = song.optString("album_name")
                val duration = song.optInt("duration", 0)

                val lrcSearchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${URLEncoder.encode(keywords, "UTF-8")}&hash=$hash&duration=${duration * 1000}"
                val lrcSearchRes = makeGetRequest(lrcSearchUrl)
                if (lrcSearchRes != null) {
                    val lrcJson = JSONObject(lrcSearchRes)
                    val list = lrcJson.optJSONArray("candidates") 
                        ?: lrcJson.optJSONArray("ugccandidates")
                    
                    if (list != null && list.length() > 0) {
                        for (j in 0 until minOf(list.length(), 3)) {
                            val cand = list.optJSONObject(j) ?: continue
                            val id = cand.optString("id")
                            val accesskey = cand.optString("accesskey")
                            val candSong = cand.optString("song")
                            val candSinger = cand.optString("singer")
                            
                            candidates.add(
                                LyricCandidate(
                                    platform = "酷狗",
                                    id = "${id}_${accesskey}",
                                    title = if (candSong.isNotEmpty()) candSong else songName,
                                    artist = if (candSinger.isNotEmpty()) candSinger else singerName,
                                    album = albumName,
                                    durationSec = duration,
                                    score = 0.0,
                                    previewLrc = ""
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // silent
        }
        return candidates
    }

    fun fetchLyric(compositeId: String): String? {
        return try {
            val parts = compositeId.split("_")
            if (parts.size < 2) return null
            val id = parts[0]
            val accesskey = parts[1]
            val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8"
            val dlRes = makeGetRequest(dlUrl) ?: return null
            val dlJson = JSONObject(dlRes)
            val rawB64 = dlJson.optString("content").takeIf { it.isNotEmpty() } ?: return null
            String(android.util.Base64.decode(rawB64, android.util.Base64.DEFAULT), Charset.forName("UTF-8"))
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
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            
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
