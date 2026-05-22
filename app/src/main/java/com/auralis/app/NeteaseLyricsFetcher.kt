package com.auralis.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlin.math.abs

object NeteaseLyricsFetcher {

    // 版本识别规则库
    private data class VersionRule(val userTerms: List<String>, val resultTerms: List<String>, val penalty: Double, val boost: Double)

    private val versionRules = listOf(
        VersionRule(listOf("cover", "翻唱", "カバー"), listOf("cover", "翻唱"), -60.0, 20.0),
        VersionRule(listOf("remix", "rmx"), listOf("remix", "rmx"), -30.0, 25.0),
        VersionRule(listOf("live"), listOf("live"), -20.0, 20.0),
        VersionRule(listOf("acoustic"), listOf("acoustic"), -20.0, 20.0),
        VersionRule(listOf("inst", "instrumental", "伴奏"), listOf("inst", "karaoke", "instrumental", "伴奏", "off vocal"), -60.0, 15.0),
        VersionRule(listOf("english", "eng"), listOf("english", "eng ver", "english ver"), -80.0, 10.0)
    )

    fun searchCandidates(keywords: String, durationSec: Int): List<LyricCandidate> {
        val rawKw = keywords.lowercase().trim()
        if (rawKw.isEmpty()) return emptyList()

        // 💡 修复：切换为更稳定的标准搜索接口，避免网页版高频触发 -460 封禁
        val searchUrl = "https://music.163.com/api/search/get?s=${URLEncoder.encode(keywords, "UTF-8")}&type=1&limit=10"
        val searchRes = makeGetRequest(searchUrl) ?: return emptyList()

        val searchJson = try { JSONObject(searchRes) } catch (e: Exception) { return emptyList() }
        val songs = searchJson.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()

        val kwNorm = normalizeText(keywords)
        val kwTokens = keywords.split(Regex("\\s+")).filter { it.isNotBlank() }

        val candidates = mutableListOf<LyricCandidate>()

        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optLong("id")
            val name = song.optString("name")
            val albumName = song.optJSONObject("album")?.optString("name") ?: ""
            val artistsArr = song.optJSONArray("artists")
            val artistNames = mutableListOf<String>()

            if (artistsArr != null) {
                for (j in 0 until artistsArr.length()) {
                    artistNames.add(artistsArr.optJSONObject(j)?.optString("name") ?: "")
                }
            }

            val artistNamesStr = artistNames.joinToString(" ")
            val aliasesArr = song.optJSONArray("alias")
            val aliasesStr = if (aliasesArr != null) {
                (0 until aliasesArr.length()).map { aliasesArr.optString(it) }.joinToString(" ")
            } else ""

            val dt = song.optInt("duration", 0) // 网易云下发的是毫秒
            var score = 0.0

            val songNameNorm = normalizeText(name)
            val allText = "$songNameNorm ${normalizeText(artistNamesStr)}"

            // 规则A: 基础标题匹配
            if (songNameNorm == kwNorm) score += 50.0
            else if (songNameNorm.isNotEmpty() && kwNorm.contains(songNameNorm)) score += 35.0
            else if (songNameNorm.isNotEmpty() && songNameNorm.contains(kwNorm)) score += 30.0
            else {
                val chars = kwNorm.toSet()
                var hits = 0
                for (c in songNameNorm) { if (chars.contains(c)) hits++ }
                score += (hits.toDouble() / maxOf(songNameNorm.length, 1)) * 20.0
            }

            // 规则B: 关键词 Token 分词匹配 (极其看重歌手名匹配)
            for (tok in kwTokens) {
                val normTok = normalizeText(tok)
                if (normTok.isEmpty()) continue
                if (normalizeText(artistNamesStr).contains(normTok)) score += 40.0
                else if (songNameNorm.contains(normTok)) score += 15.0
                else if (allText.contains(normTok)) score += 10.0
            }

            // 规则C: 版本倾向性控制 (大幅度惩罚把原唱匹配成伴奏/翻唱的情况)
            val searchStr = "${name.lowercase()} ${aliasesStr.lowercase()}"
            for (rule in versionRules) {
                val userWants = rule.userTerms.any { rawKw.contains(it) }
                val resultHas = rule.resultTerms.any { searchStr.contains(it) }

                if (userWants && resultHas) score += rule.boost
                else if (userWants && !resultHas) score -= 8.0
                else if (!userWants && resultHas) score += rule.penalty
            }

            // 规则D: 音频时长校验补偿
            if (dt > 0 && durationSec > 0) {
                val diff = abs(dt / 1000 - durationSec)
                if (diff <= 3) score += 30.0
                else if (diff <= 10) score += 20.0
                else if (diff <= 30) score += 10.0
                else if (diff <= 45) score += 5.0
                else if (diff > 90) score -= 10.0
            }

            candidates.add(
                LyricCandidate(
                    platform = "网易云",
                    id = id.toString(),
                    title = name,
                    artist = artistNamesStr,
                    album = albumName,
                    durationSec = dt / 1000,
                    score = score,
                    previewLrc = ""
                )
            )
        }

