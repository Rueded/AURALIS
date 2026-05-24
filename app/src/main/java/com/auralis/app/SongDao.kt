package com.auralis.app

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

    // 获取某年所有月份的播放次数（用于年度柱状图）
    @Query("""
    SELECT COUNT(*) FROM play_history 
    WHERE timestamp >= :yearStart AND timestamp < :yearEnd
""")
    suspend fun getTotalPlaysInYear(yearStart: Long, yearEnd: Long): Int

    // 按月聚合：某年每个月的播放次数
    @Query("""
    SELECT strftime('%m', datetime(timestamp/1000, 'unixepoch', 'localtime')) as month,
           COUNT(*) as count
    FROM play_history
    WHERE timestamp >= :yearStart AND timestamp < :yearEnd
    GROUP BY month
    ORDER BY month ASC
""")
    suspend fun getMonthlyPlayCounts(yearStart: Long, yearEnd: Long): List<MonthCount>

    // 某月播放次数最多的歌曲 TOP N
    @Query("""
    SELECT songs.*, COUNT(play_history.id) as cnt
    FROM play_history
    INNER JOIN songs ON songs.data = play_history.songPath
    WHERE play_history.timestamp >= :start AND play_history.timestamp < :end
    GROUP BY play_history.songPath
    ORDER BY cnt DESC
    LIMIT :limit
""")
    suspend fun getTopSongsInPeriod(start: Long, end: Long, limit: Int): List<Song>

    // 获取历史记录跨越的所有年份（用于年份选择器）
    @Query("""
    SELECT DISTINCT strftime('%Y', datetime(timestamp/1000, 'unixepoch', 'localtime')) as year
    FROM play_history
    ORDER BY year DESC
""")
    suspend fun getDistinctYears(): List<String>

    // 获取某年某月的每日播放次数（用于月历热力图）
    @Query("""
    SELECT strftime('%d', datetime(timestamp/1000, 'unixepoch', 'localtime')) as day,
           COUNT(*) as count
    FROM play_history
    WHERE timestamp >= :start AND timestamp < :end
    GROUP BY day
    ORDER BY day ASC
""")
    suspend fun getDailyPlayCounts(start: Long, end: Long): List<DayCount>

    // 总计：历史记录总条数（用于"共听了X首次"展示）
    @Query("SELECT COUNT(*) FROM play_history")
    suspend fun getTotalHistoryCount(): Int

    // 总计：累计收听时长（毫秒）
    @Query("SELECT SUM(durationListened) FROM play_history")
    suspend fun getTotalListenedMs(): Long?

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

@Dao
interface EqPresetDao {
    @Query("SELECT * FROM eq_presets ORDER BY name ASC")
    fun getAllPresets(): Flow<List<EqPreset>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: EqPreset)

    @Query("DELETE FROM eq_presets WHERE id = :id")
    suspend fun deletePreset(id: Int)
}
