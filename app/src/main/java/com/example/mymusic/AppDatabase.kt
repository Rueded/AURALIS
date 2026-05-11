package com.example.mymusic // 保持你自己的包名

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 👇 核心修复 1：把刚刚新建的三张表全部注册进花名册！
// 👇 核心修复 2：version 从 1 改成 2！告诉系统数据库升级了！
@Database(
    entities = [ Song::class, PlayHistory::class, Playlist::class, PlaylistSong::class ],
    version = 3, // 👇 必须加 1，触发重建！
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 单例模式，确保全局只有一个数据库实例，极度省内存
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "music_library_db" // 保持你原来的数据库文件名
                )
                    // 👇 核心修复 3：因为 version 变成了 2，这行代码会直接摧毁旧库，
                    // 按照你现在最完美的 4 张表结构，重新建一个崭新的数据库！
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}