        return candidates.sortedByDescending { it.score }
    }

    fun fetchLyric(id: String): String? {
        // 💡 修复：lv=-1, kv=-1 才能确保网易云返回完整的全版本和歌词文本对象
        val lyricUrl = "https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1"
        val lyricRes = makeGetRequest(lyricUrl) ?: return null
        val lyrJson = try { JSONObject(lyricRes) } catch (e: Exception) { return null }

        val lrc = lyrJson.optJSONObject("lrc")?.optString("lyric")?.trim()
        val tlyric = lyrJson.optJSONObject("tlyric")?.optString("lyric")?.trim()
        val romalrc = lyrJson.optJSONObject("romalrc")?.optString("lyric")?.trim()

        if (lrc.isNullOrEmpty()) return null

        val mergedLrc = mergeTimedLyrics(lrc, romalrc, tlyric) ?: lrc
        return repairPossiblyMojibakeText(mergedLrc)
    }

    private fun normalizeText(s: String?): String {
        if (s == null) return ""
        return s.lowercase()
            .replace(Regex("[\\s\\-_()（）【】「」『』\\[\\]]"), "")
            .trim()
    }

    private fun repairPossiblyMojibakeText(text: String): String {
        if (text.contains("鐣忕目") || text.contains("操作频繁")) {
            return "操作频繁，请稍候再试"
        }
        val hintRegex = Regex("[锛鍚鎿璇銆\u200E]")
        if (!hintRegex.containsMatchIn(text) && !Regex("[鎿嶄綔绋€欒]").containsMatchIn(text)) {
            return text
        }
        return try {
            val bytes = text.toByteArray(Charset.forName("ISO-8859-1"))
            val gbkStr = String(bytes, Charset.forName("GBK")).trim()
            if (gbkStr.contains("操作频繁")) "操作频繁，请稍候再试" else gbkStr
        } catch (e: Exception) {
            text
        }
    }

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun makeGetRequest(urlString: String): String? {
        return try {
            val req = okhttp3.Request.Builder()
                .url(urlString)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://music.163.com/")
                // 💡 修复：添加核心 Cookie 伪装，彻底规避高频反爬风控
                .addHeader("Cookie", "os=pc; osver=Microsoft-Windows-10-Professional-build-19045-64bit; appver=3.0.1")
                .build()
            val response = client.newCall(req).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class LyricRow(val timeMs: Long, val tagText: String, val text: String)
    private data class ParsedLyric(val rows: List<LyricRow>, val byTime: Map<Long, String>)

    private fun parseTimedLyric(raw: String?): ParsedLyric {
        val rows = mutableListOf<LyricRow>()
        val byTime = mutableMapOf<Long, String>()
        if (raw.isNullOrBlank()) return ParsedLyric(rows, byTime)

        val timeTagReg = Regex("\\[(\\d{2}):(\\d{2})(?:\\.|:)(\\d{2,3})\\]")
        for (line in raw.split("\n")) {
            val matches = timeTagReg.findAll(line).toList()
            if (matches.isEmpty()) continue

            val text = line.replace(timeTagReg, "").trim()
            if (text.isEmpty()) continue

            val tagText = matches.joinToString("") { it.value }
            val primaryMatch = matches.first()

            val m = primaryMatch.groupValues[1].toLong()
            val s = primaryMatch.groupValues[2].toLong()
            val fracStr = primaryMatch.groupValues[3]
            val frac = if (fracStr.length == 3) fracStr.toLong() else fracStr.toLong() * 10
            val primaryMs = (m * 60 + s) * 1000 + frac

            rows.add(LyricRow(primaryMs, tagText, text))

            for (match in matches) {
                val mm = match.groupValues[1].toLong()
                val ss = match.groupValues[2].toLong()
                val ffStr = match.groupValues[3]
                val ff = if (ffStr.length == 3) ffStr.toLong() else ffStr.toLong() * 10
                val ms = (mm * 60 + ss) * 1000 + ff
                if (!byTime.containsKey(ms)) {
                    byTime[ms] = text
                }
            }
        }
        return ParsedLyric(rows, byTime)
    }

    private fun findTimedExtraText(parsed: ParsedLyric?, timeMs: Long, usedKeys: MutableSet<String>, toleranceMs: Long = 650): String {
        if (parsed == null) return ""

        val exact = parsed.byTime[timeMs]
        if (exact != null) {
            val key = "${timeMs}\u0000$exact"
            if (!usedKeys.contains(key)) {
                usedKeys.add(key)
                return exact
            }
        }

        var bestRow: LyricRow? = null
        var bestDiff = Long.MAX_VALUE
        var bestKey = ""

        for (row in parsed.rows) {
            val diff = abs(row.timeMs - timeMs)
            if (diff > toleranceMs) continue
            val key = "${row.timeMs}\u0000${row.text}"
            if (usedKeys.contains(key)) continue

            if (diff < bestDiff) {
                bestDiff = diff
                bestRow = row
                bestKey = key
            }
        }

        if (bestRow == null) return ""
        usedKeys.add(bestKey)
        return bestRow.text
    }

    private fun mergeTimedLyrics(mainLyrics: String?, romajiLyrics: String?, translatedLyrics: String?): String? {
        if (mainLyrics.isNullOrBlank()) return null
        val main = parseTimedLyric(mainLyrics)
        if (main.rows.isEmpty()) return null

        val romaji = parseTimedLyric(romajiLyrics)
        val translation = parseTimedLyric(translatedLyrics)

        val usedRomaji = mutableSetOf<String>()
        val usedTranslation = mutableSetOf<String>()
        val merged = mutableListOf<String>()

        for (row in main.rows) {
            merged.add("${row.tagText}${row.text}")

            val seen = mutableSetOf(row.text)
            val extras = listOf(
                findTimedExtraText(romaji, row.timeMs, usedRomaji),
                findTimedExtraText(translation, row.timeMs, usedTranslation)
            )

            for (extra in extras) {
                val text = extra.trim()
                if (text.isEmpty() || seen.contains(text)) continue
                merged.add("${row.tagText}$text")
                seen.add(text)
            }
        }

        return merged.joinToString("\n")
    }
}