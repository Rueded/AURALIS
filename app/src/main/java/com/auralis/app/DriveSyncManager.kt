package com.auralis.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.room.InvalidationTracker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Google Drive 云备份。
 *
 * 只备份"数据"，不备份音乐文件本身（音乐文件还是本地扫描）：
 * 红心收藏、播放次数/最近播放时间、播放历史（收听足迹用）、自定义歌单、EQ 预设。
 * 卸载重装 App 后，只要音乐文件还在手机同一路径下，扫描出来再一键恢复，
 * 这些数据就都能对得上（靠文件路径匹配）。
 *
 * 存在 Google Drive 的 "appDataFolder" 里——这是一块每个 App 专属、
 * 用户在自己 Drive 网页/App 里正常浏览是看不到的隐藏空间，不会占用户 Drive 里
 * 看得见的容量观感，卸载重装 App 不影响这块数据（除非用户自己去 Google 账号
 * 设置里撤销这个 App 的授权）。
 *
 * 技术选型：只用 play-services-auth 拿登录状态和 OAuth token，
 * 实际读写 Drive 走 Drive REST API v3 + 项目里本来就有的 OkHttp，
 * 没有再引入更重的 google-api-client 系列库。
 */
object DriveSyncManager {
    private const val BACKUP_FILENAME = "auralis_backup.json"
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val PREFS = "MusicSyncPrefs"
    private const val KEY_LAST_BACKUP_AT = "drive_last_backup_at"
    private const val KEY_NETEASE_COOKIE = "netease_custom_cookie" // 跟设置页里现有的 key 保持一致
    private const val KEY_LAN_COMPUTER_IP = "server_ip" // 对应 MusicAppScreen.kt 里 pcServerIp 用的那个 key
    private const val KEY_AUTO_BACKUP_ENABLED = "drive_auto_backup_enabled"
    private const val KEY_HAS_PENDING_CHANGES = "drive_has_pending_changes"
    private const val AUTO_BACKUP_WORK_NAME = "auralis_drive_auto_backup"

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class BackupPayload(
        @SerializedName("backedUpAt") val backedUpAt: Long,
        @SerializedName("favorites") val favorites: List<FavoriteEntry>,
        @SerializedName("playHistory") val playHistory: List<PlayHistory>,
        @SerializedName("playlists") val playlists: List<PlaylistBackup>,
        @SerializedName("eqPresets") val eqPresets: List<EqPreset>,
        @SerializedName("settings") val settings: SettingsBackup?
    )

    // 敏感设置：cookie 和局域网 IP 这两个字段在 JSON 里存的已经是加密后的密文，
    // 不是明文——就算哥哥事后想去 Drive 网页那边挖这个隐藏文件（其实也挖不到，
    // appDataFolder 用户自己都看不见），拿到的也只是一串乱码。
    data class SettingsBackup(
        @SerializedName("neteaseCookieEnc") val neteaseCookieEnc: String,
        @SerializedName("lanComputerIpEnc") val lanComputerIpEnc: String
    )

    data class FavoriteEntry(
        @SerializedName("path") val path: String,
        @SerializedName("isFavorite") val isFavorite: Boolean,
        @SerializedName("playCount") val playCount: Int,
        @SerializedName("lastPlayed") val lastPlayed: Long
    )

    data class PlaylistBackup(
        @SerializedName("name") val name: String,
        @SerializedName("createdAt") val createdAt: Long,
        @SerializedName("songPaths") val songPaths: List<String>
    )

    // ── 登录状态 ──────────────────────────────────────────────
    private fun signInClient(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail() // 必须要，getAccount() 内部靠 email 拼系统 Account，没有它会拿到 null
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun getSignedInAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun buildSignInIntent(context: Context): Intent = signInClient(context).signInIntent

    fun signOut(context: Context, onDone: () -> Unit) {
        signInClient(context).signOut().addOnCompleteListener { onDone() }
    }

    fun lastBackupAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_BACKUP_AT, 0L)

