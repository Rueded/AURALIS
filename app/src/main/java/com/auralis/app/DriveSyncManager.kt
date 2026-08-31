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
 * Google Drive 云同步。
 *
 * === 改动说明（相对旧版本）===
 * 旧版本的 backup()/restore() 是"整体快照覆盖"：
 *   backup() 把本机当前全部数据打包，直接整份覆盖 Drive 上的那一份；
 *   restore() 把 Drive 那份整份下载，往本机怼进去（而且歌单每次都是新建，越 restore 越重复）。
 * 这在只有一台设备时没问题，但两台设备各自 backup() 时，后写入的那次会把先写入的那次
 * 完全冲掉——比如手机上新收藏的歌，被平板那边稍早保存、但没包含这首新收藏的旧快照覆盖掉。
 *
 * 新版本把 backup()/restore() 都改成走同一套"下载远端 → 与本地合并 → 合并结果写回本地
 * → 合并结果整体上传"的流程，核心是「只增不丢」的合并规则：
 *   - 收藏 favorites：按歌曲路径合并，playCount/lastPlayed 取两边较大值（这两个字段本来就是
 *     只增不减的），isFavorite 取"两边任一为 true 则为 true"（OR 合并）。
 *   - 播放历史 playHistory：按 (歌曲路径, 时间戳) 去重后取并集，天然不会冲突。
 *   - 歌单 playlists：按歌单名字匹配（本地数据库目前没有跨设备稳定 ID，只能靠名字），
 *     歌曲列表取两边并集；应用到本地时会先看本地是否已有同名歌单，避免旧版本那个
 *     "每次 restore 都新建一个同名歌单"的重复 bug。
 *   - EQ 预设：按名字去重后合并，本地已有同名的就不会再重复插入。
 *   - 局域网 IP（server_ip）：这是设备/所在网络相关的本地信息，两台设备大概率不在同一个
 *     局域网，不应该跨设备同步——已从同步内容里彻底拿掉，只保留在各自设备本地。
 *
 * 已知取舍：isFavorite 用 OR 合并，意味着"在设备 A 上取消收藏"这个动作，如果设备 B 还没来得及
 * 把它自己那份"仍是收藏"的状态同步过一轮，有可能会让这首歌重新变回"收藏"状态。这是因为
 * Song 表目前没有单独记录"收藏状态是什么时候改的"这个时间戳，只能做只增不减的合并。
 * 真正彻底解决，需要给 Song 加一个 favoriteUpdatedAt 字段（并做数据库迁移），
 * 用它来做"最后修改时间更新的一方获胜"的合并，而不是 OR 合并。这个改动涉及数据库版本升级，
 * 建议单独作为下一步来做，这里先用侵入性最小的方式把"整体覆盖"这个更严重的问题解决掉。
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
    private const val KEY_SETTINGS_UPDATED_AT = "netease_cookie_updated_at" // 专门记"cookie 真正被改动的时间"，不是备份时间
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

    // 敏感设置：cookie 这个字段在 JSON 里存的已经是加密后的密文，不是明文。
    // lanComputerIpEnc 字段保留在结构里只是为了兼容旧备份文件的 JSON 格式，
    // 新版本永远传空字符串、也不再读取它——局域网 IP 不跨设备同步。
    //
    // 【关键 bug 修复】settingsUpdatedAt：老版本合并 cookie 时用的是 BackupPayload.backedUpAt，
    // 但那个时间戳在 buildLocalPayload() 里每次都会被重新盖成 System.currentTimeMillis()——
    // 也就是说"本地这一份"的时间戳永远是"现在"，几乎必然大于云端那份"过去某次上传"的时间戳。
    // 结果就是无论云端存的 cookie 是不是有效值，合并时永远选本地这一份；
    // 如果本地这台设备根本没填过 cookie（本地是空字符串），空值就会把云端真正填过的 cookie 顶掉、
    // 或者"从云端恢复"永远恢复不回来，跟哥哥说的"cookie 没有备份"完全对得上。
    // 改成这个专门的时间戳只在"cookie 真的被改动"的时候才更新（见 SettingsScreen.kt），
    // 才能真实反映"哪一份更新"，而不是"哪一份是刚跑过备份"。
    data class SettingsBackup(
        @SerializedName("neteaseCookieEnc") val neteaseCookieEnc: String,
        @SerializedName("lanComputerIpEnc") val lanComputerIpEnc: String = "",
        @SerializedName("settingsUpdatedAt") val settingsUpdatedAt: Long = 0L
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
            .putBoolean(KEY_HAS_PENDING_CHANGES, false) // 同步成功了，之前排的那次改动就不算"待办"了
            .apply()
    }

    /** 换取一个可以直接拿去调 Drive REST API 的 OAuth2 access token。必须在 IO 线程调用。 */
    private fun fetchAccessToken(context: Context, account: GoogleSignInAccount): String {
        val accountEmail = account.email
            ?: throw IllegalStateException("Google 账号缺少 email，无法换取 token（请重新登录一次）")
        return GoogleAuthUtil.getToken(context, accountEmail, "oauth2:$DRIVE_SCOPE")
    }

    // ── 对外入口：backup() 和 restore() 现在共用同一套"合并同步"逻辑 ──────────

    /**
     * 结果：成功返回 null，失败返回可展示给用户的错误信息。
     * 语义：合并同步。云端没有备份时会创建一份（只包含本机数据）。
     */
    suspend fun backup(context: Context): String? = mergeSync(context, requireRemote = false)

    /**
     * 结果：成功返回 null，失败返回可展示给用户的错误信息。
     * 语义：合并同步。跟 backup() 走的是同一条路径，唯一区别是云端完全没有
     * 备份文件时会报错提示"未找到备份"，而不是静默创建一份空的。
     */
    suspend fun restore(context: Context): String? = mergeSync(context, requireRemote = true)

    private suspend fun mergeSync(context: Context, requireRemote: Boolean): String? =
        withContext(Dispatchers.IO) {
            try {
                val account = getSignedInAccount(context)
                    ?: return@withContext context.getString(R.string.drive_not_signed_in)
                val token = fetchAccessToken(context, account)
                val accountEmail = account.email
                    ?: throw IllegalStateException("Google 账号缺少 email，无法同步")

                val existingFileId = findBackupFileId(token)
                if (requireRemote && existingFileId == null) {
                    return@withContext context.getString(R.string.drive_no_backup_found)
                }

                val remotePayload = existingFileId?.let { fileId ->
                    downloadBackup(token, fileId)?.let { json ->
                        runCatching { gson.fromJson(json, BackupPayload::class.java) }.getOrNull()
                    }
                }

                val localPayload = buildLocalPayload(context, accountEmail)
                val merged = mergePayloads(localPayload, remotePayload)

                // 先把合并结果落回本地——这样不管点的是"备份"还是"恢复"按钮，
                // 只要另一台设备之前同步过的新内容，这一步都会一起补齐到本机，
                // 真正做到双向同步，而不是单纯"谁最后点谁说了算"。
                applyPayloadToLocal(context, merged, accountEmail)

                // 再把合并结果整体传回 Drive。这里覆盖的是"合并之后"的结果，
                // 不会丢任何一边独有的数据，跟旧版本"直接拿本机快照覆盖"的性质完全不同。
                val mergedJson = gson.toJson(merged)
                uploadBackup(token, mergedJson, existingFileId)

                markBackedUpNow(context)
                null
            } catch (e: Exception) {
                android.util.Log.e("DriveSync", "sync failed", e)
                e.message ?: context.getString(R.string.drive_backup_failed)
            }
        }

    // ── 读取本机当前数据，打包成待合并的 payload ──────────────────────

    private suspend fun buildLocalPayload(context: Context, accountEmail: String): BackupPayload {
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
        // 密钥是拿 account.email 派生出来的
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val neteaseCookie = prefs.getString(KEY_NETEASE_COOKIE, "") ?: ""
        // 注意这里读的是"cookie 最后一次真正被改动"的时间戳，不是 System.currentTimeMillis()——
        // 这就是修复合并 bug 的关键，见上面 SettingsBackup 的注释
        val settingsUpdatedAt = prefs.getLong(KEY_SETTINGS_UPDATED_AT, 0L)
        val settingsBackup = SettingsBackup(
            neteaseCookieEnc = SettingsEncryptionHelper.encrypt(neteaseCookie, accountEmail),
            lanComputerIpEnc = "", // 局域网 IP 不跨设备同步，永远留空
            settingsUpdatedAt = settingsUpdatedAt
        )

        return BackupPayload(
            backedUpAt = System.currentTimeMillis(),
            favorites = favorites,
            playHistory = history,
            playlists = playlists,
            eqPresets = eqPresets,
            settings = settingsBackup
        )
    }

    // ── 合并规则：本地 + 远端 → 合并结果（只增不丢）──────────────────────

    private fun mergePayloads(local: BackupPayload, remote: BackupPayload?): BackupPayload {
        if (remote == null) return local
        return BackupPayload(
            backedUpAt = System.currentTimeMillis(),
            favorites = mergeFavorites(local.favorites, remote.favorites),
            playHistory = mergeHistory(local.playHistory, remote.playHistory),
            playlists = mergePlaylists(local.playlists, remote.playlists),
            eqPresets = mergeEqPresets(local.eqPresets, remote.eqPresets),
            settings = pickSettings(local.settings, remote.settings)
        )
    }

    // cookie 是账号级别的东西，两台设备理论上该是同一份；真出现分歧时，
    // 按"cookie 真正被改动的时间"（settingsUpdatedAt）判断谁更新，而不是整份备份的时间戳
    // ——见 SettingsBackup 数据类上面那段注释，这里就是修复那个 bug 的地方。
    private fun pickSettings(local: SettingsBackup?, remote: SettingsBackup?): SettingsBackup? {
        if (local == null) return remote
        if (remote == null) return local
        return when {
            local.settingsUpdatedAt > remote.settingsUpdatedAt -> local
            remote.settingsUpdatedAt > local.settingsUpdatedAt -> remote
            // 时间戳打平：多半是老版本升级上来，两边都还是默认的 0。
            // 这种情况下优先选内容不为空的那一份，避免一台从没填过 cookie 的设备
            // 用空值把另一台真正填过的 cookie 顶掉
            local.neteaseCookieEnc.isNotEmpty() -> local
            else -> remote
        }
    }

    private fun mergeFavorites(
        local: List<FavoriteEntry>,
        remote: List<FavoriteEntry>
    ): List<FavoriteEntry> {
        val merged = LinkedHashMap<String, FavoriteEntry>()
        local.forEach { merged[it.path] = it }
        remote.forEach { r ->
            val l = merged[r.path]
            merged[r.path] = if (l == null) r else FavoriteEntry(
                path = r.path,
                isFavorite = l.isFavorite || r.isFavorite, // OR 合并，见文件顶部说明的取舍
                playCount = maxOf(l.playCount, r.playCount), // 只增不减，取大值绝对安全
                lastPlayed = maxOf(l.lastPlayed, r.lastPlayed)
            )
        }
        return merged.values.toList()
    }

    private fun mergeHistory(local: List<PlayHistory>, remote: List<PlayHistory>): List<PlayHistory> {
        val seen = HashSet<Pair<String, Long>>()
        val merged = mutableListOf<PlayHistory>()
        (local + remote).forEach {
            val key = it.songPath to it.timestamp
            if (seen.add(key)) merged.add(it)
        }
        return merged
    }

    private fun mergePlaylists(
        local: List<PlaylistBackup>,
        remote: List<PlaylistBackup>
    ): List<PlaylistBackup> {
        val merged = LinkedHashMap<String, PlaylistBackup>()
        local.forEach { merged[it.name] = it }
        remote.forEach { r ->
            val l = merged[r.name]
            merged[r.name] = if (l == null) r else PlaylistBackup(
                name = r.name,
                createdAt = minOf(l.createdAt, r.createdAt), // 以先创建的那次为准
                songPaths = (l.songPaths + r.songPaths).distinct() // 并集，不会丢歌
            )
        }
        return merged.values.toList()
    }

    private fun mergeEqPresets(local: List<EqPreset>, remote: List<EqPreset>): List<EqPreset> {
        val merged = LinkedHashMap<String, EqPreset>()
        local.forEach { merged[it.name] = it }
        remote.forEach { r -> if (!merged.containsKey(r.name)) merged[r.name] = r }
        return merged.values.toList()
    }

    // ── 把合并结果写回本地数据库 / SharedPreferences ──────────────────────

    private suspend fun applyPayloadToLocal(
        context: Context,
        merged: BackupPayload,
        accountEmail: String
    ) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.songDao()
        val eqDao = db.eqPresetDao()

        // 收藏与统计：按文件路径匹配已扫描到的本地歌曲，对不上（比如那首歌文件已经不在了）就跳过
        merged.favorites.forEach { fav ->
            if (dao.getSongByPath(fav.path) != null) {
                dao.restoreFavoriteAndStats(fav.path, fav.isFavorite, fav.playCount, fav.lastPlayed)
            }
        }

        // 播放历史：按 (歌曲路径, 时间戳) 去重，只插入本地真的没有的那些，
        // 并把 id 重置成 0 让 Room 用本机自增 id 生成——不能直接沿用云端 JSON 里的 id，
        // 那个 id 是"另一台设备自己数据库里的自增 id"，两台设备各自独立递增，
        // 完全有可能撞号但其实是两条不相关的记录，用它做去重反而不准。
        val existingHistoryKeys = dao.getAllHistorySync().map { it.songPath to it.timestamp }.toHashSet()
        val newHistory = merged.playHistory
            .filter { (it.songPath to it.timestamp) !in existingHistoryKeys }
            .map { it.copy(id = 0) }
        if (newHistory.isNotEmpty()) {
            dao.insertHistoryList(newHistory)
        }

        // 歌单：先按名字找本地是否已有同名歌单，有就复用它的 id，
        // 没有才新建——避免旧版本"每次都新建一个同名歌单"的重复问题。
        // 歌曲关联只补本地缺的那些。
        val existingPlaylists = dao.getAllPlaylistsSync().associateBy { it.name }
        merged.playlists.forEach { plBackup ->
            val playlistId = existingPlaylists[plBackup.name]?.id
                ?: dao.createPlaylist(Playlist(name = plBackup.name, createdAt = plBackup.createdAt))
            val existingPaths = dao.getPlaylistSongPaths(playlistId).toHashSet()
            plBackup.songPaths.forEach { path ->
                if (path !in existingPaths && dao.getSongByPath(path) != null) {
                    dao.addSongToPlaylist(PlaylistSong(playlistId = playlistId, songPath = path))
                }
            }
        }

        // EQ 预设：按名字去重，本地已有同名的就不再重复插入
        // （insertPreset 是按自增 id REPLACE 的，不按名字，所以这一步去重必须在这里做）
        val existingPresetNames = eqDao.getAllPresets().first().map { it.name }.toHashSet()
        merged.eqPresets.forEach { preset ->
            if (preset.name !in existingPresetNames) {
                eqDao.insertPreset(preset.copy(id = 0))
            }
        }

        // 敏感设置：只解密应用 cookie。局域网 IP 不再从云端下发，
        // 保持每台设备自己本地那份设置不被覆盖。
        if (merged.settings != null) {
            val decryptedCookie = SettingsEncryptionHelper.decrypt(merged.settings.neteaseCookieEnc, accountEmail)
            if (decryptedCookie.isNotEmpty()) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                isRestoringSettings = true
                prefs.edit()
                    .putString(KEY_NETEASE_COOKIE, decryptedCookie)
                    // 把本机的"更新时间"也校准成 merged 之后的那个值，
                    // 不然下次这台设备自己再触发一次备份时，读到的还是旧时间戳，
                    // 又会在下一轮合并里被误判成"比云端旧"
                    .putLong(KEY_SETTINGS_UPDATED_AT, merged.settings.settingsUpdatedAt)
                    .apply()
                // SharedPreferences 的监听器回调是丢到主线程异步执行的，
                // 不能直接在这里（IO 线程）用 finally 立刻把标记重置回 false——
                // 那样很可能在监听器真正跑之前标记就已经被清掉了，保护形同虚设。
                // 改成也丢到主线程队列里，跟内部的监听器通知排在同一个队列，
                // 保证一定是先通知完、再重置。
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isRestoringSettings = false
                }
            }
        }
    }

    // ── Drive REST API 读写（未改动）──────────────────────────────

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

    // ── 自动备份（"有改动才同步"，不是定时）──────────────────────

    // 5 张数据表，只要其中任何一张被写入（收藏、播放次数、历史、歌单、EQ 预设），
    // Room 的 InvalidationTracker 都会自动感知到，不用在每个写入的地方手动埋点。
    private val WATCHED_TABLES = arrayOf("songs", "play_history", "playlists", "playlist_songs", "eq_presets")
    private var invalidationObserver: InvalidationTracker.Observer? = null

    // cookie 存在 SharedPreferences 里，跟数据库是两套机制，InvalidationTracker 感知不到，
    // 得单独注册一个监听。只盯这一个 key——局域网 IP 已经不参与同步了，改它不需要触发同步。
    private val WATCHED_PREF_KEYS = setOf(KEY_NETEASE_COOKIE)
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

    /** 打上"有改动还没同步成功"的标记，然后排一次防抖同步。数据库变动、设置变动都走这一个函数。 */
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
     * 2. SharedPreferences 监听——专门盯 netease cookie 这个 key。
     * 是否真的去排队同步，还要看用户有没有在设置页打开自动备份开关。
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
     * 如果上次的 work 因为被杀后台（尤其是 MIUI 电池策略）没真正跑完，
     * 这次重新打开 App、进程重新起来、Application.onCreate 重新执行时，
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
     * 真正排队去跑一次同步，但是"防抖"的：3 分钟内如果又有新的改动进来，
     * 会把这次排队往后顶、重新计时，而不是立刻各跑各的。
     * 这样连着操作一堆歌（比如一口气收藏了 10 首），也只会在安静下来之后
     * 触发 1 次同步，不会疯狂调 Drive API，也不会一直耗电耗流量。
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