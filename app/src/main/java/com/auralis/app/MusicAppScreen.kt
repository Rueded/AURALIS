package com.auralis.app

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import kotlinx.coroutines.isActive // 确保引入了 isActive
import kotlinx.coroutines.flow.first
import kotlin.math.pow
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.DrawScope // 确保绘图作用域被识别
// 如果 drawRect 还是红的，补上这个：
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import android.util.Log
import androidx.compose.material.icons.outlined.DeleteOutline
import com.auralis.app.PlayerStateHolder.dominantColor
import com.auralis.app.ui.theme.AuralisTheme
import kotlin.apply

// ==========================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun MusicAppScreen(shouldOpenPlayer: MutableState<Boolean>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE) }

    val db = remember { AppDatabase.getDatabase(context) }
    val dao = remember { db.songDao() }

    val allSongs by dao.getAllSongs().collectAsState(initial = emptyList())
    val favSongs by dao.getFavoriteSongs().collectAsState(initial = emptyList())
    val topSongs by dao.getMostPlayedSongs().collectAsState(initial = emptyList())
    // 👇 新增：获取所有歌单数据
    val allPlaylists by dao.getAllPlaylists().collectAsState(initial = emptyList())
    var enableReplayGain by remember { mutableStateOf(prefs.getBoolean("enable_replay_gain", false)) }

    // 👇 新增：USB Bit-perfect 开关状态
    var enableBitPerfect by remember { mutableStateOf(prefs.getBoolean("enable_bit_perfect", false)) }

    var pcServerIp by remember { mutableStateOf(prefs.getString("server_ip", "192.168.") ?: "192.168.") }
    var savedFolderUriStr by remember { mutableStateOf(prefs.getString("sync_folder", null)) }
    var allowedFolders by remember { mutableStateOf(prefs.getStringSet("allowed_folders", setOf()) ?: setOf()) }

    val activity = context as MainActivity
    val hasPermission by activity.audioPermissionGranted
    var mediaController by remember { mutableStateOf<MediaController?>(null) }

    var currentTitle by remember { mutableStateOf<String?>(null) }
    var currentArtist by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var miniProgress by remember { mutableFloatStateOf(0f) }
    var currentArtwork by remember { mutableStateOf<ByteArray?>(null) }

    var showFullScreenPlayer by rememberSaveable { mutableStateOf(false) }
    var currentAudioPath by rememberSaveable { mutableStateOf("") }

    var searchQuery by remember { mutableStateOf("") }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffleMode by remember { mutableStateOf(false) }

    var sortType by remember { mutableStateOf(prefs.getString("sort_type", "Name") ?: "Name") }
    var isAscending by remember { mutableStateOf(prefs.getBoolean("is_ascending", true)) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var sleepTimerSeconds by remember { mutableLongStateOf(0L) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    val mainListState = rememberLazyListState()
    var allowMarquee by remember { mutableStateOf(true) }
    val isScrolling = mainListState.isScrollInProgress

    var isPcMode by remember { mutableStateOf(PcAudioReceiver.isReceiving) }

    var songToAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }

    LaunchedEffect(isScrolling) {
        if (isScrolling) {
            allowMarquee = false
        } else {
            delay(1500)
            allowMarquee = true
        }
    }

    LaunchedEffect(sleepTimerSeconds) {
        if (sleepTimerSeconds > 0) {
            delay(1000)
            sleepTimerSeconds--
            if (sleepTimerSeconds == 0L) {
                mediaController?.pause()
                Toast.makeText(context, "睡眠定时器：已暂停播放 💤", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(sortType, isAscending) {
        prefs.edit().putString("sort_type", sortType).putBoolean("is_ascending", isAscending).apply()
    }

    LaunchedEffect(shouldOpenPlayer.value) {
        if (shouldOpenPlayer.value && currentTitle != null) {
            showFullScreenPlayer = true
            shouldOpenPlayer.value = false
        }
    }

    // 👇 修改 1：引入 PagerState，废弃原本的 selectedTab
    val tabs = listOf("全部歌曲", "红心收藏", "最近常听", "专辑列表", "歌手聚合", "我的歌单")
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    val qualityKeywordMap = mapOf(
        "flac" to listOf(".flac"), "wav" to listOf(".wav"), "mp3" to listOf(".mp3"),
        "aac" to listOf(".aac", ".m4a"), "dsf" to listOf(".dsf"), "dff" to listOf(".dff"),
        "lossless" to listOf(".flac", ".wav", ".ape", ".alac"),
        "hires" to listOf(".flac", ".wav"), "hi-res" to listOf(".flac", ".wav"),
        "spatial" to listOf("5.1", "7.1", "atmos", "spatial"),
        "5.1" to listOf("5.1"), "7.1" to listOf("7.1"),
        "atmos" to listOf("atmos"), "hq" to listOf(".mp3"),
        "dsd" to listOf(".dsf", ".dff"),
        "24bit" to listOf("24-bit", "24bit"), "32bit" to listOf("32-bit", "32bit")
    )

    var showSettingsScreen by remember { mutableStateOf(false) }
    var showSelectionDialog by remember { mutableStateOf(false) }
    var showDownloadingDialog by remember { mutableStateOf(false) }
    var missingSongsList by remember { mutableStateOf<List<SyncItem>>(emptyList()) }
    var syncLog by remember { mutableStateOf("") }
    var syncProgress by remember { mutableFloatStateOf(0f) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var duplicatesList by remember { mutableStateOf<List<List<Song>>>(emptyList()) }

    var pendingDeleteSong by remember { mutableStateOf<Song?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingDeleteSong?.let { song ->
                scope.launch(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data)) }
                Toast.makeText(context, "授权删除成功", Toast.LENGTH_SHORT).show()
                // 👇 加上这行：如果是在查重页面触发了系统授权删除，删完要立刻把这首歌从查重列表里拿掉
                duplicatesList = duplicatesList.map { g -> g.filter { it.id != song.id } }.filter { it.size > 1 }
            }
        }
        pendingDeleteSong = null
    }

    // 👇 核心重构：将点击播放、插队播放、删除原封不动提取成通用 Lambda，保证在 Pager 中一点代码都不丢失
    val onSongClickAction: (Song, Int, List<Song>) -> Unit = { song, index, currentList ->
        // 👇 1. 核心防御：在播放前检查物理文件是否还存活！
        val file = java.io.File(song.data)
        if (!file.exists()) {
            // 发现幽灵文件！提示用户并自动清理数据库
            android.widget.Toast.makeText(context, "文件已在外部被删除，正在清理列表...", android.widget.Toast.LENGTH_SHORT).show()
            scope.launch(Dispatchers.IO) {
                // 从总库中删除
                db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data))
                // 顺手从关联的歌单里也清掉
                db.openHelper.writableDatabase.execSQL("DELETE FROM playlist_songs WHERE songPath = ?", arrayOf(song.data))
            }
        } else {
            // 👇 2. 文件存在，走正常的播放逻辑
            if (currentAudioPath == song.data) showFullScreenPlayer = true
            else {
                mediaController?.let { controller ->
                    val mediaItems = currentList.map { s ->
                        val metadata = androidx.media3.common.MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).build()
                        androidx.media3.common.MediaItem.Builder().setMediaId(s.data).setUri(s.data).setMediaMetadata(metadata).build()
                    }
                    controller.setMediaItems(mediaItems, index, 0L); controller.prepare(); controller.play()
                }
            }
        }
    }

    val onSongPlayNextAction: (Song) -> Unit = { song ->
        mediaController?.let { controller ->
            val currentIdx = controller.currentMediaItemIndex

            if (currentIdx != androidx.media3.common.C.INDEX_UNSET) {
                val nextIdx = currentIdx + 1

                // 1. 检查：如果这首歌已经是紧挨着的下一首了，直接拦截！
                if (nextIdx < controller.mediaItemCount && controller.getMediaItemAt(nextIdx).mediaId == song.data) {
                    Toast.makeText(context, "已经是下一首啦", Toast.LENGTH_SHORT).show()
                    return@let // 退出，坚决不重复添加
                }

                // 2. 检查：这首歌是不是在播放队列的更后面？
                var existingIndex = -1
                for (i in nextIdx until controller.mediaItemCount) {
                    if (controller.getMediaItemAt(i).mediaId == song.data) {
                        existingIndex = i
                        break
                    }
                }

                if (existingIndex != -1) {
                    // 场景 A：已经在后面的队列里了，我们就把它“提拔”上来，不产生重复！
                    controller.moveMediaItem(existingIndex, nextIdx)
                    Toast.makeText(context, "已移至下一首播放", Toast.LENGTH_SHORT).show()
                } else {
                    // 场景 B：完全是一首新歌，正常插入
                    val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build()
                    val mediaItem = MediaItem.Builder().setMediaId(song.data).setUri(song.data).setMediaMetadata(metadata).build()
                    controller.addMediaItem(nextIdx, mediaItem)
                    Toast.makeText(context, "已添加到下一首", Toast.LENGTH_SHORT).show()
                }
            } else {
                // 如果当前什么都没在播放，就直接播放这首歌
                val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build()
                val mediaItem = MediaItem.Builder().setMediaId(song.data).setUri(song.data).setMediaMetadata(metadata).build()
                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.play()
            }
        }
    }

    val onSongDeleteAction: (Song) -> Unit = { song ->
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
            if (context.contentResolver.delete(uri, null, null) > 0) {
                scope.launch(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data)) }
                Toast.makeText(context, "彻底删除成功", Toast.LENGTH_SHORT).show()
            } else {
                if (File(song.data).delete()) {
                    scope.launch(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data)) }
                    Toast.makeText(context, "彻底删除成功", Toast.LENGTH_SHORT).show()
                } else { Toast.makeText(context, "删除失败，文件可能被占用", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                pendingDeleteSong = song
                deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val recoverable = e as? android.app.RecoverableSecurityException
                if (recoverable != null) {
                    pendingDeleteSong = song
                    deleteLauncher.launch(IntentSenderRequest.Builder(recoverable.userAction.actionIntent.intentSender).build())
                } else Toast.makeText(context, "删除受限", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, "删除受限", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(context, "删除出错", Toast.LENGTH_SHORT).show() }
    }

    val fetchSongsList = {
        syncLog = "正在连接电脑获取清单..."
        showDownloadingDialog = true
        scope.launch {
            try {
                val folderUri = savedFolderUriStr?.let { android.net.Uri.parse(it) }
                missingSongsList = SyncManager.fetchMissingSongs(context, pcServerIp, allSongs, folderUri)
                showDownloadingDialog = false
                if (missingSongsList.isEmpty()) {
                    syncLog = "手机已经是最新，没有缺少的歌曲！"
                    showDownloadingDialog = true; delay(2000); showDownloadingDialog = false
                } else showSelectionDialog = true
            } catch (e: Exception) {
                syncLog = "连接失败，请检查电脑 IP 是否正确、且在同一 Wi-Fi"
                delay(3000); showDownloadingDialog = false
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            savedFolderUriStr = uri.toString()
            prefs.edit().putString("sync_folder", uri.toString()).apply()
            showSettingsScreen = false; fetchSongsList()
        }
    }

    val scanWhitelistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val folderName = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: return@rememberLauncherForActivityResult
            val newSet = allowedFolders.toMutableSet().apply { add(folderName) }
            allowedFolders = newSet
            prefs.edit().putStringSet("allowed_folders", newSet).apply()
            scope.launch { MusicUtils.syncLocalMusicToDatabase(context, dao, newSet) }
        }
    }

    val batchLrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                var successCount = 0; var failCount = 0
                uris.forEach { uri ->
                    try {
                        val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        val fileName = cursor?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@forEach
                        val songName = fileName.substringBeforeLast(".")
                        val matchedSong = allSongs.find {
                            it.title.equals(songName, true) || File(it.data).nameWithoutExtension.equals(songName, true) ||
                                    songName.contains(it.title, true) || it.title.contains(songName, true)
                        }
                        if (matchedSong != null) {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val destFile = File(File(matchedSong.data).parent, "${File(matchedSong.data).nameWithoutExtension}.lrc")
                            inputStream?.use { input -> destFile.outputStream().use { output -> output.write(input.readBytes()) } }
                            successCount++
                        } else failCount++
                    } catch (e: Exception) { failCount++ }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "批量导入完成：成功 $successCount 首，未匹配 $failCount 首", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            scope.launch { MusicUtils.syncLocalMusicToDatabase(context, dao, allowedFolders) }
        }
    }

    LaunchedEffect(mediaController, currentTitle) {
        while (isActive && mediaController != null && currentTitle != null) {
            val controller = mediaController
            val dur = controller?.duration?.coerceAtLeast(1L) ?: 1L
            val pos = controller?.currentPosition ?: 0L
            miniProgress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            delay(400)
        }
    }

    LaunchedEffect(Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            mediaController = controller
            repeatMode = controller.repeatMode
            shuffleMode = controller.shuffleModeEnabled
            controller.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    currentTitle   = mediaMetadata.title?.toString()
                    currentArtist  = mediaMetadata.artist?.toString()
                    currentArtwork = mediaMetadata.artworkData
                    scope.launch(Dispatchers.IO) {
                        val path = currentAudioPath
                        if (path.isEmpty()) return@launch

                        var bmp: Bitmap? = CoverArtCache.loadBitmapFromDisk(context, path)
                        if (bmp == null && mediaMetadata.artworkData != null) {
                            val bytes = mediaMetadata.artworkData!!
                            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        }

                        if (bmp != null && path == PlayerStateHolder.currentPath) {
                            PlayerStateHolder.updateFromBitmap(bmp, path)
                            AudioCache.putCoverInMemory(path, bmp.asImageBitmap())
                        }
                        // 无封面时不 clear，等待 CoverArtCache / 全屏播放器网络拉取后更新主题
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentAudioPath = mediaItem?.mediaId ?: ""
                    PlayerStateHolder.onTrackChanged(currentAudioPath)

                    // 👇 修复后的 ReplayGain 核心智能调音逻辑
                    scope.launch(Dispatchers.IO) {
                        val path = currentAudioPath
                        if (path.isNotEmpty() && enableReplayGain) {
                            // 1. 获取所有歌曲流并拿到最新的一次快照
                            val allSongsList = dao.getAllSongs().first()

                            // 2. 找到当前正在播放的这首歌
                            val currentSongDb = allSongsList.find { it.data == path }

                            // 3. 拿出它的增益值
                            val gainDb = currentSongDb?.replayGain ?: 0f

                            // 4. 增益换算公式 (需要 import kotlin.math.pow)
                            val linearVolume = (10.0).pow(gainDb / 20.0).toFloat().coerceIn(0.1f, 1.0f)

                            withContext(Dispatchers.Main) {
                                // 偷偷调整 ExoPlayer 的内部音量
                                mediaController?.setVolume(linearVolume)
                            }
                        } else {
                            // 没开开关，或者路径为空，保持 100% 原始输出
                            withContext(Dispatchers.Main) {
                                mediaController?.setVolume(1.0f)
                            }
                        }
                    }
                }
                override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
                override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffleMode = enabled }
            })
        }, ContextCompat.getMainExecutor(context))
    }

    val currentSong = remember(currentAudioPath, allSongs) { allSongs.find { it.data == currentAudioPath } }
    val isFavorite = currentSong?.isFavorite == true
    var isCurrentSongCounted by remember { mutableStateOf(false) }

    LaunchedEffect(currentAudioPath) {
        isCurrentSongCounted = false
    }

    LaunchedEffect(currentAudioPath, isPlaying) {
        if (isPlaying && currentAudioPath.isNotEmpty() && !isCurrentSongCounted) {
            while(isActive) {
                val pos = mediaController?.currentPosition ?: 0L
                if (pos >= 30_000L) {
                    dao.incrementPlayCount(currentAudioPath, System.currentTimeMillis())
                    // 👇 新增历史记录写入，记录下精确听歌时间，方便以后做年度报表
                    dao.insertHistory(PlayHistory(songPath = currentAudioPath, timestamp = System.currentTimeMillis(), durationListened = 30000L))
                    isCurrentSongCounted = true
                    break
                }
                delay(1000)
            }
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.auralis.app.SYNC_COMPLETED") {
                    android.util.Log.d("MusicApp", "📢 收到同步完成广播！开始入库...") // 👈 加这行
                    scope.launch {
                        ctx?.let {
                            // 因为上面已经定义了 dao 和 allowedFolders，这里就不会再报错了！
                            MusicUtils.syncLocalMusicToDatabase(it, dao, allowedFolders)
                            android.widget.Toast.makeText(it, "新歌已自动入库！", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        val filter = android.content.IntentFilter("com.auralis.app.SYNC_COMPLETED")
        // 兼容 Android 14+ 的广播注册安全要求
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            // 当离开页面时，销毁监听器，防止内存泄漏
            context.unregisterReceiver(receiver)
        }
    }

    val albumPalette by PlayerStateHolder.albumPalette.collectAsState()
    val miniCover by PlayerStateHolder.coverBitmap.collectAsState()

    // ── Scaffold 移到最外层，永远不销毁 ──
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            AnimatedVisibility(
                visible = currentTitle != null && !showFullScreenPlayer,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(300)),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(200))
            ) {
                if (currentTitle != null) {
                    PremiumMiniPlayerBar(
                        title = currentTitle!!,
                        artist = currentArtist ?: "未知歌手",
                        isPlaying = isPlaying,
                        coverBitmap = miniCover,
                        audioPath = currentAudioPath,
                        progress = miniProgress,
                        onPreviousClick = {
                            mediaController?.let { controller ->
                                val currentIdx = controller.currentMediaItemIndex
                                if (currentIdx > 0) { controller.seekTo(currentIdx - 1, 0L); controller.play() }
                                else controller.seekTo(0, 0L)
                            }
                        },
                        onPlayPauseClick = { if (isPlaying) mediaController?.pause() else mediaController?.play() },
                        onNextClick = { mediaController?.seekToNext() },
                        onBarClick = { showFullScreenPlayer = true }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).windowInsetsPadding(WindowInsets.safeDrawing)) {
            HomeAmbientBackground(palette = albumPalette)
            Column(modifier = Modifier.fillMaxSize()) {

            AnimatedVisibility(visible = sleepTimerSeconds > 0) {
                SleepTimerBanner(
                    secondsRemaining = sleepTimerSeconds,
                    onCancel = { sleepTimerSeconds = 0L }
                )
            }

            HomeHeader(
                totalSongs    = allSongs.size,
                searchQuery   = searchQuery,
                onSearchChange = { searchQuery = it },
                sortType      = sortType,
                isAscending   = isAscending,
                onSortChange  = { type, asc ->
                    sortType = type; isAscending = asc
                    prefs.edit().putString("sort_type", type).putBoolean("is_ascending", asc).apply()
                },
                onSettingsClick = { showSettingsScreen = true },
                onSyncClick = {
                    if (pcServerIp.endsWith(".") || savedFolderUriStr == null)
                        showSettingsScreen = true
                    else
                        fetchSongsList()
                }
            )

            PremiumTabBar(
                tabs = tabs,
                selectedIndex = pagerState.currentPage,
                onTabSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } }
            )

            if (!hasPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        EmptyStateView(
                            icon = Icons.Filled.LibraryMusic,
                            title = "需要访问音乐文件",
                            subtitle = "请允许读取音频、通知与麦克风权限，以便扫描曲库、显示播放控制与律动效果"
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { activity.requestRuntimePermissions() }) {
                            Icon(Icons.Filled.Security, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("授予权限")
                        }
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.Top
                ) { pageIndex ->
                    when (pageIndex) {
                        0, 1 -> {
                            val currentBaseList = if (pageIndex == 0) allSongs else favSongs
                            val currentProcessedSongs = remember(currentBaseList, searchQuery, sortType, isAscending) {
                                val lowerQuery = searchQuery.lowercase().trim()
                                val filtered = if (lowerQuery.isEmpty()) currentBaseList else {
                                    val extraKeywords = qualityKeywordMap[lowerQuery] ?: listOf(lowerQuery)
                                    currentBaseList.filter { song ->
                                        song.title.contains(lowerQuery, true) || 
                                        song.artist.contains(lowerQuery, true) || 
                                        song.album.contains(lowerQuery, true) ||
                                        (lowerQuery == "24-bit" && song.bitDepth >= 24) ||
                                        (lowerQuery == "hi-res" && (song.bitDepth >= 24 || song.samplingRate > 48000)) ||
                                        extraKeywords.any { kw -> song.data.lowercase().contains(kw) }
                                    }
                                }
                                val sorted = when (sortType) { "Date" -> filtered.sortedBy { it.dateModified }; "Size" -> filtered.sortedBy { it.size }; else -> filtered.sortedBy { it.title } }
                                if (isAscending) sorted else sorted.reversed()
                            }

                            // 1. 定义刷新状态
                            var isRefreshing by remember { mutableStateOf(false) }
                            val pullRefreshState = rememberPullToRefreshState()

                            // 2. 使用 PullToRefreshBox 包裹
                            PullToRefreshBox(
                                isRefreshing = isRefreshing,
                                onRefresh = {
                                    if (pageIndex == 0) {
                                        isRefreshing = true
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                MusicUtils.syncLocalMusicToDatabase(context, dao, allowedFolders)
                                            }
                                            isRefreshing = false
                                            android.widget.Toast.makeText(context, "列表已刷新", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                state = pullRefreshState,
                                modifier = Modifier.fillMaxSize(),
                                indicator = {
                                    if (pageIndex == 0) {
                                        PullToRefreshDefaults.Indicator(
                                            state = pullRefreshState,
                                            isRefreshing = isRefreshing,
                                            modifier = Modifier.align(Alignment.TopCenter),
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            ) {
                                if (currentProcessedSongs.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            // 关键：即使为空也要能划动，确保能拉出刷新球
                                            .verticalScroll(rememberScrollState()),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        EmptyStateView(
                                            icon = if (pageIndex == 1) Icons.Filled.FavoriteBorder else Icons.Filled.MusicNote,
                                            title = if (pageIndex == 1) "还没有收藏" else "曲库为空",
                                            subtitle = if (pageIndex == 1) "点击歌曲菜单，将喜欢的音乐加入红心收藏" else "下拉可刷新本地曲库，或在设置中添加扫描文件夹"
                                        )
                                    }
                                } else {
                                    val listState = if (pageIndex == 0) mainListState else rememberLazyListState()

                                    LaunchedEffect(sortType, isAscending) {
                                        if (listState.firstVisibleItemIndex > 0) {
                                            listState.scrollToItem(0)
                                        }
                                    }

                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        state = listState,
                                        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)
                                    ) {
                                        itemsIndexed(items = currentProcessedSongs, key = { _, song -> song.data }) { index, song ->
                                            val isCurrentSong = currentAudioPath == song.data
                                            SongItemUI(
                                                song = song, index = index, isCurrentSong = isCurrentSong, // 👈 传给新参数
                                                isPlaying = isPlaying, allowMarquee = allowMarquee,
                                                onClick = { onSongClickAction(song, index, currentProcessedSongs) },
                                                onPlayNext = { onSongPlayNextAction(song) },
                                                onAddToPlaylist = { songToAddToPlaylist = song },
                                                onDelete = { onSongDeleteAction(song) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            val activeTopList = topSongs.filter { it.playCount > 0 }
                            if (activeTopList.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    EmptyStateView(
                                        icon = Icons.Filled.History,
                                        title = "暂无播放记录",
                                        subtitle = "多听几首歌，这里会展示你的常听榜单"
                                    )
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                                    ) {
                                        itemsIndexed(items = activeTopList, key = { _, song -> song.data }) { index, song ->
                                            TopSongRow(
                                                rank = index,
                                                song = song,
                                                isCurrentSong = currentAudioPath == song.data,
                                                isPlaying = isPlaying,
                                                onClick = { onSongClickAction(song, index, activeTopList) }
                                            )
                                        }
                                    }
                                    ListeningStatsBanner(
                                        totalPlays = activeTopList.sumOf { it.playCount },
                                        uniqueSongs = activeTopList.size
                                    )
                                }
                            }
                        }

                        3 -> {
                            val albumGroups = remember(allSongs) { allSongs.groupBy { it.album }.toList().sortedBy { it.first } }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(albumGroups) { (albumName, songs) ->
                                    AlbumRow(
                                        albumName = albumName,
                                        artistName = songs.firstOrNull()?.artist ?: "未知歌手",
                                        songCount = songs.size,
                                        onClick = {
                                            searchQuery = albumName
                                            scope.launch { pagerState.animateScrollToPage(0) }
                                        }
                                    )
                                }
                            }
                        }

                        4 -> {
                            val artistGroups = remember(allSongs) { allSongs.groupBy { it.artist }.toList().sortedBy { it.first } }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(artistGroups) { (artistName, songs) ->
                                    ArtistRow(
                                        artistName = artistName,
                                        songCount = songs.size,
                                        onClick = {
                                            searchQuery = artistName
                                            scope.launch { pagerState.animateScrollToPage(0) }
                                        }
                                    )
                                }
                            }
                        }

                        5 -> {
                            if (selectedPlaylist == null) {
                                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                    FilledTonalButton(
                                        onClick = { showNewPlaylistDialog = true },
                                        modifier = Modifier.fillMaxWidth().height(52.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(Icons.Default.Add, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("新建自定义歌单", fontWeight = FontWeight.SemiBold)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    if (allPlaylists.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                            EmptyStateView(
                                                icon = Icons.Filled.QueueMusic,
                                                title = "还没有歌单",
                                                subtitle = "点击上方按钮，创建你的第一个自定义歌单"
                                            )
                                        }
                                    } else {
                                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            items(allPlaylists) { playlist ->
                                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                                PlaylistRow(
                                                    name = playlist.name,
                                                    subtitle = "创建于 ${sdf.format(java.util.Date(playlist.createdAt))}",
                                                    onClick = { selectedPlaylist = playlist },
                                                    onDelete = {
                                                        scope.launch(Dispatchers.IO) { dao.deletePlaylist(playlist.id) }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                val playlistSongs by dao.getSongsInPlaylist(selectedPlaylist!!.id).collectAsState(initial = emptyList())
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { selectedPlaylist = null }) { Icon(Icons.Filled.ArrowBack, null) }
                                        Spacer(Modifier.width(8.dp))
                                        Text(selectedPlaylist!!.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        
                                        // 👇 导出歌单按钮
                                        IconButton(onClick = {
                                            scope.launch {
                                                val file = MusicUtils.exportPlaylistToM3U(context, selectedPlaylist!!.name, playlistSongs)
                                                if (file != null) {
                                                    Toast.makeText(context, "已导出至 Download/Auralis/Playlists: ${file.name}", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        })
 {
                                            Icon(Icons.Default.FileDownload, "导出歌单")
                                        }

                                        Text("共 ${playlistSongs.size} 首", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                    }
                                    HorizontalDivider()
                                    if (playlistSongs.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            EmptyStateView(
                                                icon = Icons.Filled.PlaylistAdd,
                                                title = "歌单是空的",
                                                subtitle = "在歌曲列表中长按或点击菜单，添加到当前歌单"
                                            )
                                        }
                                    } else {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            itemsIndexed(items = playlistSongs, key = { _, song -> song.data }) { index, song ->
                                                val isCurrentSong = currentAudioPath == song.data
                                                SongItemUI(
                                                    song = song, index = index, isCurrentSong = isCurrentSong, // 👈 传给新参数
                                                    isPlaying = isPlaying, allowMarquee = allowMarquee,
                                                    onClick = { onSongClickAction(song, index, playlistSongs) },
                                                    onPlayNext = { onSongPlayNextAction(song) },
                                                    onAddToPlaylist = { songToAddToPlaylist = song },
                                                    onRemoveFromPlaylist = {
                                                        scope.launch(Dispatchers.IO) {
                                                            dao.removeSongFromPlaylist(selectedPlaylist!!.id, song.data)
                                                            withContext(Dispatchers.Main) { Toast.makeText(context, "已从歌单移除", Toast.LENGTH_SHORT).show() }
                                                        }
                                                    },
                                                    onDelete = { onSongDeleteAction(song) }
                                                )
                                            }
                                        }
                                    }
                                }
                                BackHandler(enabled = selectedPlaylist != null) { selectedPlaylist = null }
                            }
                        }
                    }
                }
            }
            } // hasPermission else
        } // Column
     // Box (ambient background)
    } // Scaffold

    // ── 全屏播放器覆盖在 Scaffold 上面 ──
    AnimatedContent(
        targetState = showFullScreenPlayer && currentTitle != null,
        transitionSpec = {
            if (targetState) {
                (slideInVertically(initialOffsetY = { it }, animationSpec = tween(420, easing = FastOutSlowInEasing)) + fadeIn(tween(350))).togetherWith(fadeOut(tween(200)))
            } else {
                fadeIn(tween(300)).togetherWith(slideOutVertically(targetOffsetY = { it }, animationSpec = tween(380, easing = FastOutSlowInEasing)) + fadeOut(tween(280)))
            }
        },
        label = "screenTransition"
    ) { isVisible ->
        if (isVisible) {
            Box(modifier = Modifier.fillMaxSize()) {
                FullScreenPlayer(
                    title = currentTitle!!, artist = currentArtist ?: "未知歌手",
                    isPlaying = isPlaying, artwork = currentArtwork, audioPath = currentAudioPath,
                    mediaController = mediaController, repeatMode = repeatMode, shuffleMode = shuffleMode,
                    sleepTimerSeconds = sleepTimerSeconds, audioManager = audioManager,
                    isFavorite = isFavorite,
                    onFavoriteClick = { scope.launch { dao.updateFavoriteStatus(currentAudioPath, !isFavorite) } },
                    onSleepTimerClick = { showSleepTimerDialog = true },
                    onBackClick = { showFullScreenPlayer = false },
                    onArtistClick = { clickedArtist -> searchQuery = clickedArtist; showFullScreenPlayer = false }
                )
                BackHandler { showFullScreenPlayer = false }
            }
        }
    }

    BackHandler(enabled = searchQuery.isNotEmpty() && !showFullScreenPlayer) { searchQuery = "" }

    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("睡眠定时器", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (sleepTimerSeconds > 0) {
                        Text("当前：${sleepTimerSeconds / 60}分${sleepTimerSeconds % 60}秒后暂停", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { sleepTimerSeconds = 0L; showSleepTimerDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("取消定时器") }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                    listOf(15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "1 小时", 90 to "90 分钟").forEach { (minutes, label) ->
                        TextButton(onClick = { sleepTimerSeconds = minutes * 60L; showSleepTimerDialog = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSleepTimerDialog = false }) { Text("取消") } }
        )
    }

    AnimatedContent(
        targetState = showSettingsScreen,
        transitionSpec = {
            if (targetState) {
                slideInHorizontally { it }.togetherWith(slideOutHorizontally { -it / 3 })
            } else {
                slideInHorizontally { -it / 3 }.togetherWith(slideOutHorizontally { it })
            }
        }, label = "settingsTransition"
    ) { showSettings ->
        if (showSettings) {
            SettingsScreen(
                onBack = { showSettingsScreen = false },
                enableReplayGain = enableReplayGain,
                onReplayGainChange = {
                    enableReplayGain = it
                    prefs.edit().putBoolean("enable_replay_gain", it).apply()
                    if (!it) mediaController?.setVolume(1.0f)
                },
                enableBitPerfect = enableBitPerfect,
                onBitPerfectChange = {
                    enableBitPerfect = it
                    prefs.edit().putBoolean("enable_bit_perfect", it).apply()
                    PlaybackService.instance?.applyUsbBitPerfectSetting(it)
                },
                isPcMode = isPcMode,
                onPcModeChange = {
                    if (it) {
                        scope.launch(Dispatchers.IO) { PcAudioReceiver.startListening() }
                        isPcMode = true
                    } else {
                        PcAudioReceiver.stop()
                        isPcMode = false
                    }
                },
                pcServerIp = pcServerIp,
                onPcServerIpChange = { pcServerIp = it; prefs.edit().putString("server_ip", it).apply() },

                // 💡 修复：补上了这两个参数！
                savedFolderUriStr = savedFolderUriStr,
                onPickFolder = { folderPickerLauncher.launch(null) },

                allowedFolders = allowedFolders,
                onFolderAdded = { folder ->
                    val newSet = allowedFolders.toMutableSet().apply { add(folder) }
                    allowedFolders = newSet
                    prefs.edit().putStringSet("allowed_folders", newSet).apply()
                    scope.launch { MusicUtils.syncLocalMusicToDatabase(context, dao, newSet) }
                },
                onFolderRemoved = { folder ->
                    val newSet = allowedFolders.toMutableSet().apply { remove(folder) }
                    allowedFolders = newSet
                    prefs.edit().putStringSet("allowed_folders", newSet).apply()
                    scope.launch { MusicUtils.syncLocalMusicToDatabase(context, dao, newSet) }
                },
                onRescanLibrary = {
                    scope.launch(Dispatchers.IO) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "开始深度解析...", Toast.LENGTH_SHORT).show() }
                        CoverArtCache.invalidateAll(context)
                        db.openHelper.writableDatabase.execSQL("DELETE FROM songs")
                        MusicUtils.syncLocalMusicToDatabase(context, dao, allowedFolders)
                        withContext(Dispatchers.Main) { Toast.makeText(context, "深度解析完成！", Toast.LENGTH_LONG).show() }
                    }
                },
                onBatchImportLrc = { batchLrcPicker.launch(arrayOf("*/*")) },
                onShowSleepTimer = { showSleepTimerDialog = true },
                // 👇 把丢失的智能查重大脑补回来！
                onFindDuplicates = {
                    scope.launch(Dispatchers.Default) {
                        // 智能查重逻辑：按 “歌名 + 歌手” 精准归类，忽略大小写和空格！
                        val duplicates = allSongs.groupBy { "${it.title.trim().lowercase()}_${it.artist.trim().lowercase()}" }
                            .filter { it.value.size > 1 }
                            .values.toList()

                        withContext(Dispatchers.Main) {
                            if (duplicates.isNotEmpty()) {
                                duplicatesList = duplicates
                                showDuplicateDialog = true
                                showSettingsScreen = false // 关掉设置页，优雅地弹出查重界面
                            } else {
                                Toast.makeText(context, "太棒了！你的曲库非常干净，没有重复歌曲 ✨", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )
            BackHandler { showSettingsScreen = false }
        }
    }

    if (showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { showDuplicateDialog = false },
            containerColor = MaterialTheme.colorScheme.surface, // 去掉花哨底色
            title = { Text("清理重复歌曲", fontWeight = FontWeight.W600, fontSize = 20.sp) },
            text = {
                // 记录当前点击展开了哪首歌的详细信息
                var expandedSongId by remember { mutableStateOf<Long?>(null) }

                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                    duplicatesList.forEachIndexed { index, group ->
                        item {
                            // 高级感标题：去掉了色块，采用极简的文字+留白
                            Text(
                                text = "组 ${index + 1} · ${group[0].title}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }

                        itemsIndexed(group) { _, song ->
                            val extension = song.data.substringAfterLast('.').uppercase()
                            val sizeMb = String.format("%.2f", song.size / 1048576.0)
                            val durationStr = "${song.duration / 60000}:${String.format("%02d", (song.duration % 60000) / 1000)}"
                            val isExpanded = expandedSongId == song.id

                            // 去掉 Card，改用无边框的 Column，依靠 padding 呼吸感
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null // 去掉点击的水波纹，更显冷淡高级
                                    ) { expandedSongId = if (isExpanded) null else song.id }
                                    .padding(vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${song.title} - ${song.artist}", maxLines = 1, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // 极简的小标签
                                            Box(modifier = Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                                                Text(extension, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text("$sizeMb MB • $durationStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }

                                    // 高级感垃圾桶：平时是淡淡的灰色线框图标，不再是一团大红色
                                    IconButton(
                                        onClick = {
                                            // 👇 真正的物理删除逻辑 (MediaStore API)
                                            try {
                                                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                                                // 1. 尝试使用官方 API 删物理文件
                                                if (context.contentResolver.delete(uri, null, null) > 0 || File(song.data).delete()) {
                                                    // 2. 删库
                                                    scope.launch(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data)) }
                                                    // 3. 刷新 UI
                                                    duplicatesList = duplicatesList.map { g -> g.filter { it.id != song.id } }.filter { it.size > 1 }
                                                    Toast.makeText(context, "已从手机彻底删除", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "删除失败，文件可能被占用", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: SecurityException) {
                                                // 触发 Android 10+ 系统级删除授权弹窗
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                                                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                                                    pendingDeleteSong = song
                                                    deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                                                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                                                    val recoverable = e as? android.app.RecoverableSecurityException
                                                    if (recoverable != null) {
                                                        pendingDeleteSong = song
                                                        deleteLauncher.launch(IntentSenderRequest.Builder(recoverable.userAction.actionIntent.intentSender).build())
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "删除出错", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Icon(androidx.compose.material.icons.Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.outline)
                                    }
                                }

                                // 👇 点按展开的底层高级属性面板 (占位，等接入 jaudiotagger 即可点亮)
                                AnimatedVisibility(visible = isExpanded) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text("物理路径: ${song.data.substringAfterLast("/")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(6.dp))

                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            // 👇 检查位深度：如果还是显示“未知”，说明数据库里这列是 0
                                            val bitsText = if (song.bitDepth > 0) "${song.bitDepth}-bit" else "等待解析..."
                                            Text(
                                                text = "位深度: $bitsText",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (song.bitDepth >= 24) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            // 👇 检查采样率
                                            val rateText = if (song.samplingRate > 0) {
                                                String.format("%.1f kHz", song.samplingRate / 1000f)
                                            } else {
                                                "等待解析..."
                                            }
                                            Text(
                                                text = "采样率: $rateText",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (song.samplingRate > 48000) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        // 🔮 加一个小彩蛋：显示这首歌的音量增益，这是 jaudiotagger 是否活着的终极证据
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "音量增益 (ReplayGain): ${String.format("%.2f dB", song.replayGain)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (song.replayGain != 0f) MaterialTheme.colorScheme.secondary else Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                        // 极简分隔线
                        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDuplicateDialog = false }) { Text("关闭") } }
        )
    }

    if (showSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showSelectionDialog = false }, title = { Text("发现新歌曲") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    item {
                        val allSelected = missingSongsList.all { it.isSelected }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { missingSongsList = missingSongsList.map { it.copy(isSelected = !allSelected) } }.padding(vertical = 8.dp)) {
                            Checkbox(checked = allSelected, onCheckedChange = null); Text("全选", fontWeight = FontWeight.Bold)
                        }; HorizontalDivider()
                    }
                    itemsIndexed(missingSongsList) { index, item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { val newList = missingSongsList.toMutableList(); newList[index] = item.copy(isSelected = !item.isSelected); missingSongsList = newList }.padding(vertical = 4.dp)) {
                            Checkbox(checked = item.isSelected, onCheckedChange = null)
                            Column { Text(item.remoteSong.filename, maxLines = 1, style = MaterialTheme.typography.bodyMedium); Row { Text("${item.remoteSong.size / 1048576} MB", style = MaterialTheme.typography.bodySmall, color = Color.Gray); if (item.remoteSong.has_lrc) Text(" • 带歌词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showSelectionDialog = false
                    val toDownload = missingSongsList.filter { it.isSelected }.map { it.remoteSong }
                    if (toDownload.isNotEmpty() && savedFolderUriStr != null) {
                        SyncTaskQueue.serverIp = pcServerIp; SyncTaskQueue.saveFolderUri = android.net.Uri.parse(savedFolderUriStr); SyncTaskQueue.songsToDownload = toDownload
                        val intent = Intent(context, SyncService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                        Toast.makeText(context, "已切换至后台同步，请下拉通知栏查看进度", Toast.LENGTH_LONG).show()
                    }
                }) { Text("后台同步") }
            },
            dismissButton = { TextButton(onClick = { showSelectionDialog = false }) { Text("取消") } }
        )
    }

    if (showDownloadingDialog) {
        AlertDialog(
            onDismissRequest = {}, title = { Text("同步中", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(syncLog); Spacer(Modifier.height(16.dp))
                    if (syncProgress > 0f) {
                        LinearProgressIndicator(progress = { syncProgress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                        Text("${(syncProgress * 100).toInt()}%", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (songToAddToPlaylist != null) {
        AlertDialog(
            onDismissRequest = { songToAddToPlaylist = null },
            title = { Text("添加到歌单", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    item {
                        OutlinedButton(
                            onClick = { showNewPlaylistDialog = true },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Icon(Icons.Filled.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("新建歌单")
                        }
                    }
                    if (allPlaylists.isEmpty()) {
                        item { Text("暂无自定义歌单", color = Color.Gray, modifier = Modifier.padding(16.dp)) }
                    } else {
                        items(allPlaylists) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.name) },
                                leadingContent = { Icon(Icons.Filled.QueueMusic, null) },
                                modifier = Modifier.clickable {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            // 把歌和歌单绑定存入数据库
                                            dao.addSongToPlaylist(PlaylistSong(playlistId = playlist.id, songPath = songToAddToPlaylist!!.data))
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "已添加到 ${playlist.name}", Toast.LENGTH_SHORT).show()
                                                songToAddToPlaylist = null // 关闭弹窗
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) { Toast.makeText(context, "添加失败或已在歌单中", Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { songToAddToPlaylist = null }) { Text("取消") } }
        )
    }

    // 🪟 弹窗：输入新歌单名字
    if (showNewPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showNewPlaylistDialog = false; newPlaylistName = "" },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            // 1. 创建歌单拿到 ID
                            val newId = dao.createPlaylist(Playlist(name = newPlaylistName))
                            // 2. 如果是从某首歌点进来的，顺便把这首歌塞进去
                            if (songToAddToPlaylist != null) {
                                dao.addSongToPlaylist(PlaylistSong(playlistId = newId, songPath = songToAddToPlaylist!!.data))
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "已创建并添加歌曲", Toast.LENGTH_SHORT).show()
                                    showNewPlaylistDialog = false
                                    newPlaylistName = ""
                                    songToAddToPlaylist = null // 连带外层选择窗一起关闭
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "歌单创建成功", Toast.LENGTH_SHORT).show()
                                    showNewPlaylistDialog = false
                                    newPlaylistName = ""
                                }
                            }
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showNewPlaylistDialog = false; newPlaylistName = "" }) { Text("取消") } }
        )
    }
}