    private fun markBackedUpNow(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_BACKUP_AT, System.currentTimeMillis())
            .putBoolean(KEY_HAS_PENDING_CHANGES, false) // 备份成功了，之前排的那次改动就不算"待办"了
            .apply()
    }

    /** 换取一个可以直接拿去调 Drive REST API 的 OAuth2 access token。必须在 IO 线程调用。 */
    private fun fetchAccessToken(context: Context, account: GoogleSignInAccount): String {
        val accountEmail = account.email
            ?: throw IllegalStateException("Google 账号缺少 email，无法换取 token（请重新登录一次）")
        return GoogleAuthUtil.getToken(context, accountEmail, "oauth2:$DRIVE_SCOPE")
    }

    // ── 备份 ──────────────────────────────────────────────────

    /** 结果：成功返回 null，失败返回可展示给用户的错误信息。 */
    suspend fun backup(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val account = getSignedInAccount(context) ?: return@withContext context.getString(R.string.drive_not_signed_in)
            val token = fetchAccessToken(context, account)

            val dao = AppDatabase.getDatabase(context).songDao()
            val allSongs = dao.getAllSongs().first()
            val favorites = allSongs
                .filter { it.isFavorite || it.playCount > 0 }
                .map { FavoriteEntry(it.data, it.isFavorite, it.playCount, it.lastPlayed) }
            val history = dao.getAllHistorySync()
            val playlists = dao.getAllPlaylistsSync().map { pl ->
                PlaylistBackup(pl.name, pl.createdAt, dao.getPlaylistSongPaths(pl.id))
            }
            val eqPresets = AppDatabase.getDatabase(context).eqPresetDao().getAllPresets().first()

            // 敏感设置：先从 SharedPreferences 读出明文，加密之后才放进备份包，
            // 密钥是拿 account.email 派生出来的，所以这里要传进去
            val accountEmail = account.email ?: throw IllegalStateException("Google 账号缺少 email，无法加密敏感设置")
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val neteaseCookie = prefs.getString(KEY_NETEASE_COOKIE, "") ?: ""
            val lanComputerIp = prefs.getString(KEY_LAN_COMPUTER_IP, "") ?: ""
            val settingsBackup = SettingsBackup(
                neteaseCookieEnc = SettingsEncryptionHelper.encrypt(neteaseCookie, accountEmail),
                lanComputerIpEnc = SettingsEncryptionHelper.encrypt(lanComputerIp, accountEmail)
            )

            val payload = BackupPayload(
                backedUpAt = System.currentTimeMillis(),
                favorites = favorites,
                playHistory = history,
                playlists = playlists,
                eqPresets = eqPresets,
                settings = settingsBackup
            )
            val json = gson.toJson(payload)

            val existingFileId = findBackupFileId(token)
            uploadBackup(token, json, existingFileId)
            markBackedUpNow(context)
            null
        } catch (e: Exception) {
            android.util.Log.e("DriveSync", "backup failed", e)
            e.message ?: context.getString(R.string.drive_backup_failed)
        }
    }

    private fun findBackupFileId(token: String): String? {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name%3D%27$BACKUP_FILENAME%27&fields=files(id)")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
            val files = obj.getAsJsonArray("files") ?: return null
            if (files.size() == 0) return null
            return files[0].asJsonObject.get("id").asString
        }
    }

    private fun uploadBackup(token: String, json: String, existingFileId: String?) {
        val metadata = if (existingFileId == null)
            """{"name":"$BACKUP_FILENAME","parents":["appDataFolder"]}"""
        else
            """{"name":"$BACKUP_FILENAME"}"""

        // 注意：不要再手动加 Content-Type header，toRequestBody(mediaType) 已经带了，
        // 重复加会让 OkHttp 直接抛 "Unexpected header: Content-Type"
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(json.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()

        val url = if (existingFileId == null)
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        else
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=multipart"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .let { if (existingFileId == null) it.post(multipart) else it.patch(multipart) }
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Drive upload failed: HTTP ${resp.code}")
        }
    }

    // ── 恢复 ──────────────────────────────────────────────────

    /** 结果：成功返回 null，失败返回可展示给用户的错误信息。 */
    suspend fun restore(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val account = getSignedInAccount(context) ?: return@withContext context.getString(R.string.drive_not_signed_in)
            val token = fetchAccessToken(context, account)

            val fileId = findBackupFileId(token) ?: return@withContext context.getString(R.string.drive_no_backup_found)
            val json = downloadBackup(token, fileId) ?: return@withContext context.getString(R.string.drive_backup_failed)
            val payload = gson.fromJson(json, BackupPayload::class.java)

            val dao = AppDatabase.getDatabase(context).songDao()
            val eqDao = AppDatabase.getDatabase(context).eqPresetDao()

            // 收藏与统计：按文件路径匹配已扫描到的本地歌曲，对不上（比如那首歌文件已经不在了）就跳过
            payload.favorites.forEach { fav ->
                if (dao.getSongByPath(fav.path) != null) {
                    dao.restoreFavoriteAndStats(fav.path, fav.isFavorite, fav.playCount, fav.lastPlayed)
                }
            }

            // 播放历史：全量插入，重复的（同一条记录之前就已存在）会被自动忽略
            if (payload.playHistory.isNotEmpty()) {
                dao.insertHistoryList(payload.playHistory)
            }

            // 歌单：新建歌单 + 逐首按路径关联（本地没有的歌曲路径就跳过，不报错）
            payload.playlists.forEach { plBackup ->
                val newId = dao.createPlaylist(Playlist(name = plBackup.name, createdAt = plBackup.createdAt))
                plBackup.songPaths.forEach { path ->
                    if (dao.getSongByPath(path) != null) {
                        dao.addSongToPlaylist(PlaylistSong(playlistId = newId, songPath = path))
                    }
                }
            }

            // EQ 预设：直接恢复
            payload.eqPresets.forEach { preset -> eqDao.insertPreset(preset) }

            // 敏感设置：解密后写回 SharedPreferences。
            // 只有解密成功（非空）才覆盖本地现有值，避免账号不对或数据损坏时，
            // 拿一个空字符串把哥哥现在好好的 cookie / IP 设置覆盖掉
            val accountEmail = account.email
            if (payload.settings != null && accountEmail != null) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val decryptedCookie = SettingsEncryptionHelper.decrypt(payload.settings.neteaseCookieEnc, accountEmail)
                val decryptedIp = SettingsEncryptionHelper.decrypt(payload.settings.lanComputerIpEnc, accountEmail)
                isRestoringSettings = true
                prefs.edit().apply {
                    if (decryptedCookie.isNotEmpty()) putString(KEY_NETEASE_COOKIE, decryptedCookie)
                    if (decryptedIp.isNotEmpty()) putString(KEY_LAN_COMPUTER_IP, decryptedIp)
                }.apply()
                // 注意：SharedPreferences 的监听器回调是丢到主线程异步执行的，
                // 不能直接在这里（IO 线程）用 finally 立刻把标记重置回 false——
                // 那样很可能在监听器真正跑之前标记就已经被清掉了，保护形同虚设。
                // 改成也丢到主线程队列里，跟内部的监听器通知排在同一个队列，
                // 保证一定是先通知完、再重置。
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isRestoringSettings = false
                }
            }

            null
        } catch (e: Exception) {
            android.util.Log.e("DriveSync", "restore failed", e)
            e.message ?: context.getString(R.string.drive_backup_failed)
        }
    }

    private fun downloadBackup(token: String, fileId: String): String? {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    // ── 自动备份（"有改动才备份"，不是定时）──────────────────────

    // 5 张数据表，只要其中任何一张被写入（收藏、播放次数、历史、歌单、EQ 预设），
    // Room 的 InvalidationTracker 都会自动感知到，不用在每个写入的地方手动埋点。
    private val WATCHED_TABLES = arrayOf("songs", "play_history", "playlists", "playlist_songs", "eq_presets")
    private var invalidationObserver: InvalidationTracker.Observer? = null

    // IP 和 cookie 存在 SharedPreferences 里，跟数据库是两套机制，InvalidationTracker 感知不到，
    // 得单独注册一个监听。只盯这两个 key，别的 key（比如开关状态、上次备份时间）不能算进去，
    // 不然会变成自己写自己触发、无限循环
    private val WATCHED_PREF_KEYS = setOf(KEY_NETEASE_COOKIE, KEY_LAN_COMPUTER_IP)
    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    // restore() 往 SharedPreferences 写回解密后的设置时，会把这个设成 true，
    // 让上面那个监听器知道"这是恢复流程自己写的，不是用户手动改的"，跳过这一次，
    // 不然每次恢复完都会立刻又排一次备份，纯属多余
    @Volatile
    private var isRestoringSettings = false

    fun isAutoBackupEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_BACKUP_ENABLED, false)

    /** 设置页开关调用这个即可，只是存个开关状态；真正生效靠 startWatchingForChanges。 */
    fun setAutoBackupEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled)
            .apply()

        // 关掉的话，把还没来得及跑的那次排队备份也取消掉，避免关了之后它还偷偷跑一次
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(AUTO_BACKUP_WORK_NAME)
        }
    }

    /** 打上"有改动还没备份成功"的标记，然后排一次防抖备份。数据库变动、设置变动都走这一个函数。 */
    private fun markPendingAndSchedule(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HAS_PENDING_CHANGES, true)
            .apply()
        scheduleDebouncedBackup(context)
    }

    /**
     * App 启动时（Application.onCreate）调用一次即可，全程只需要调这一个。
     * 内部挂两个观察者：
     * 1. 数据库观察者——5 张表只要有写入就会触发，覆盖收藏、播放次数、歌单、EQ 这些。
     * 2. SharedPreferences 监听——专门盯 netease cookie 和局域网 IP 这两个 key。
     * 是否真的去排队备份，还要看用户有没有在设置页打开自动备份开关。
     * 用两个变量做去重，重复调用不会重复注册。
     */
    fun startWatchingForChanges(context: Context) {
        val appContext = context.applicationContext

        if (invalidationObserver == null) {
            val db = AppDatabase.getDatabase(appContext)
            val observer = object : InvalidationTracker.Observer(WATCHED_TABLES) {
                override fun onInvalidated(tables: Set<String>) {
                    if (isAutoBackupEnabled(appContext) && getSignedInAccount(appContext) != null) {
                        markPendingAndSchedule(appContext)
                    }
                }
            }
            db.invalidationTracker.addObserver(observer)
            invalidationObserver = observer
        }

        if (prefsListener == null) {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key in WATCHED_PREF_KEYS && !isRestoringSettings) {
                    if (isAutoBackupEnabled(appContext) && getSignedInAccount(appContext) != null) {
                        markPendingAndSchedule(appContext)
                    }
                }
            }
            // 必须存到变量里长期持有——SharedPreferences 内部只存弱引用，
            // 不存的话监听器很快会被系统回收掉，表现就是"用着用着自动备份突然不生效了"
            prefs.registerOnSharedPreferenceChangeListener(listener)
            prefsListener = listener
        }
    }

    /**
     * 回答哥哥那个问题：如果上次的 work 因为被杀后台（尤其是 MIUI 电池策略）
     * 没真正跑完，这次重新打开 App、进程重新起来、Application.onCreate 重新执行时，
     * 调这个函数就能"补一次"——不用等 3 分钟防抖，直接排队尽快跑。
     * 建议紧跟在 startWatchingForChanges(this) 后面调用。
     */
    fun retryPendingBackupIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val hasPending = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_PENDING_CHANGES, false)

        if (hasPending && isAutoBackupEnabled(appContext) && getSignedInAccount(appContext) != null) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(constraints) // 没有 delay，App 已经在前台了，尽快跑
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                AUTO_BACKUP_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    /**
     * 真正排队去跑一次备份，但是"防抖"的：3 分钟内如果又有新的改动进来，
     * 会把这次排队往后顶、重新计时，而不是立刻各跑各的。
     * 这样哥哥连着操作一堆歌（比如一口气收藏了 10 首），也只会在安静下来之后
     * 触发 1 次备份，不会疯狂调 Drive API，也不会一直耗电耗流量。
     */
    fun scheduleDebouncedBackup(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // 只想 WiFi 下才传，改成 NetworkType.UNMETERED
            .build()

        val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setInitialDelay(3, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            AUTO_BACKUP_WORK_NAME,
            ExistingWorkPolicy.REPLACE, // 关键：新改动进来就把旧的排队重置，等真正安静了才跑
            request
        )
    }
}

/** WorkManager 周期任务实体，真正在后台跑 DriveSyncManager.backup() 的地方。 */
class DriveBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val error = DriveSyncManager.backup(applicationContext)
        return if (error == null) {
            Result.success()
        } else {
            // 登录过期 / 没授权这类问题重试也没用，但为了简单起见统一走 retry
            // WorkManager 会做指数退避，不会疯狂重试打爆电量
            Result.retry()
        }
    }
}