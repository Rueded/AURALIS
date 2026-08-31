package com.auralis.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// dateModified: 秒级 Unix 时间戳，跟 Song.kt 里 dateModified 的单位（MediaStore 的老规矩，秒不是毫秒）保持一致。
// 默认 0——如果电脑那边的 /api/sync 接口还没升级、JSON 里没这个字段，Gson 反序列化时会自动留 0，
// 不会崩，只是那些歌在按时间排序时会一起排到最前面（当成"最老"）。
data class RemoteSong(val filename: String, val size: Long, val md5: String = "", val has_lrc: Boolean = false, val dateModified: Long = 0L)
data class SyncItem(val remoteSong: RemoteSong, var isSelected: Boolean = true)

object SyncManager {
    // 把超时时间拉长，防止网络波动断掉大文件
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun fetchMissingSongs(
        context: Context,
        serverIp: String,
        existingSongs: List<Song>, // 保留这个参数防止上层报错，但内部不再依赖它
        saveFolderUri: android.net.Uri?
    ): List<SyncItem> = withContext(Dispatchers.IO) {
        val baseUrl = "http://$serverIp:5000"
        val request = Request.Builder().url("$baseUrl/api/sync").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception(context.getString(R.string.cannot_connect_to_pc))

        val json = response.body?.string() ?: "[]"
        val type = object : TypeToken<List<RemoteSong>>() {}.type
        val remoteSongs: List<RemoteSong> = gson.fromJson(json, type)

        // 【修复 1：抛弃懒惰的媒体库，直接读取真实的文件夹】
        val realLocalFiles = mutableSetOf<String>()
        saveFolderUri?.let { uri ->
            val rootFolder = DocumentFile.fromTreeUri(context, uri)
            rootFolder?.listFiles()?.forEach { file ->
                file.name?.let { realLocalFiles.add(it) }
            }
        }

        remoteSongs.map { remote ->
            // 直接在真实的文件夹里对比，绝对精准！
            val audioExists = realLocalFiles.contains(remote.filename)
            val lrcFilename = remote.filename.substringBeforeLast(".") + ".lrc"
            val lrcExists = realLocalFiles.contains(lrcFilename)

            val needsSync = !audioExists || (remote.has_lrc && !lrcExists)
            SyncItem(remoteSong = remote, isSelected = needsSync)
        }.filter {
            // 只显示缺音频或缺歌词的歌曲，清爽至极
            it.isSelected
        }.sortedWith(
            // 主排序：最近改动时间倒序。次排序：文件名——Kotlin 的 sortedWith 是稳定排序，
            // 时间完全相同/并列时不加这个兜底的话，顺序会退化成电脑那边 os.scandir() 的原始扫描顺序，
            // 那个顺序既不是时间也不是字母，看起来就是"乱的"
            compareByDescending<SyncItem> { it.remoteSong.dateModified }
                .thenBy { it.remoteSong.filename.lowercase() }
        )
    }

    // 同时开几个下载任务。太大电脑那边的 Flask 开发服务器扛不住，3~4 是比较稳的经验值
    private const val MAX_CONCURRENT_DOWNLOADS = 3

