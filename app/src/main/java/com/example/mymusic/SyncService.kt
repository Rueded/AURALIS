package com.example.mymusic // 换成你的包名

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val channelId = "sync_channel"
    private val notificationId = 1001
    private var lastLog = "准备下载..."

    override fun onCreate() {
        super.onCreate()
        // 安卓 8.0 以上必须创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "音乐同步进度", NotificationManager.IMPORTANCE_LOW)
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
                onComplete = {
                    serviceScope.launch {
                        updateNotification("🎉 同步大功告成！", 0, 0, false)

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
                                android.util.Log.d("SyncService", "系统 MediaStore 已成功登记文件: $path")
                            }
                        }

                        // 给系统数据库一点登记的反应时间
                        delay(1000)

                        // 任务真正完成，发送广播通知主界面刷新
                        val broadcastIntent = Intent("com.example.mymusic.SYNC_COMPLETED")
                        sendBroadcast(broadcastIntent)

                        delay(1000)
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
            .setContentTitle("局域网音乐同步")
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