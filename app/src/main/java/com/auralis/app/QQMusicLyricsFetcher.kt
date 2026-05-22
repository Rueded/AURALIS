package com.auralis.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

object QQMusicLyricsFetcher {
    private const val TAG = "QQMusicLyricsFetcher"

    fun searchCandidates(keywords: String, durationSec: Int): List<LyricCandidate> {
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=10&w=${URLEncoder.encode(keywords, "UTF-8")}&format=json"
        val res = makeGetRequest(searchUrl, mapOf("Referer" to "https://y.qq.com/")) ?: return emptyList()
        val candidates = mutableListOf<LyricCandidate>()

        try {
            val json = JSONObject(res)
            val list = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()
            for (i in 0 until list.length()) {
                val song = list.optJSONObject(i) ?: continue
                val songmid = song.optString("songmid")
                val songname = song.optString("songname")
                val albumname = song.optString("albumname")
                
                val singersArr = song.optJSONArray("singer")
                val singersList = mutableListOf<String>()
                if (singersArr != null) {
                    for (j in 0 until singersArr.length()) {
                        singersList.add(singersArr.optJSONObject(j)?.optString("name") ?: "")
                    }
                }
                val artistStr = singersList.joinToString(" ")
                val duration = song.optInt("interval", 0)

                candidates.add(
                    LyricCandidate(
                        platform = "QQ音乐",
                        id = songmid,
                        title = songname,
                        artist = artistStr,
                        album = albumname,
                        durationSec = duration,
                        score = 0.0,
                        previewLrc = ""
                    )
                )
            }
        } catch (e: Exception) {
            // silent
        }
        return candidates
    }

    fun fetchLyric(songmid: String): String? {
        val lrcUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
        val res = makeGetRequest(lrcUrl, mapOf("Referer" to "https://y.qq.com/")) ?: return null
        return try {
            val json = JSONObject(res)
            val lyricBase64 = json.optString("lyric")
            if (lyricBase64.isNullOrEmpty()) null
            else {
                val decodedBytes = android.util.Base64.decode(lyricBase64, android.util.Base64.DEFAULT)
                val rawLrc = String(decodedBytes, Charset.forName("UTF-8")).trim()
                val transBase64 = json.optString("trans")
                if (!transBase64.isNullOrEmpty()) {
                    val transBytes = android.util.Base64.decode(transBase64, android.util.Base64.DEFAULT)
                    val transLrc = String(transBytes, Charset.forName("UTF-8")).trim()
                    mergeLyrics(rawLrc, transLrc)
                } else {
                    rawLrc
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun mergeLyrics(lrc: String, trans: String): String {
        try {
            val mainLines = lrc.lines()
            val transLines = trans.lines()
            val transMap = mutableMapOf<String, String>()
            val timeReg = Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}\\]")
            
            for (line in transLines) {
                val match = timeReg.find(line) ?: continue
                val text = line.substring(match.range.last + 1).trim()
                if (text.isNotEmpty()) {
                    transMap[match.value] = text
                }
            }
            
            val merged = mutableListOf<String>()
            for (line in mainLines) {
                merged.add(line)
                val match = timeReg.find(line)
                if (match != null) {
                    val transText = transMap[match.value]
                    if (transText != null && transText.isNotEmpty()) {
                        merged.add("${match.value}$transText")
                    }
                }
            }
            return merged.joinToString("\n")
        } catch (e: Exception) {
            return lrc
        }
    }

    private fun makeGetRequest(urlString: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            
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
