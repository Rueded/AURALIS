package com.auralis.app

import androidx.compose.ui.res.stringResource
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
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
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
@androidx.media3.common.util.UnstableApi
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
    val recentSongs by dao.getRecentlyPlayedSongs().collectAsState(initial = emptyList())
    // 「最近常听」页的排序方式：false = 按播放次数（原本行为），true = 按最近播放时间
    var historySortByRecent by remember { mutableStateOf(false) }
    // 👇 新增：获取所有歌单数据
    val allPlaylists by dao.getAllPlaylists().collectAsState(initial = emptyList())
    var enableReplayGain by remember { mutableStateOf(prefs.getBoolean("enable_replay_gain", false)) }

    // 👇 新增：USB Bit-perfect 开关状态
    var enableBitPerfect by remember { mutableStateOf(prefs.getBoolean("enable_bit_perfect", false)) }

    var pcServerIp by remember { mutableStateOf(prefs.getString("server_ip", "192.168.") ?: "192.168.") }
    var savedFolderUriStr by remember { mutableStateOf(prefs.getString("sync_folder", null)) }
    var allowedFolders by remember { mutableStateOf(prefs.getStringSet("allowed_folders", setOf()) ?: setOf()) }

    val activity = LocalActivity.current
    val hasPermission by activity.audioPermissionGranted
    var mediaController by remember { mutableStateOf<MediaController?>(null) }

    var currentTitle by remember { mutableStateOf<String?>(null) }
    var currentArtist by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var miniProgress by remember { mutableFloatStateOf(0f) }
    var miniPositionMs by remember { mutableStateOf(0L) }
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
    var songToShareToNearby by remember { mutableStateOf<Song?>(null) }

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
                Toast.makeText(context, context.getString(R.string.sleep_timer_paused_toast), Toast.LENGTH_LONG).show()
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

    val openPlayerRequest by PlayerStateHolder.openPlayerRequest.collectAsState()
    LaunchedEffect(openPlayerRequest) {
        if (openPlayerRequest) {
            if (currentTitle != null) showFullScreenPlayer = true
            PlayerStateHolder.consumeOpenPlayerRequest()
        }
    }

    // 👇 修改 1：引入 PagerState，废弃原本的 selectedTab
    val tabs = listOf(
        stringResource(R.string.tab_all_songs),
        stringResource(R.string.tab_favorites),
        stringResource(R.string.tab_recent),
        stringResource(R.string.tab_albums),
        stringResource(R.string.tab_artists),
        stringResource(R.string.tab_playlists),
        stringResource(R.string.tab_history)
    )
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
    var showNearbyScreen by remember { mutableStateOf(false) }
    var showAboutScreen  by remember { mutableStateOf(false) }
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
                Toast.makeText(context, context.getString(R.string.delete_authorized_success), Toast.LENGTH_SHORT).show()
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
            android.widget.Toast.makeText(context, context.getString(R.string.file_deleted_externally_cleanup), android.widget.Toast.LENGTH_SHORT).show()
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
                        val artworkUri = android.net.Uri.parse("auralis://cover?path=" + java.net.URLEncoder.encode(s.data, "UTF-8"))
                        val metadata = androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(s.title)
                            .setArtist(s.artist)
                            .setArtworkUri(artworkUri)
                            .build()
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

                // 0. 如果这首歌就是当前正在播放的那首，不需要做任何事，避免生成一份重复项
                if (controller.getMediaItemAt(currentIdx).mediaId == song.data) {
                    Toast.makeText(context, context.getString(R.string.already_next_song), Toast.LENGTH_SHORT).show()
                    return@let
                }

                // 1. 检查：如果这首歌已经是紧挨着的下一首了，直接拦截！
                if (nextIdx < controller.mediaItemCount && controller.getMediaItemAt(nextIdx).mediaId == song.data) {
                    Toast.makeText(context, context.getString(R.string.already_next_song), Toast.LENGTH_SHORT).show()
                    return@let // 退出，坚决不重复添加
                }

                // 2. 检查：这首歌是不是已经在队列的其他位置（不管前面还是后面）？
                var existingIndex = -1
                for (i in 0 until controller.mediaItemCount) {
                    if (i == currentIdx) continue
                    if (controller.getMediaItemAt(i).mediaId == song.data) {
                        existingIndex = i
                        break
                    }
                }

                if (existingIndex != -1) {
                    // 场景 A：已经在队列里了（不论原本排在前面还是后面），把它挪到下一首位置，绝不重复插入！
                    // moveMediaItem 之后，若原位置在目标位置之前，目标下标要减 1 做修正
                    val targetIdx = if (existingIndex < nextIdx) nextIdx - 1 else nextIdx
                    controller.moveMediaItem(existingIndex, targetIdx)
                    Toast.makeText(context, context.getString(R.string.moved_to_play_next), Toast.LENGTH_SHORT).show()
                } else {
                    // 场景 B：完全是一首新歌，正常插入
                    val artworkUri = android.net.Uri.parse("auralis://cover?path=" + java.net.URLEncoder.encode(song.data, "UTF-8"))
                    val metadata = MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setArtworkUri(artworkUri)
                        .build()
                    val mediaItem = MediaItem.Builder().setMediaId(song.data).setUri(song.data).setMediaMetadata(metadata).build()
                    controller.addMediaItem(nextIdx, mediaItem)
                    Toast.makeText(context, context.getString(R.string.added_to_play_next), Toast.LENGTH_SHORT).show()
                }
            } else {
                // 如果当前什么都没在播放，就直接播放这首歌
                val artworkUri = android.net.Uri.parse("auralis://cover?path=" + java.net.URLEncoder.encode(song.data, "UTF-8"))
                val metadata = MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(artworkUri)
                    .build()
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
                Toast.makeText(context, context.getString(R.string.delete_permanent_success), Toast.LENGTH_SHORT).show()
            } else {
                if (File(song.data).delete()) {
                    scope.launch(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE data = ?", arrayOf(song.data)) }
                    Toast.makeText(context, context.getString(R.string.delete_permanent_success), Toast.LENGTH_SHORT).show()
                } else { Toast.makeText(context, context.getString(R.string.delete_failed_file_locked), Toast.LENGTH_SHORT).show() }
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
                } else Toast.makeText(context, context.getString(R.string.delete_restricted), Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, context.getString(R.string.delete_restricted), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(context, context.getString(R.string.delete_error), Toast.LENGTH_SHORT).show() }
    }

    val fetchSongsList = {
        syncLog = context.getString(R.string.connecting_to_pc)
        showDownloadingDialog = true
        scope.launch {
            try {
                val folderUri = savedFolderUriStr?.let { android.net.Uri.parse(it) }
                missingSongsList = SyncManager.fetchMissingSongs(context, pcServerIp, allSongs, folderUri)
                showDownloadingDialog = false
                if (missingSongsList.isEmpty()) {
                    syncLog = context.getString(R.string.already_up_to_date)
                    showDownloadingDialog = true; delay(2000); showDownloadingDialog = false
                } else showSelectionDialog = true
            } catch (e: Exception) {
                syncLog = context.getString(R.string.connect_failed_check_ip)
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
                    Toast.makeText(context, context.getString(R.string.bulk_import_done, successCount, failCount), Toast.LENGTH_LONG).show()
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
            miniPositionMs = pos
            delay(400)
        }
    }

    // ── 一起听：房主广播 ─────────────────────────────────────
    // 只要「附近的 Auralis」里开了“允许附近设备一起听”，就把当前播放状态
    // 定期写进 RoomManager；AuralisServer 那边的 /auralis/room/state 接口会原样吐给来问的访客。
    LaunchedEffect(mediaController, currentAudioPath, isPlaying) {
        while (isActive) {
            val controller = mediaController
            if (RoomManager.isHosting.value && controller != null && currentAudioPath.isNotEmpty()) {
                val song = allSongs.find { it.data == currentAudioPath }
                if (song != null) {
                    RoomManager.updateHostState(
                        RoomManager.RoomState(
                            hostDeviceId = AuralisDeviceId.getId(context),
                            hostDeviceName = AuralisDeviceId.getName(context),
                            filename = File(song.data).name,
                            title = song.title,
                            artist = song.artist,
                            positionMs = controller.currentPosition,
                            durationMs = controller.duration.coerceAtLeast(0L),
                            isPlaying = controller.isPlaying
                        )
                    )
                }
            }
            delay(1500)
        }
    }

    // ── 一起听：访客端跟播 ─────────────────────────────────────
    // 加入了某个房主之后，每 1.5 秒去问一次对方现在放到哪了，
    // 换歌就在本地库里按文件名找同名歌曲切过去，进度偏差超过 1.2 秒就纠偏，
    // 播放/暂停状态也跟房主保持一致。找不到本地同名歌曲就只更新状态、提示用户，不强行播放。
    LaunchedEffect(mediaController) {
        var lastSyncedFilename = ""
        // 记录"已经确认在本机找不到"的那个文件名——
        // 之前的 bug 就出在这：找不到本地版本时，只是把 lastSyncedFilename 标记成"已处理"，
        // 但下一轮轮询一看"文件名没变"，就会进到"同一首歌，校正播放/暂停状态"那个分支，
        // 把主机的播放/暂停状态硬套到本机当前正在放的、完全不相关的内容上——
        // 于是主机一暂停，本机正在放的别的歌也会跟着被暂停。
        // 现在明确记一个"确认缺失"的状态，只要还是这首缺失的歌，就完全不碰本机播放器。
        var missingFilename = ""
        while (isActive) {
            val joined = RoomManager.joinedHost.value
            val controller = mediaController
            if (joined != null && controller != null) {
                val state = withContext(Dispatchers.IO) { fetchRoomState(joined) }
                RoomManager.setRemoteState(state)
                if (state != null) {
                    if (state.filename == missingFilename) {
                        // 已知这首歌本机没有，不对本机播放器做任何播放/暂停/进度控制
                    } else if (state.filename != lastSyncedFilename) {
                        val localSong = allSongs.find { File(it.data).name == state.filename }
                        if (localSong != null) {
                            val index = allSongs.indexOf(localSong)
                            onSongClickAction(localSong, index, allSongs)
                            lastSyncedFilename = state.filename
                            missingFilename = ""
                        } else {
                            Toast.makeText(context, context.getString(R.string.room_song_missing, state.title), Toast.LENGTH_SHORT).show()
                            missingFilename = state.filename
                            lastSyncedFilename = "" // 万一之后主机又切回一首本机有的歌，能正常触发切歌
                        }
                    } else {
                        // 同一首本机确实有的歌：校正播放/暂停状态 + 进度漂移
                        val estimatedRemotePos = if (state.isPlaying) {
                            state.positionMs + (System.currentTimeMillis() - state.updatedAtMs)
                        } else state.positionMs
                        val drift = kotlin.math.abs(controller.currentPosition - estimatedRemotePos)
                        if (drift > 1200L) controller.seekTo(estimatedRemotePos.coerceAtLeast(0L))
                        if (state.isPlaying && !controller.isPlaying) controller.play()
                        if (!state.isPlaying && controller.isPlaying) controller.pause()
                    }
                }
            } else if (joined == null) {
                lastSyncedFilename = ""
                missingFilename = ""
            }
            delay(1500)
        }
    }

    LaunchedEffect(Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        // 启动 Auralis 局域网服务
        withContext(Dispatchers.IO) {
            try {
                AuralisServer(context).start()
                NsdHelper.register(context)
                // ✅ 应用启动就开始扫描，而不是等打开 NearbyScreen 才扫
                NsdHelper.startDiscovery(context)
            } catch (e: Exception) {
                android.util.Log.e("MusicApp", "AuralisServer 启动失败: ${e.message}")
            }
        }

        controllerFuture.addListener({
            val controller = controllerFuture.get()
            mediaController = controller
            repeatMode = controller.repeatMode
            shuffleMode = controller.shuffleModeEnabled

            // ★ Immediately sync current playback state (e.g. after process kill & reopen)
            controller.currentMediaItem?.let { item ->
                currentAudioPath = item.mediaId
                PlayerStateHolder.onTrackChanged(item.mediaId)
            }
            controller.mediaMetadata.let { meta ->
                if (meta.title != null) {
                    currentTitle  = meta.title.toString()
                    currentArtist = meta.artist?.toString()
                    currentArtwork = meta.artworkData
                }
            }
            isPlaying = controller.isPlaying

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

                override fun onIsPlayingChanged(playing: Boolean) {
                    if (!playing && PlaybackService.isCrossfading) {
                        // During crossfade the old player stops — debounce to avoid false pause icon
                        scope.launch {
                            delay(300)
                            isPlaying = mediaController?.isPlaying ?: false
                        }
                    } else {
                        isPlaying = playing
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentAudioPath = mediaItem?.mediaId ?: ""
                    PlayerStateHolder.onTrackChanged(currentAudioPath)

                    // 👇 修复后的 ReplayGain 核心智能调音逻辑，兼容淡入淡出
                    scope.launch(Dispatchers.IO) {
                        val path = currentAudioPath
                        if (path.isNotEmpty() && enableReplayGain) {
                            val allSongsList = dao.getAllSongs().first()
                            val currentSongDb = allSongsList.find { it.data == path }
                            val gainDb = currentSongDb?.replayGain ?: 0f
                            val linearVolume = (10.0).pow(gainDb / 20.0).toFloat().coerceIn(0.1f, 1.0f)

                            withContext(Dispatchers.Main) {
                                PlaybackService.targetVolume = linearVolume
                                // ⚠️ 只有在没有进行淡入淡出时，才允许强行干预音量，否则交给渐变动画处理
                                if (!PlaybackService.isCrossfading) {
                                    mediaController?.setVolume(linearVolume)
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                PlaybackService.targetVolume = 1.0f
                                if (!PlaybackService.isCrossfading) {
                                    mediaController?.setVolume(1.0f)
                                }
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

    // 悬浮歌词 overlay 用：不用打开全屏播放器也要有歌词可显示。
    // 这里只做“本地 .lrc / 内嵌歌词”的轻量解析，在线抓词那套复杂交互还是留在全屏播放器里。
    // 如果全屏播放器后面把在线抓到的词同步过来了（同一个 PlayerStateHolder），会自动覆盖掉这份本地结果。
    LaunchedEffect(currentAudioPath) {
        if (currentAudioPath.isEmpty()) {
            PlayerStateHolder.clearLyrics()
            return@LaunchedEffect
        }
        val path = currentAudioPath
        val localLines = withContext(Dispatchers.IO) { LrcParser.parse(path) }
        if (path == currentAudioPath) { // 切歌太快时防止旧结果覆盖新歌
            PlayerStateHolder.updateLyrics(path, localLines)
        }
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
                            android.widget.Toast.makeText(it, it.getString(R.string.new_song_auto_imported), android.widget.Toast.LENGTH_SHORT).show()
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
                    Column {
                        // ── 悬浮歌词 overlay：不用打开全屏播放器也能跟着走词 ──
                        val overlayLrcLines by PlayerStateHolder.lrcLines.collectAsState()
                        val overlayLrcPath by PlayerStateHolder.lrcLoadedForPath.collectAsState()
                        var lyricsOverlayDismissedFor by remember { mutableStateOf("") }
                        // 首次进来把 SharedPreferences 里存的值同步进全局 StateFlow（只做一次）
                        LaunchedEffect(Unit) {
                            PlayerStateHolder.setLyricsOverlayEnabled(prefs.getBoolean("lyrics_overlay_enabled", true))
                        }
                        val lyricsOverlayEnabled by PlayerStateHolder.lyricsOverlayEnabled.collectAsState()
                        val showOverlay = lyricsOverlayEnabled &&
                            overlayLrcPath == currentAudioPath &&
                            overlayLrcLines.isNotEmpty() &&
                            lyricsOverlayDismissedFor != currentAudioPath

                        AnimatedVisibility(
                            visible = showOverlay,
                            enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { it / 2 }),
                            exit = fadeOut(tween(150))
                        ) {
                            val currentLine = remember(overlayLrcLines, miniPositionMs) {
                                if (overlayLrcLines.isEmpty()) null
                                else overlayLrcLines.lastOrNull { it.timeMs <= miniPositionMs } ?: overlayLrcLines.firstOrNull()
                            }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clickable { showFullScreenPlayer = true },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                tonalElevation = 3.dp,
                                shadowElevation = 2.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = currentLine?.text?.takeIf { it.isNotBlank() } ?: "♪",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            // 只是这一首暂时不想看悬浮歌词，不是永久关闭
                                            lyricsOverlayDismissedFor = currentAudioPath
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            stringResource(R.string.hide_lyrics_overlay),
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        PremiumMiniPlayerBar(
                            title = currentTitle!!,
                            artist = currentArtist ?: stringResource(R.string.unknown_artist),
                            isPlaying = isPlaying,
                            coverBitmap = miniCover,
                            audioPath = currentAudioPath,
                            progress = miniProgress,
                            onPreviousClick = {
                                // 用 seekToPrevious()（而不是手动 currentMediaItemIndex - 1）
                                // 是因为后者只会按“原始列表顺序”回退一格，
                                // 开启随机播放(shuffle)时这跟“真正的上一首”完全对不上，
                                // 表现就跟乱跳到别的歌一样。seekToPrevious() 会自动尊重 shuffle 顺序。
                                mediaController?.seekToPrevious()
                            },
                            onPlayPauseClick = { if (isPlaying) mediaController?.pause() else mediaController?.play() },
                            onNextClick = { mediaController?.seekToNext() },
                            onBarClick = { showFullScreenPlayer = true }
                        )
                    }
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
                            title = stringResource(R.string.need_music_access_title),
                            subtitle = stringResource(R.string.need_music_access_subtitle)
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { activity.requestRuntimePermissions() }) {
                            Icon(Icons.Filled.Security, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_grant_permission))
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
                                            android.widget.Toast.makeText(context, context.getString(R.string.list_refreshed), android.widget.Toast.LENGTH_SHORT).show()
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
                                            title = if (pageIndex == 1) stringResource(R.string.empty_favorites_title) else stringResource(R.string.empty_library_title),
                                            subtitle = if (pageIndex == 1) stringResource(R.string.empty_favorites_subtitle) else stringResource(R.string.empty_library_subtitle)
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
                                                onDelete = { onSongDeleteAction(song) },
                                                        onShareToNearby = {
                                                    scope.launch {
                                                        val liveDevices = NsdHelper.discovered.value
                                                        if (liveDevices.isEmpty()) {
                                                            Toast.makeText(context, context.getString(R.string.no_nearby_devices), Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            songToShareToNearby = song
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            // 之前这里只有 topSongs（按 playCount 次数排的），
                            // 不管选哪个 tab，最新听过但只听了一两次的歌永远看不到。
                            // 现在加个切换：按次数 / 按最近播放时间。
                            val activeTopList = if (historySortByRecent) {
                                recentSongs.filter { it.playCount > 0 }
                            } else {
                                topSongs.filter { it.playCount > 0 }
                            }
                            if (activeTopList.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    EmptyStateView(
                                        icon = Icons.Filled.History,
                                        title = stringResource(R.string.no_play_history_title),
                                        subtitle = stringResource(R.string.no_play_history_subtitle)
                                    )
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilterChip(
                                            selected = !historySortByRecent,
                                            onClick = { historySortByRecent = false },
                                            label = { Text(stringResource(R.string.sort_by_count)) }
                                        )
                                        FilterChip(
                                            selected = historySortByRecent,
                                            onClick = { historySortByRecent = true },
                                            label = { Text(stringResource(R.string.sort_by_recent)) }
                                        )
                                    }
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
                            val lowerQuery = searchQuery.lowercase().trim()
                            val albumGroups = remember(allSongs, lowerQuery) {
                                allSongs.groupBy { it.album }
                                    .toList()
                                    .filter { it.first.lowercase().contains(lowerQuery) || it.second.any { s -> s.artist.lowercase().contains(lowerQuery) } }
                                    .sortedBy { it.first }
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(albumGroups) { (albumName, songs) ->
                                    AlbumRow(
                                        albumName = albumName,
                                        artistName = songs.firstOrNull()?.artist ?: stringResource(R.string.unknown_artist),
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
                            val lowerQuery = searchQuery.lowercase().trim()
                            val artistGroups = remember(allSongs, lowerQuery) {
                                allSongs.groupBy { it.artist }
                                    .toList()
                                    .filter { it.first.lowercase().contains(lowerQuery) }
                                    .sortedBy { it.first }
                            }
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
                                        Text(stringResource(R.string.action_new_custom_playlist), fontWeight = FontWeight.SemiBold)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    if (allPlaylists.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                            EmptyStateView(
                                                icon = Icons.Filled.QueueMusic,
                                                title = stringResource(R.string.empty_playlists_title),
                                                subtitle = stringResource(R.string.empty_playlists_subtitle)
                                            )
                                        }
                                    } else {
                                        val lowerQuery = searchQuery.lowercase().trim()
                                        val filteredPlaylists = remember(allPlaylists, lowerQuery) {
                                            if (lowerQuery.isEmpty()) allPlaylists
                                            else allPlaylists.filter { it.name.lowercase().contains(lowerQuery) }
                                        }
                                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            items(filteredPlaylists) { playlist ->
                                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                                PlaylistRow(
                                                    name = playlist.name,
                                                    subtitle = stringResource(R.string.created_at_label, sdf.format(java.util.Date(playlist.createdAt))),
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
                                                    Toast.makeText(context, context.getString(R.string.playlist_exported_to, file.name), Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        })
 {
                                            Icon(Icons.Default.FileDownload, stringResource(R.string.action_export_playlist))
                                        }

                                        Text(stringResource(R.string.song_count_label, playlistSongs.size), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                    }
                                    HorizontalDivider()
                                    if (playlistSongs.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            EmptyStateView(
                                                icon = Icons.Filled.PlaylistAdd,
                                                title = stringResource(R.string.empty_playlist_songs_title),
                                                subtitle = stringResource(R.string.empty_playlist_songs_subtitle)
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
                                                            withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.removed_from_playlist), Toast.LENGTH_SHORT).show() }
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

                        6 -> {
                            HistoryScreen(onBack = {
                                scope.launch { pagerState.animateScrollToPage(0) }
                            })
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
                    title = currentTitle!!, artist = currentArtist ?: stringResource(R.string.unknown_artist),
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
        var customMinutes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text(stringResource(R.string.sleep_timer_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (sleepTimerSeconds > 0) {
                        Text(stringResource(R.string.sleep_timer_current_status, sleepTimerSeconds / 60, sleepTimerSeconds % 60), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { sleepTimerSeconds = 0L; showSleepTimerDialog = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel_timer)) }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                    listOf(15 to stringResource(R.string.sleep_15min), 30 to stringResource(R.string.sleep_30min), 45 to stringResource(R.string.sleep_45min), 60 to stringResource(R.string.sleep_1hr), 90 to stringResource(R.string.sleep_90min)).forEach { (minutes, label) ->
                        TextButton(onClick = { sleepTimerSeconds = minutes * 60L; showSleepTimerDialog = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // 👇 新增：自定义分钟输入框
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = {
                            if (it.isEmpty() || it.all { char -> char.isDigit() }) customMinutes = it
                        },
                        label = { Text(stringResource(R.string.custom_minutes_label)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val mins = customMinutes.toLongOrNull()
                            if (mins != null && mins > 0) {
                                sleepTimerSeconds = mins * 60L
                                showSleepTimerDialog = false
                            } else {
                                Toast.makeText(context, context.getString(R.string.invalid_minutes_input), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = customMinutes.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.action_confirm_custom))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSleepTimerDialog = false }) { Text(stringResource(R.string.action_close)) } }
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
                        withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.deep_scan_started), Toast.LENGTH_SHORT).show() }
                        CoverArtCache.invalidateAll(context)
                        db.openHelper.writableDatabase.execSQL("DELETE FROM songs")
                        MusicUtils.syncLocalMusicToDatabase(context, dao, allowedFolders)
                        withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.deep_scan_done), Toast.LENGTH_LONG).show() }
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
                                Toast.makeText(context, context.getString(R.string.library_clean_no_duplicates), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onNearbyDevices = { showNearbyScreen = true; showSettingsScreen = false },
                onAbout         = { showAboutScreen  = true; showSettingsScreen = false }
            )
            BackHandler { showSettingsScreen = false }
        }
    }

    // 监听来自其他 Auralis 设备的推送
    val incomingPush by AuralisPushManager.incoming.collectAsState()
    incomingPush?.let { push ->
        val receiveEnabled = prefs.getBoolean("auralis_receive_enabled", true)
        if (receiveEnabled) {
            var isDownloading by remember { mutableStateOf(false) }
            var downloadProgress by remember { mutableFloatStateOf(0f) }

            if (isDownloading) {
                // 下载中：显示进度弹窗
                AlertDialog(
                    onDismissRequest = {},  // 下载中不能关闭
                    shape = RoundedCornerShape(24.dp),
                    title = { Text(stringResource(R.string.receiving_ellipsis), fontWeight = FontWeight.Bold) },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                push.songTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(20.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${(downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    confirmButton = {}
                )
            } else {
                IncomingPushDialog(
                    request  = push,
                    onAccept = {
                        isDownloading = true
                        scope.launch(Dispatchers.IO) {
                            val folder = android.os.Environment
                                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                                .absolutePath
                            val ok = downloadFromPeer(context, push, folder) { p ->
                                downloadProgress = p
                            }
                            withContext(Dispatchers.Main) {
                                isDownloading = false
                                AuralisPushManager.consume()
                                Toast.makeText(
                                    context,
                                    if (ok) context.getString(R.string.received_song, push.songTitle) else context.getString(R.string.download_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (ok) scope.launch {
                                    MusicUtils.syncLocalMusicToDatabase(context, dao, allowedFolders)
                                }
                            }
                        }
                    },
                    onDismiss = { AuralisPushManager.consume() }
                )
            }
        } else {
            AuralisPushManager.consume()
        }
    }
    if (showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { showDuplicateDialog = false },
            containerColor = MaterialTheme.colorScheme.surface, // 去掉花哨底色
            title = { Text(stringResource(R.string.action_clean_duplicates), fontWeight = FontWeight.W600, fontSize = 20.sp) },
            text = {
                // 记录当前点击展开了哪首歌的详细信息
                var expandedSongId by remember { mutableStateOf<Long?>(null) }

                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                    duplicatesList.forEachIndexed { index, group ->
                        item {
                            // 高级感标题：去掉了色块，采用极简的文字+留白
                            Text(
                                text = stringResource(R.string.duplicate_group_label, index + 1, group[0].title),
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
                                                    Toast.makeText(context, context.getString(R.string.delete_permanent_success), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, context.getString(R.string.delete_failed_file_locked), Toast.LENGTH_SHORT).show()
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
                                                Toast.makeText(context, context.getString(R.string.delete_error), Toast.LENGTH_SHORT).show()
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
                                        Text(stringResource(R.string.physical_path_label, song.data.substringAfterLast("/")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(6.dp))

                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            // 👇 检查位深度：如果还是显示“未知”，说明数据库里这列是 0
                                            val bitsText = if (song.bitDepth > 0) "${song.bitDepth}-bit" else stringResource(R.string.parsing_ellipsis)
                                            Text(
                                                text = stringResource(R.string.bit_depth_label, bitsText),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (song.bitDepth >= 24) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            // 👇 检查采样率
                                            val rateText = if (song.samplingRate > 0) {
                                                String.format("%.1f kHz", song.samplingRate / 1000f)
                                            } else {
                                                stringResource(R.string.parsing_ellipsis)
                                            }
                                            Text(
                                                text = stringResource(R.string.sample_rate_label, rateText),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (song.samplingRate > 48000) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        // 🔮 加一个小彩蛋：显示这首歌的音量增益，这是 jaudiotagger 是否活着的终极证据
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(R.string.replay_gain_label, String.format("%.2f dB", song.replayGain)),
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
            confirmButton = { TextButton(onClick = { showDuplicateDialog = false }) { Text(stringResource(R.string.action_close)) } }
        )
    }
    // ── Nearby Screen ─────────────────────────────────────────
    androidx.compose.animation.AnimatedVisibility(
        visible = showNearbyScreen,
        enter = slideInHorizontally { it },
        exit  = slideOutHorizontally { it }
    ) {
        NearbyScreen(
            onBack      = { showNearbyScreen = false },
            currentSong = allSongs.firstOrNull {
                it.data == (mediaController?.currentMediaItem?.mediaId ?: "")
            }
        )
        BackHandler { showNearbyScreen = false }
    }

// ── About Screen ─────────────────────────────────────────
    androidx.compose.animation.AnimatedVisibility(
        visible = showAboutScreen,
        enter = slideInHorizontally { it },
        exit  = slideOutHorizontally { it },
        modifier = Modifier.fillMaxSize()
    ) {
        AboutScreen(onBack = { showAboutScreen = false })
        BackHandler { showAboutScreen = false }
    }
    // 分享给附近设备的歌曲选择器
    songToShareToNearby?.let { song ->
        NearbyShareQuickSheet(
            song = song,
            onDismiss = { songToShareToNearby = null }
        )
    }

    if (showSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showSelectionDialog = false }, title = { Text(stringResource(R.string.found_new_songs_title)) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    item {
                        val allSelected = missingSongsList.all { it.isSelected }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { missingSongsList = missingSongsList.map { it.copy(isSelected = !allSelected) } }.padding(vertical = 8.dp)) {
                            Checkbox(checked = allSelected, onCheckedChange = null); Text(stringResource(R.string.action_select_all), fontWeight = FontWeight.Bold)
                        }; HorizontalDivider()
                    }
                    itemsIndexed(missingSongsList) { index, item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { val newList = missingSongsList.toMutableList(); newList[index] = item.copy(isSelected = !item.isSelected); missingSongsList = newList }.padding(vertical = 4.dp)) {
                            Checkbox(checked = item.isSelected, onCheckedChange = null)
                            Column { Text(item.remoteSong.filename, maxLines = 1, style = MaterialTheme.typography.bodyMedium); Row { Text("${item.remoteSong.size / 1048576} MB", style = MaterialTheme.typography.bodySmall, color = Color.Gray); if (item.remoteSong.has_lrc) Text(stringResource(R.string.has_lyrics_suffix), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
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
                        Toast.makeText(context, context.getString(R.string.switched_to_background_sync), Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.action_background_sync)) }
            },
            dismissButton = { TextButton(onClick = { showSelectionDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (showDownloadingDialog) {
        AlertDialog(
            onDismissRequest = {}, title = { Text(stringResource(R.string.syncing_title), fontWeight = FontWeight.Bold) },
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
            title = { Text(stringResource(R.string.add_to_playlist_title), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    item {
                        OutlinedButton(
                            onClick = { showNewPlaylistDialog = true },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Icon(Icons.Filled.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_new_playlist))
                        }
                    }
                    if (allPlaylists.isEmpty()) {
                        item { Text(stringResource(R.string.no_custom_playlists), color = Color.Gray, modifier = Modifier.padding(16.dp)) }
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
                                                Toast.makeText(context, context.getString(R.string.added_to_playlist_named, playlist.name), Toast.LENGTH_SHORT).show()
                                                songToAddToPlaylist = null // 关闭弹窗
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.add_to_playlist_failed), Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { songToAddToPlaylist = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    // 🪟 弹窗：输入新歌单名字
    if (showNewPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showNewPlaylistDialog = false; newPlaylistName = "" },
            title = { Text(stringResource(R.string.action_new_playlist)) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text(stringResource(R.string.playlist_name_label)) },
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
                                    Toast.makeText(context, context.getString(R.string.playlist_created_added_song), Toast.LENGTH_SHORT).show()
                                    showNewPlaylistDialog = false
                                    newPlaylistName = ""
                                    songToAddToPlaylist = null // 连带外层选择窗一起关闭
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, context.getString(R.string.playlist_created_success), Toast.LENGTH_SHORT).show()
                                    showNewPlaylistDialog = false
                                    newPlaylistName = ""
                                }
                            }
                        }
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showNewPlaylistDialog = false; newPlaylistName = "" }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

// ── 一起听：向房主拉取当前播放状态 ─────────────────────────────
private val roomStateHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}
private val roomStateGson = Gson()

/** 房主没开“一起听”或者请求失败，都直接返回 null——调用方按“暂时同步不上”处理，不崩、不弹一堆错误。 */
private fun fetchRoomState(device: DiscoveredDevice): RoomManager.RoomState? {
    return try {
        val request = Request.Builder()
            .url("http://${device.host}:${device.port}/auralis/room/state")
            .get()
            .build()
        roomStateHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            roomStateGson.fromJson(body, RoomManager.RoomState::class.java)
        }
    } catch (e: Exception) {
        null
    }
}
