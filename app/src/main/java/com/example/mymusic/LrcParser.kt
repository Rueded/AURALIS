package com.example.mymusic

import java.io.File

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {

    private val STANDARD_RE = """\[(\d{2}):(\d{2})[.:](\d{2,3})](.*) """.trimEnd().toRegex()
    private val JSON_TIME_RE = """"t":(\d+)""".toRegex()
    private val JSON_TEXT_RE = """"tx":"(.*?)"""".toRegex()

    /** 从本地 .lrc 文件解析（原有逻辑不变） */
    fun parse(audioPath: String): List<LrcLine> {
        if (audioPath.isBlank()) return emptyList()
        val lrcFile = File(audioPath.substringBeforeLast(".") + ".lrc")
        if (!lrcFile.exists()) return emptyList()
        return try { parseRaw(lrcFile.readText()) } catch (_: Exception) { emptyList() }
    }

    /** 从字符串解析（供 OnlineLyricsRepository 使用） */
    fun parseRaw(raw: String): List<LrcLine> {
        if (raw.isBlank()) return emptyList()
        val lines = mutableListOf<LrcLine>()
        raw.lines().forEach { line ->
            val trimmed = line.trim()
            // JSON 格式（网易云/部分平台的逐字歌词格式）
            if (trimmed.startsWith("{") && trimmed.contains("\"t\":")) {
                val timeMatch = JSON_TIME_RE.find(line)
                val textParts = JSON_TEXT_RE.findAll(line)
                if (timeMatch != null) {
                    val timeMs = timeMatch.groupValues[1].toLong()
                    val text = textParts.joinToString("") { it.groupValues[1] }
                    if (text.isNotBlank()) lines.add(LrcLine(timeMs, text))
                }
            } else {
                // 标准 LRC 格式 [mm:ss.xx]
                val m = STANDARD_RE.find(line) ?: return@forEach
                val (min, sec, ms, text) = m.destructured
                if (text.isBlank()) return@forEach
                val timeMs = min.toLong() * 60_000 +
                        sec.toLong() * 1_000 +
                        ms.padEnd(3, '0').toLong()
                lines.add(LrcLine(timeMs, text.trim()))
            }
        }
        return lines.sortedBy { it.timeMs }
    }
}
