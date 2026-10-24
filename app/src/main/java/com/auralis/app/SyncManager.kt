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
import android.provider.DocumentsContract
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// dateModified: 秒级 Unix 时间戳，跟 Song.kt 里 dateModified 的单位（MediaStore 的老规矩，秒不是毫秒）保持一致。
// 默认 0——如果电脑那边的 /api/sync 接口还没升级、JSON 里没这个字段，Gson 反序列化时会自动留 0，
// 不会崩，只是那些歌在按时间排序时会一起排到最前面（当成"最老"）。
data class RemoteSong(val filename: String, val size: Long, val md5: String = "", val has_lrc: Boolean = false, val dateModified: Long = 0L)
data class SyncItem(val remoteSong: RemoteSong, var isSelected: Boolean = true)

// 本地文件夹里一个已存在文件：URI + 大小。一次查询就能拿到，不用再对每个文件单独 Binder 查询一遍。
private data class ChildInfo(val uri: Uri, val size: Long)

// 性能统计：把"等数据"和"写存储"各自花了多少时间分开记，用来判断瓶颈在网络还是在手机写入。
private class PerfStats {
    val bytes   = AtomicLong(0)
    val readNs  = AtomicLong(0)
    val writeNs = AtomicLong(0)
    val ttfbNs  = AtomicLong(0)
    val files   = AtomicInteger(0)
}

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
        val baseUrl = "http://${serverIp.trim()}:5000"
        val request = Request.Builder().url("$baseUrl/api/sync").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception(context.getString(R.string.cannot_connect_to_pc))

        val json = response.body?.string() ?: "[]"
        val type = object : TypeToken<List<RemoteSong>>() {}.type
        val remoteSongs: List<RemoteSong> = gson.fromJson(json, type)

        // 【修复 1：抛弃懒惰的媒体库，直接读取真实的文件夹】
        // DocumentFile.listFiles() 之后对每个文件调用 name/length() 都是一次 Binder 查询，
        // 几千首歌的目录光"扫描本地目录"就要卡几十秒；改成一次游标查询拿到全部名字和大小。
        val realLocalFiles: Set<String> = saveFolderUri?.let { uri ->
            try { listChildren(context, uri).keys.toSet() } catch (_: Exception) { emptySet() }
        } ?: emptySet()

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
        val baseUrl = "http://${serverIp.trim()}:5000"
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
        val existingFiles = ConcurrentHashMap<String, ChildInfo>()
        try {
            existingFiles.putAll(listChildren(context, saveFolderUri))
        } catch (e: Exception) {
            onLog(context.getString(R.string.sync_error_prefix, e.message))
        }

        val total = songsToDownload.size
        val completedCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val avgSize = if (songsToDownload.isEmpty()) 0L else songsToDownload.sumOf { it.size } / songsToDownload.size
        val parallelism = if (avgSize in 1 until 8L * 1024 * 1024) MAX_CONCURRENT_DOWNLOADS + 1 else MAX_CONCURRENT_DOWNLOADS
        val semaphore = Semaphore(parallelism)
        val perf = PerfStats()
        val syncStartNs = System.nanoTime()
        onLog("并发 $parallelism 路 · 平均每首 ${avgSize / 1048576} MB")
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
                            val needsDownload = audioFile == null || audioFile.size != song.size
                            var audioOk = true

                            if (needsDownload) {
                                synchronized(logLock) {
                                    if (audioFile != null) onLog(context.getString(R.string.incomplete_file_redownload, progressPrefix, song.filename))
                                    else onLog(context.getString(R.string.downloading_audio, progressPrefix, song.filename))
                                }
                                // 并发下载时不再逐字节汇报单曲进度（多首歌同时跑，单条进度条已经没意义），
                                // 进度条改成"已完成文件数 / 总数"，见下面 onProgress 的调用
                                audioOk = downloadWithRetry(context, downloadUrl(baseUrl, song.filename), song.filename, rootFolder, existingFiles, perf)
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
                                    downloadWithRetry(context, downloadUrl(baseUrl, lrcFilename), lrcFilename, rootFolder, existingFiles, perf)
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

        // 速度汇总：告诉用户平均速度，并指出瓶颈在"网络/电脑端"还是"手机写入"
        val wallSec = (System.nanoTime() - syncStartNs) / 1e9
        val mb = perf.bytes.get() / 1048576.0
        if (perf.bytes.get() > 0 && wallSec > 0) {
            val readMs = perf.readNs.get().toDouble(); val writeMs = perf.writeNs.get().toDouble()
            val readPct = (readMs / (readMs + writeMs + 1) * 100).toInt()
            onLog(context.getString(R.string.sync_perf_summary, String.format("%.1f", mb / wallSec), String.format("%.0f", mb), wallSec.toInt()))
            onLog(context.getString(R.string.sync_perf_detail, readPct, 100 - readPct))
            android.util.Log.i("SyncPerf", "总 ${"%.1f".format(mb)}MB 用时 ${"%.1f".format(wallSec)}s 平均 ${"%.1f".format(mb / wallSec)}MB/s " +
                "首字节平均 ${perf.ttfbNs.get() / 1e6 / perf.files.get().coerceAtLeast(1)}ms 读等待 ${"%.1f".format(readMs / 1e9)}s 写存储 ${"%.1f".format(writeMs / 1e9)}s 并发 $parallelism")
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

    private fun downloadUrl(baseUrl: String, filename: String) =
        baseUrl.toHttpUrl().newBuilder().addPathSegment("download").addPathSegment(filename).build().toString()

    /** 一次查询拿到文件夹里所有文件的 名字→(URI, 大小)。 */
    private fun listChildren(context: Context, treeUri: Uri): ConcurrentHashMap<String, ChildInfo> {
        val map = ConcurrentHashMap<String, ChildInfo>()
        val docId = if (DocumentsContract.isDocumentUri(context, treeUri)) DocumentsContract.getDocumentId(treeUri)
                    else DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                map[name] = ChildInfo(DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)), c.getLong(2))
            }
        }
        return map
    }

    /** 失败自动重试 2 次（间隔 0.8s、2.5s）。Wi-Fi 偶发抖动不该让整首歌直接算失败。 */
    private fun downloadWithRetry(
        context: Context, url: String, filename: String,
        rootFolder: DocumentFile, existingMap: MutableMap<String, ChildInfo>, perf: PerfStats
    ): Boolean {
        for (waitMs in longArrayOf(0L, 800L, 2500L)) {
            if (waitMs > 0) Thread.sleep(waitMs)
            if (downloadFile(context, url, filename, rootFolder, existingMap, perf)) return true
        }
        return false
    }

    /** 返回值表示这一次下载是否真的成功，调用方（SyncService）要靠这个来判断，不能假设"没抛异常就是成功"。 */
    private fun downloadFile(
        context: Context, url: String, filename: String,
        rootFolder: DocumentFile,
        existingMap: MutableMap<String, ChildInfo>,
        perf: PerfStats
    ): Boolean {
        var createdUri: Uri? = null
        return try {
            val t0 = System.nanoTime()
            // 音频本来就压缩过，要求服务器别再 gzip：
            // OkHttp 默认会带 Accept-Encoding: gzip，如果电脑端的服务开了压缩，
            // 就会白白耗电脑 CPU 去压缩 flac/mp3，速度被拖慢，而且响应头里没有 Content-Length，进度也不准。
            val req = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return false
                val body = res.body ?: return false
                perf.ttfbNs.addAndGet(System.nanoTime() - t0)
                perf.files.incrementAndGet()
                val totalBytes = body.contentLength()

                // 使用二进制流，防止安卓强加 .txt 后缀
                val mimeType = if (filename.endsWith(".lrc")) "application/octet-stream" else "audio/*"

                existingMap[filename]?.let {
                    try { DocumentsContract.deleteDocument(context.contentResolver, it.uri) } catch (_: Exception) {}
                }

                val newFile = rootFolder.createFile(mimeType, filename) ?: return false
                createdUri = newFile.uri
                existingMap[filename] = ChildInfo(newFile.uri, 0L)

                val rawOut = context.contentResolver.openOutputStream(newFile.uri) ?: return false
                // 关键：OkHttp 的 read() 每次最多只给 8KB（一个 Okio Segment），
                // 以前代码里那个"64KB 缓冲区"只是个数组大小，并不会让每次 read 变大；
                // 于是每 8KB 就是一次 write 系统调用，穿过 SAF/FUSE 到存储，次数极多、非常慢。
                // 在输出端包一层 256KB 的缓冲，把小块攒成大块再写。
                val output = BufferedOutputStream(rawOut, 256 * 1024)

                body.byteStream().use { input ->
                    output.use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var copiedBytes = 0L
                        while (true) {
                            val r0 = System.nanoTime()
                            val n = input.read(buffer)
                            val r1 = System.nanoTime()
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            perf.readNs.addAndGet(r1 - r0)
                            perf.writeNs.addAndGet(System.nanoTime() - r1)
                            copiedBytes += n
                            if (totalBytes > 0) { /* 单曲进度在并发下载时没有意义，不回调 */ }
                        }
                        val f0 = System.nanoTime()
                        out.flush()
                        perf.writeNs.addAndGet(System.nanoTime() - f0)
                        perf.bytes.addAndGet(copiedBytes)
                        if (totalBytes > 0 && copiedBytes != totalBytes) {
                            throw java.io.IOException("文件不完整 $copiedBytes/$totalBytes")
                        }
                    }
                }
                true
            }
        } catch (e: Exception) {
            // 网络层异常（连接被拒、超时、连接被服务器重置……）在这里兜底，
            // 不让它往上冒泡打断同一批里其他还在跑的并发下载任务。
            // 半截文件删掉：以前留在那里，下次同步还得靠"大小不一致"才会重下。
            createdUri?.let { try { DocumentsContract.deleteDocument(context.contentResolver, it) } catch (_: Exception) {} }
            existingMap.remove(filename)
            android.util.Log.w("SyncPerf", "下载失败 $filename: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
