package com.auralis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

// 这是一个“全局暂存区”，用于把 MainActivity 里选好的歌传递给后台服务
object SyncTaskQueue {
    var serverIp: String = ""
    var saveFolderUri: android.net.Uri? = null
    var songsToDownload: List<RemoteSong> = emptyList()
}

class SyncService : Service() {

    // 同 PlaybackService：Service 不会因为 Activity.recreate() 跟着重建，
    // 通知栏标题/渠道名这些从这里直接 getString(...) 出来的文案，
    // 得在这单独包一次 Context 才能跟上语言设置。
    override fun attachBaseContext(newBase: Context) {
        val lang = LocalizationManager.readSavedLanguage(newBase)
        super.attachBaseContext(LocalizationManager.wrapContext(newBase, lang))
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val channelId = "sync_channel"
    private val notificationId = 1001
    private var lastLog = ""

    override fun onCreate() {
        super.onCreate()
        // 安卓 8.0 以上必须创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, getString(R.string.music_sync_progress_title), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = SyncTaskQueue.serverIp
        val uri = SyncTaskQueue.saveFolderUri
        val songs = SyncTaskQueue.songsToDownload

        if (ip.isEmpty() || uri == null || songs.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 【极其重要】：启动前台服务，防止被安卓系统杀掉后台！
        val notification = createNotification(lastLog, 0, 0, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notificationId, notification)
        }

        // 开始调用你那个强大的 SyncManager 进行下载
        serviceScope.launch {
            SyncManager.downloadSelected(
                context = this@SyncService,
                serverIp = ip,
                songsToDownload = songs,
                saveFolderUri = uri,
                onLog = { logMsg ->
                    lastLog = logMsg
                    updateNotification(lastLog, 100, 0, true) // 更新文字，进度条转圈
                },
                onProgress = { progress ->
                    val percent = (progress * 100).toInt()
                    updateNotification(lastLog, 100, percent, false) // 实时更新进度条
                },
                onComplete = { successCount, failCount ->
                    serviceScope.launch {
                        // 【关键修复】：以前不管实际下没下到东西，通知栏一律显示"同步完成"。
                        // 现在按真实结果分三种文案：全成功才用庆祝文案，部分失败如实报数字，
                        // 一首都没成功时格外提示检查网络/电脑那边服务是否正常。
                        val summaryText = when {
                            failCount == 0 -> getString(R.string.sync_complete_celebration)
                            successCount == 0 -> "同步失败：${failCount} 首歌一首都没下成功，检查一下电脑和手机是不是在同一个局域网、电脑那边服务是否还在运行"
                            else -> "同步完成：成功 $successCount 首，失败 $failCount 首"
                        }
                        updateNotification(summaryText, 0, 0, false)

                        // 👇 修复 1：直接在内部写一个神级工具函数，把系统的 TreeUri 转换成真实的硬盘绝对路径
                        fun getRealPathFromTreeUri(treeUri: android.net.Uri): String {
                            try {
                                val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                                val split = docId.split(":")
                                val type = split[0]
                                val path = if (split.size > 1) split[1] else ""
                                return if ("primary".equals(type, ignoreCase = true)) {
                                    android.os.Environment.getExternalStorageDirectory().toString() + "/" + path
                                } else {
                                    "/storage/$type/$path" // 兼容外置 SD 卡
                                }
                            } catch (e: Exception) {
                                return ""
                            }
                        }

                        // 只有真的成功下载过东西，才有必要去折腾 MediaScanner——一首都没成功时
                        // 直接跳过，省得系统白扫一堆压根不存在的文件路径
                        if (successCount > 0) {
                            // 👇 修复 2 & 3：使用 getRealPathFromTreeUri 和 song 变量
                            val filePaths = SyncTaskQueue.songsToDownload.mapNotNull { song ->
                                val folderPath = getRealPathFromTreeUri(SyncTaskQueue.saveFolderUri!!)
                                if (folderPath.isNotEmpty()) "$folderPath/${song.filename}" else null
                            }.toTypedArray()

                            // 强迫安卓系统去扫描这些刚下载好的新文件
                            if (filePaths.isNotEmpty()) {
                                android.media.MediaScannerConnection.scanFile(
                                    this@SyncService, // 👈 修复 context 的问题
                                    filePaths,
                                    null
                                ) { path, uri ->
                                    android.util.Log.d("SyncService", "MediaStore registered file: $path")
                                }
                            }

                            // 给系统数据库一点登记的反应时间
                            delay(1000)

                            // 任务真正完成，发送广播通知主界面刷新
                            val broadcastIntent = Intent("com.auralis.app.SYNC_COMPLETED")
                            sendBroadcast(broadcastIntent)
                        }

                        // 全部失败时通知栏多留一会儿，不然错误信息一晃就被收起来了，等于白写
                        delay(if (failCount == 0) 1000 else 4000)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            stopForeground(true)
                        }
                        stopSelf()
                    }
                }
            )
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(text: String, max: Int, progress: Int, indeterminate: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, createNotification(text, max, progress, indeterminate))
    }

    private fun createNotification(text: String, max: Int, progress: Int, indeterminate: Boolean): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.lan_music_sync_channel))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download) // 系统自带的下载小图标
            .setProgress(max, progress, indeterminate)
            .setOngoing(true) // 禁止用户左右滑动清除通知
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel() // 如果服务被摧毁，立刻停止下载
    }
}