    suspend fun downloadSelected(
        context: Context, serverIp: String, songsToDownload: List<RemoteSong>,
        saveFolderUri: android.net.Uri,
        onLog: (String) -> Unit, onProgress: (Float) -> Unit,
        // onComplete 带上实际的成功/失败数量，调用方（SyncService）才有办法知道这次同步是不是真的成功了，
        // 而不是无论下没下到东西，通知栏一律显示"完成"
        onComplete: (successCount: Int, failCount: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val baseUrl = "http://$serverIp:5000"
        val rootFolder = DocumentFile.fromTreeUri(context, saveFolderUri)
        if (rootFolder == null) {
            onLog(context.getString(R.string.cannot_access_selected_folder))
            delay(2000)
            onComplete(0, songsToDownload.size)
            return@withContext
        }

        onLog(context.getString(R.string.scanning_local_directory))
        // 一次性把所有文件加载到内存字典里，干掉 findFile() 卡顿毒瘤
        // 【并发修复】改用 ConcurrentHashMap：多个下载协程会同时读写这个表，普通 HashMap 在并发场景下不安全
        val existingFiles = ConcurrentHashMap<String, DocumentFile>()
        try {
            rootFolder.listFiles().forEach { file ->
                file.name?.let { existingFiles[it] = file }
            }
        } catch (e: Exception) {
            onLog(context.getString(R.string.sync_error_prefix, e.message))
        }

        val total = songsToDownload.size
        val completedCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
        // 多个协程会并发调用 onLog，日志文本本身没有加锁保护，这里用一把锁保证每条日志完整、不交错
        val logLock = Any()

        coroutineScope {
            songsToDownload.map { song ->
                async {
                    semaphore.withPermit {
                        // 【核心修复：每首歌单独包一层 try/catch】
                        // 以前这层 try/catch 是包在最外面、整个下载循环外面的：只要有一首歌因为网络抖动/
                        // 连接被拒/服务器一时半会没反应过来而抛异常，就会直接中断——并发场景下体现为
                        // 这一个任务的异常把 coroutineScope 里其他还在跑的任务全部连带取消，
                        // 后面明明能正常下载的歌一首都下不到，onComplete() 却还是照样被调用，
                        // 通知栏显示"同步完成"，实际上什么都没下到。现在每首歌的异常只影响它自己。
                        try {
                            val progressPrefix = "[${completedCount.get() + 1}~${total}]"
                            val audioFile = existingFiles[song.filename]

                            // 不仅查文件是否存在，还要查文件大小是否完全一致
                            val needsDownload = audioFile == null || audioFile.length() != song.size
                            var audioOk = true

                            if (needsDownload) {
                                synchronized(logLock) {
                                    if (audioFile != null) onLog(context.getString(R.string.incomplete_file_redownload, progressPrefix, song.filename))
                                    else onLog(context.getString(R.string.downloading_audio, progressPrefix, song.filename))
                                }
                                // 并发下载时不再逐字节汇报单曲进度（多首歌同时跑，单条进度条已经没意义），
                                // 进度条改成"已完成文件数 / 总数"，见下面 onProgress 的调用
                                audioOk = downloadFile(context, "$baseUrl/download/${song.filename}", song.filename, rootFolder, existingFiles) { }
                                if (!audioOk) {
                                    synchronized(logLock) {
                                        onLog(context.getString(R.string.sync_error_prefix, "${song.filename} 下载失败（服务器无响应或连接被拒）"))
                                    }
                                }
                            } else {
                                synchronized(logLock) {
                                    onLog(context.getString(R.string.audio_exists_complete_skip, progressPrefix, song.filename))
                                }
                            }

                            // 歌词下载失败不算整首歌失败——没歌词还能正常听歌，别因为这个把这首歌计入失败
                            if (audioOk && song.has_lrc) {
                                val lrcFilename = song.filename.substringBeforeLast(".") + ".lrc"
                                val lrcFile = existingFiles[lrcFilename]

                                if (lrcFile == null) {
                                    synchronized(logLock) {
                                        onLog(context.getString(R.string.completing_lyrics, progressPrefix, lrcFilename))
                                    }
                                    downloadFile(context, "$baseUrl/download/$lrcFilename", lrcFilename, rootFolder, existingFiles) { }
                                }
                            }

                            if (audioOk) successCount.incrementAndGet() else failCount.incrementAndGet()
                        } catch (e: Exception) {
                            failCount.incrementAndGet()
                            synchronized(logLock) {
                                onLog(context.getString(R.string.sync_error_prefix, "${song.filename}: ${e.message}"))
                            }
                        }

                        val done = completedCount.incrementAndGet()
                        onProgress(done.toFloat() / total)
                    }
                }
            }.awaitAll()
        }

        val finalSuccess = successCount.get()
        val finalFail = failCount.get()
        if (finalFail == 0) {
            onLog(context.getString(R.string.sync_all_done))
        } else {
            onLog(context.getString(R.string.sync_error_prefix, "$finalSuccess 首成功，$finalFail 首失败——具体原因看上面的日志"))
        }
        delay(1500)
        onComplete(finalSuccess, finalFail)
    }

    /** 返回值表示这一次下载是否真的成功，调用方（SyncService）要靠这个来判断，不能假设"没抛异常就是成功"。 */
    private fun downloadFile(
        context: Context, url: String, filename: String,
        rootFolder: DocumentFile,
        existingMap: MutableMap<String, DocumentFile>,
        onProgress: (Float) -> Unit
    ): Boolean {
        val req = Request.Builder().url(url).build()
        return try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return false
                val body = res.body ?: return false
                val totalBytes = body.contentLength()

                // 使用二进制流，防止安卓强加 .txt 后缀
                val mimeType = if (filename.endsWith(".lrc")) "application/octet-stream" else "audio/*"

                existingMap[filename]?.delete()

                val newFile = rootFolder.createFile(mimeType, filename) ?: return false
                existingMap[filename] = newFile

                val outputStream: OutputStream = context.contentResolver.openOutputStream(newFile.uri) ?: return false

                body.byteStream().use { input ->
                    outputStream.use { output ->
                        // 8KB → 64KB：减少 read()/write() 的调用次数，SAF 每次 write 都有一次 Binder IPC 开销，
                        // 缓冲区越小、IPC 次数越多，大文件（尤其无损音频）差距会比较明显
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var copiedBytes = 0L
                        while (input.read(buffer).also { bytesRead = it } >= 0) {
                            output.write(buffer, 0, bytesRead)
                            copiedBytes += bytesRead
                            if (totalBytes > 0) onProgress(copiedBytes.toFloat() / totalBytes)
                        }
                    }
                }
                true
            }
        } catch (e: Exception) {
            // 网络层异常（连接被拒、超时、连接被服务器重置……）在这里兜底，
            // 不让它往上冒泡打断同一批里其他还在跑的并发下载任务
            false
        }
    }
}