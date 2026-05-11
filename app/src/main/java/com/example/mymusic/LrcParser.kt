package com.example.mymusic // 记得换成你的包名

import java.io.File

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    fun parse(audioPath: String): List<LrcLine> {
        if (audioPath.isBlank()) return emptyList()
        val lrcPath = audioPath.substringBeforeLast(".") + ".lrc"
        val file = File(lrcPath)
        if (!file.exists()) return emptyList()

        val lines = mutableListOf<LrcLine>()
        try {
            val timeRegexJson = "\"t\":(\\d+)".toRegex()
            val textRegexJson = "\"tx\":\"(.*?)\"".toRegex()
            val standardRegex = "\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)".toRegex()

            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("{") && trimmed.contains("\"t\":")) {
                    val timeMatch = timeRegexJson.find(line)
                    val textMatches = textRegexJson.findAll(line)
                    if (timeMatch != null) {
                        val timeMs = timeMatch.groupValues[1].toLong()
                        val text = textMatches.joinToString("") { it.groupValues[1] }
                        if (text.isNotBlank()) lines.add(LrcLine(timeMs, text))
                    }
                } else {
                    val matchResult = standardRegex.find(line)
                    if (matchResult != null) {
                        val (m, s, ms, text) = matchResult.destructured
                        val timeMs = m.toLong() * 60000 + s.toLong() * 1000 + ms.padEnd(3, '0').toLong()
                        if (text.isNotBlank()) lines.add(LrcLine(timeMs, text))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return lines.sortedBy { it.timeMs }
    }
}