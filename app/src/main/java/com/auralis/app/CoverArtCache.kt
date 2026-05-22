package com.auralis.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 封面磁盘/内存缓存：使用路径 MD5 作为键，避免 hashCode 冲突导致串图。
 */
object CoverArtCache {
    private const val TAG = "CoverArtCache"
    private const val DIR = "audio_meta_cache"

    fun pathToKey(path: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun imageFile(context: Context, path: String): File =
        File(cacheDir(context), "${pathToKey(path)}.webp")

    fun metaFile(context: Context, path: String): File =
        File(cacheDir(context), "${pathToKey(path)}.txt")

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, DIR).also { if (!it.exists()) it.mkdirs() }

    /** 删除指定歌曲的封面缓存（含旧 hashCode 键，便于迁移） */
    fun invalidate(context: Context, path: String) {
        AudioCache.removeFromMemory(path)
        imageFile(context, path).delete()
        metaFile(context, path).delete()
        // 清理旧版 hashCode 缓存
        val legacy = File(cacheDir(context), "${path.hashCode()}.webp")
        val legacyTxt = File(cacheDir(context), "${path.hashCode()}.txt")
        legacy.delete()
        legacyTxt.delete()
    }

    fun invalidateAll(context: Context) {
        AudioCache.clearMemory()
        cacheDir(context).deleteRecursively()
        cacheDir(context).mkdirs()
    }

    fun loadBitmapFromDisk(context: Context, path: String): Bitmap? {
        val file = imageFile(context, path)
        if (!file.exists()) {
            val legacy = File(cacheDir(context), "${path.hashCode()}.webp")
            if (!legacy.exists()) return null
            return BitmapFactory.decodeFile(legacy.absolutePath)
        }
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun saveBitmap(context: Context, path: String, bitmap: Bitmap) {
        try {
            imageFile(context, path).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存封面失败: ${e.message}")
        }
    }

    /**
     * 加载封面：内嵌 → 磁盘 → 网络；仅在 path 仍为当前播放时更新全局主题。
     */
    suspend fun loadCover(
        context: Context,
        path: String,
        title: String,
        artist: String,
        forceNetwork: Boolean = false,
        updateGlobalTheme: Boolean = true
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (path.isEmpty()) return@withContext null

        val requestPath = path
        var bitmap: Bitmap? = null

        if (!forceNetwork) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(path)
                val pic = retriever.embeddedPicture
                retriever.release()
                if (pic != null) {
                    bitmap = BitmapFactory.decodeByteArray(pic, 0, pic.size)
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取内嵌封面失败: ${e.message}")
            }

            if (bitmap == null) {
                bitmap = loadBitmapFromDisk(context, path)
            }
        }

        if (bitmap == null || forceNetwork) {
            bitmap = CoverFetcher.fetchHighResCover(title, artist)
            if (bitmap == null && !forceNetwork) {
                bitmap = loadAlbumArtFromMediaStore(context, path)
            }
        }

        bitmap?.let { bmp ->
            saveBitmap(context, path, bmp)
            val image = bmp.asImageBitmap()
            AudioCache.putCoverInMemory(path, image)
            if (updateGlobalTheme && requestPath == PlayerStateHolder.currentPath) {
                PlayerStateHolder.updateFromBitmap(bmp, requestPath)
            }
            return@withContext image
        }
        null
    }

    /** 强制重新拉取当前歌曲封面 */
    suspend fun refreshCover(
        context: Context,
        path: String,
        title: String,
        artist: String
    ): ImageBitmap? {
        invalidate(context, path)
        return loadCover(context, path, title, artist, forceNetwork = true, updateGlobalTheme = true)
    }

    /** 为无内嵌封面的歌曲批量重新爬取网络封面 */
    suspend fun refreshAllOnlineCovers(
        context: Context,
        songs: List<Song>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        var refreshed = 0
        songs.forEachIndexed { index, song ->
            val needsNetwork = try {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(song.data)
                val pic = r.embeddedPicture
                r.release()
                pic == null
            } catch (_: Exception) {
                true
            }
            if (needsNetwork) {
                invalidate(context, song.data)
                val bmp = loadCover(
                    context, song.data, song.title, song.artist,
                    forceNetwork = true,
                    updateGlobalTheme = song.data == PlayerStateHolder.currentPath
                )
                if (bmp != null) refreshed++
            }
            onProgress?.invoke(index + 1, songs.size)
        }
        refreshed
    }

    suspend fun loadAlbumArtFromMediaStore(context: Context, path: String): Bitmap? {
        return try {
            val song = AppDatabase.getDatabase(context).songDao().getSongByPath(path) ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                context.contentResolver.loadThumbnail(uri, android.util.Size(400, 400), null)
            } else {
                val artUri = android.net.Uri.parse("content://media/external/audio/albumart/${song.albumId}")
                context.contentResolver.openInputStream(artUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
