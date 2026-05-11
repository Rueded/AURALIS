package com.example.mymusic // 保持你自己的包名

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File

object MusicUtils {

    // 【终极进化完全体】：同步引擎 + 幽灵清道夫 + 自动元数据纠正
    suspend fun syncLocalMusicToDatabase(
        context: Context,
        songDao: SongDao,
        allowedFolders: Set<String> = emptySet()
    ) {
        withContext(Dispatchers.IO) {
            val songList = mutableListOf<Song>()
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.ALBUM_ID
            )

            var selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            var selectionArgs: Array<String>? = null

            if (allowedFolders.isNotEmpty()) {
                val folderConditions = allowedFolders.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }
                selection += " AND ($folderConditions)"
                selectionArgs = allowedFolders.map { "%/$it/%" }.toTypedArray()
            }

            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val data = cursor.getString(dataCol) ?: continue
                    val file = File(data)

                    // 1. 如果文件物理上都不存在，直接跳过（后面会有大扫除清理数据库）
                    if (!file.exists()) continue

                    // 2. 先拿系统提供的数据（可能残缺）
                    val id = cursor.getLong(idCol)
                    var title = cursor.getString(titleCol) ?: "未知歌名"
                    var artist = cursor.getString(artistCol) ?: "未知歌手"
                    var duration = cursor.getLong(durationCol)
                    var size = cursor.getLong(sizeCol)
                    val dateModified = cursor.getLong(dateCol)
                    val albumId = cursor.getLong(albumIdCol)

                    // 3. 【核心修正】：如果系统给的数据不靠谱，App 自己搜身
                    if (size <= 0L) {
                        size = file.length() // 物理大小永远最真实
                    }

                    val existingSong = songDao.getSongByPath(data)
                    // 只有当是新歌，或者文件被改动过，才走耗时的 jaudiotagger 解析
                    if (existingSong == null || existingSong.dateModified < dateModified) {

                        var gain = 0f
                        var bits = 16
                        var sampleRate = 44100

                        try {
                            // 关闭 jaudiotagger 的啰嗦日志
                            java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF
                            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
                            val header = audioFile.audioHeader
                            val tag = audioFile.tag

                            // 从 Header 补全位深、采样率和时长
                            bits = header.bitsPerSample
                            sampleRate = header.sampleRateAsNumber
                            if (duration <= 0L) {
                                duration = (header.trackLength * 1000).toLong()
                            }

                            // 👇 【关键纠正】：如果系统索引没登记好，我们从 Tag 强行扒出歌手名
                            if (artist == "未知歌手" || artist.contains("unknown", true)) {
                                artist = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST) ?: "未知歌手"
                            }
                            if (title == "未知歌名" || title.contains("unknown", true)) {
                                title = tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) ?: file.nameWithoutExtension
                            }

                            // 解析 ReplayGain (反射避错版)
                            if (tag != null) {
                                try {
                                    val fieldKeys = org.jaudiotagger.tag.FieldKey.values()
                                    val replayGainKey = fieldKeys.find { it.name == "REPLAYGAIN_TRACK_GAIN" }
                                    var gainStr = if (replayGainKey != null) tag.getFirst(replayGainKey) ?: "" else ""

                                    if (gainStr.isBlank()) {
                                        val fields = tag.fields
                                        while (fields.hasNext()) {
                                            val f = fields.next()
                                            if (f.id.uppercase().contains("REPLAYGAIN_TRACK_GAIN")) {
                                                gainStr = f.toString()
                                                break
                                            }
                                        }
                                    }

                                    if (gainStr.isNotBlank()) {
                                        val rawValue = if (gainStr.contains(":")) gainStr.substringAfterLast(":") else gainStr
                                        val cleanNum = rawValue.replace(Regex("[^0-9.\\-]"), "")
                                        gain = cleanNum.toFloatOrNull() ?: 0f
                                    }
                                } catch (e: Exception) {
                                    Log.e("MusicUtils", "Gain解析异常: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("MusicUtils", "文件深度解析失败: $data")
                        }

                        songList.add(
                            Song(
                                data = data, id = id, title = title, artist = artist,
                                duration = duration, size = size, dateModified = dateModified,
                                albumId = albumId, replayGain = gain, bitDepth = bits, samplingRate = sampleRate
                            )
                        )
                    }
                }
            }

            // 4. 入库新扫到的数据
            if (songList.isNotEmpty()) {
                songDao.insertSongs(songList)
                Log.d("MusicApp", "✅ 成功入库 ${songList.size} 首新解析的歌曲")
            }

            // 👇 5. 【幽灵清道夫】：清理数据库中已不存在的物理文件
            try {
                val allDbSongs = songDao.getAllSongs().first()
                val ghostSongs = allDbSongs.filter { !File(it.data).exists() }

                if (ghostSongs.isNotEmpty()) {
                    val dbHelper = AppDatabase.getDatabase(context).openHelper.writableDatabase
                    dbHelper.beginTransaction()
                    try {
                        ghostSongs.forEach { ghost ->
                            dbHelper.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(ghost.data))
                            dbHelper.execSQL("DELETE FROM playlist_songs WHERE songPath = ?", arrayOf(ghost.data))
                        }
                        dbHelper.setTransactionSuccessful()
                    } finally {
                        dbHelper.endTransaction()
                    }
                    Log.d("MusicApp", "🧹 清理了 ${ghostSongs.size} 首幽灵数据")
                }
            } catch (e: Exception) {
                Log.e("MusicApp", "幽灵清理出错: ${e.message}")
            }
        }
    }
}