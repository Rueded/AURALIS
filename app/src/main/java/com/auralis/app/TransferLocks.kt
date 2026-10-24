package com.auralis.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * 传输期间保持 CPU 和 Wi-Fi "醒着"。
 *
 * 为什么需要：这个项目里原来没有任何 WakeLock / WifiLock。
 * 息屏或 app 退到后台以后，Android 会让 Wi-Fi 进入省电模式（延迟变大、吞吐掉一大截，
 * 有的手机甚至会暂停收包），CPU 也会进入休眠——长时间同步/传歌时表现就是"越传越慢"或者"卡住不动"。
 * 前台服务只能保证进程不被杀，不保证网络和 CPU 不被降频。
 */
object TransferLocks {
    private const val TAG = "TransferLocks"

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var holders = 0
    private val handler = Handler(Looper.getMainLooper())
    private var autoRelease: Runnable? = null

    /**
     * 申请锁（可以重复调用，每次调用都要配一次 [release]）。
     * [maxMs] 是兜底：就算忘了释放，到时间也会自动全部释放，不会一直耗电。
     */
    @Synchronized
    fun acquire(context: Context, maxMs: Long = 2 * 60 * 60 * 1000L) {
        holders++
        try {
            val app = context.applicationContext
            if (wake == null) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Auralis:transfer").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifi == null) {
                val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                           else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = wm.createWifiLock(mode, "Auralis:transfer").apply { setReferenceCounted(false) }
            }
            wake?.acquire(maxMs)                       // 带超时的 WakeLock
            if (wifi?.isHeld != true) wifi?.acquire()
            // 重新计时的兜底释放
            autoRelease?.let { handler.removeCallbacks(it) }
            autoRelease = Runnable { releaseAll("超时自动释放") }.also { handler.postDelayed(it, maxMs) }
        } catch (e: Exception) {
            // 没有 WAKE_LOCK 权限或系统不允许时，不能影响传输本身
            Log.w(TAG, "申请锁失败: ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) releaseAll("传输结束")
    }

    @Synchronized
    private fun releaseAll(why: String) {
        holders = 0
        autoRelease?.let { handler.removeCallbacks(it) }
        autoRelease = null
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Exception) {}
        Log.d(TAG, "已释放 ($why)")
    }
}
