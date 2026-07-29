package com.auralis.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * “一起听歌”功能的房间状态。
 *
 * 设计思路（复用项目里已有的“附近的 Auralis”局域网基础设施，没有新起一套协议）：
 * - 房主：正常本地播放，[MusicAppScreen] 里会定期把当前播放的歌 + 播放进度
 *   写进 [hostState]，[AuralisServer] 新增的 GET /auralis/room/state 接口会把这份状态原样返回。
 * - 访客：在“附近的 Auralis”里点某个已绑定设备的“一起听”，就是调用 [join]。
 *   [MusicAppScreen] 检测到 [joinedHost] 不为空时，会启动一个轮询协程，
 *   每隔 1~2 秒去问一次房主的 /auralis/room/state，然后本地对齐播放
 *   （换歌、跳进度、暂停/播放都跟房主一致）。
 *
 * 目前是“只读跟播”，访客端不能反向操控房主（没做双向控制），
 * 优点是实现简单、不容易冲突；如果之后想做“谁都能点暂停”的完全同步 KTV 模式，
 * 可以在这个基础上加一个 /auralis/room/control 的反向接口。
 */
object RoomManager {

    data class RoomState(
        val hostDeviceId: String,
        val hostDeviceName: String,
        val filename: String,      // 不传完整路径（跨设备路径没意义），访客按文件名去本地库里找同名歌曲
        val title: String,
        val artist: String,
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    // ── 房主侧 ──────────────────────────────────────────────
    private val _isHosting = MutableStateFlow(false)
    val isHosting: StateFlow<Boolean> = _isHosting.asStateFlow()

    private val _hostState = MutableStateFlow<RoomState?>(null)
    val hostState: StateFlow<RoomState?> = _hostState.asStateFlow()

    fun startHosting() {
        _isHosting.value = true
    }

    fun stopHosting() {
        _isHosting.value = false
        _hostState.value = null
    }

    /** 房主这边每次播放状态变化（切歌/暂停/播放/大幅度跳转）时调用。 */
    fun updateHostState(state: RoomState) {
        if (_isHosting.value) _hostState.value = state
    }

    // ── 访客侧 ──────────────────────────────────────────────
    private val _joinedHost = MutableStateFlow<DiscoveredDevice?>(null)
    val joinedHost: StateFlow<DiscoveredDevice?> = _joinedHost.asStateFlow()

    private val _remoteState = MutableStateFlow<RoomState?>(null)
    val remoteState: StateFlow<RoomState?> = _remoteState.asStateFlow()

    /** 记录“上一次已经对齐过”的（filename, updatedAtMs），避免轮询到同一份状态时反复 seekTo 造成播放卡顿。 */
    private val _lastAppliedUpdatedAtMs = MutableStateFlow(0L)
    val lastAppliedUpdatedAtMs: StateFlow<Long> = _lastAppliedUpdatedAtMs.asStateFlow()

    fun markApplied(updatedAtMs: Long) {
        _lastAppliedUpdatedAtMs.value = updatedAtMs
    }

    fun join(device: DiscoveredDevice) {
        _joinedHost.value = device
        _remoteState.value = null
        _lastAppliedUpdatedAtMs.value = 0L
    }

    fun leave() {
        _joinedHost.value = null
        _remoteState.value = null
        _lastAppliedUpdatedAtMs.value = 0L
    }

    fun setRemoteState(state: RoomState?) {
        _remoteState.value = state
    }
}
