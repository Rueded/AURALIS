package com.example.mymusic // 换成你的包名

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class RemoteSong(val filename: String, val size: Long, val md5: String, val has_lrc: Boolean = false)
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
        if (!response.isSuccessful) throw Exception("无法连接到电脑")

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
        }
    }

    suspend fun downloadSelected(
        context: Context, serverIp: String, songsToDownload: List<RemoteSong>,
        saveFolderUri: android.net.Uri,
        onLog: (String) -> Unit, onProgress: (Float) -> Unit, onComplete: () -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val baseUrl = "http://$serverIp:5000"
            val rootFolder = DocumentFile.fromTreeUri(context, saveFolderUri)
                ?: throw Exception("无法访问所选文件夹")

            onLog("正在扫描本地目录，准备极速引擎...")
            // 一次性把所有文件加载到内存字典里，干掉 findFile() 卡顿毒瘤
            val existingFiles = mutableMapOf<String, DocumentFile>()
            rootFolder.listFiles().forEach { file ->
                file.name?.let { existingFiles[it] = file }
            }

            for ((index, song) in songsToDownload.withIndex()) {
                // 【修复 2：进度前缀，防止被立刻覆盖】
                val progressPrefix = "[${index + 1}/${songsToDownload.size}]"
                onProgress(0f)

                // 瞬间判定
                val audioFile = existingFiles[song.filename]

                // 【核心修复】：不仅查文件是否存在，还要查文件大小是否完全一致！
                val needsDownload = audioFile == null || audioFile.length() != song.size

                if (needsDownload) {
                    if (audioFile != null) onLog("$progressPrefix 发现不完整文件，重新下载: ${song.filename}")
                    else onLog("$progressPrefix 正在下载音频: ${song.filename}")

                    downloadFile(context, "$baseUrl/download/${song.filename}", song.filename, rootFolder, existingFiles, onProgress)
                } else {
                    onLog("$progressPrefix 音频已存在且完整，跳过: ${song.filename}")
                    onProgress(1f)
                }

                if (song.has_lrc) {
                    val lrcFilename = song.filename.substringBeforeLast(".") + ".lrc"
                    val lrcFile = existingFiles[lrcFilename]

                    if (lrcFile == null) {
                        onLog("$progressPrefix 正在补全歌词: $lrcFilename")
                        downloadFile(context, "$baseUrl/download/$lrcFilename", lrcFilename, rootFolder, existingFiles) { }
                    }
                }
            }
            onLog("🎉 全部处理完成！")
            delay(1500)
            onComplete()
        } catch (e: Exception) {
            onLog("❌ 错误: ${e.message}")
            delay(2000)
            onComplete()
        }
    }

    private fun downloadFile(
        context: Context, url: String, filename: String,
        rootFolder: DocumentFile,
        existingMap: MutableMap<String, DocumentFile>,
        onProgress: (Float) -> Unit
    ) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return
            val body = res.body ?: return
            val totalBytes = body.contentLength()

            // 【修复 3：使用二进制流，防止安卓强加 .txt 后缀】
            val mimeType = if (filename.endsWith(".lrc")) "application/octet-stream" else "audio/*"

            existingMap[filename]?.delete()

            val newFile = rootFolder.createFile(mimeType, filename) ?: return
            existingMap[filename] = newFile

            val outputStream: OutputStream = context.contentResolver.openOutputStream(newFile.uri) ?: return

            body.byteStream().use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var copiedBytes = 0L
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        output.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                        if (totalBytes > 0) onProgress(copiedBytes.toFloat() / totalBytes)
                    }
                }
            }
        }
    }
}