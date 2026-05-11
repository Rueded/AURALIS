package com.example.mymusic // 保持你自己的包名

import androidx.room.Entity
import androidx.room.PrimaryKey

// @Entity 告诉 Room 这是一张数据库表，表名叫 "songs"
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey // 使用物理文件路径作为主键，绝对唯一，防止重复插入
    val data: String,

    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val size: Long = 0L,
    val dateModified: Long = 0L,
    val albumId: Long = 0L,

    // 👇 数据库专属：为未来的“听歌大数据”埋下伏笔！
    val isFavorite: Boolean = false, // 是否红心喜欢
    val playCount: Int = 0,          // 播放总次数
    val lastPlayed: Long = 0L,        // 最后一次播放的时间戳
    val replayGain: Float = 0f,
    // 👇 新增：存储高级音频参数
    val bitDepth: Int = 16,       // 位深度 (默认 16)
    val samplingRate: Int = 44100 // 采样率 (默认 44100)
)

// 记录每一次真实的收听，用于年月日统计
@Entity(tableName = "play_history")
data class PlayHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songPath: String,
    val timestamp: Long, // 播放的时间
    val durationListened: Long // 听了多久
)

// 歌单基本信息
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

// 歌单与歌曲的关联表 (多对多)
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songPath"]
)
data class PlaylistSong(
    val playlistId: Long,
    val songPath: String
)