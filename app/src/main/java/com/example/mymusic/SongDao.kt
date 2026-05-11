package com.example.mymusic // 保持你自己的包名

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<Song>)

    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteSongs(): Flow<List<Song>>

    // 👇 优化：只有听过的才叫最近常听，且按播放次数和时间双重排序
    @Query("SELECT * FROM songs WHERE playCount > 0 ORDER BY playCount DESC, lastPlayed DESC LIMIT 50")
    fun getMostPlayedSongs(): Flow<List<Song>>

    @Query("UPDATE songs SET isFavorite = :isFav WHERE data = :audioPath")
    suspend fun updateFavoriteStatus(audioPath: String, isFav: Boolean)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayed = :timestamp WHERE data = :audioPath")
    suspend fun incrementPlayCount(audioPath: String, timestamp: Long)

    // ── 播放历史统计 (新增) ──
    @Insert
    suspend fun insertHistory(history: PlayHistory)

    // 按月统计播放量 (用于图表或报表)
    // 这里的 :monthStart 和 :monthEnd 是时间戳
    @Query("SELECT COUNT(*) FROM play_history WHERE timestamp BETWEEN :start AND :end")
    suspend fun getPlayCountInPeriod(start: Long, end: Long): Int

    // ── 歌单管理 (新增) ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createPlaylist(playlist: Playlist): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(playlistSong: PlaylistSong)

    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playlist_songs ON songs.data = playlist_songs.songPath 
        WHERE playlist_songs.playlistId = :playlistId
    """)
    fun getSongsInPlaylist(playlistId: Long): Flow<List<Song>>

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songPath = :songPath")
    suspend fun removeSongFromPlaylist(playlistId: Long, songPath: String)

    @Query("SELECT * FROM songs WHERE data = :audioPath LIMIT 1")
    suspend fun getSongByPath(audioPath: String): Song?

    // 👇 建议新增：如果你想在删除文件时也同步清理歌单关联，可以加这个
    @Query("DELETE FROM playlist_songs WHERE songPath = :songPath")
    suspend fun removeSongFromAllPlaylists(songPath: String)
}