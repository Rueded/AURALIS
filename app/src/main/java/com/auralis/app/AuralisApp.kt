package com.auralis.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuralisApp : Application() {

    companion object {
        private val _isInForeground = MutableStateFlow(false)
        /** 应用界面当前是否可见——系统级悬浮歌词只在不可见（在别的应用里）时显示。 */
        val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()
    }

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        // 用已 start 的 Activity 计数判断前后台，不用额外依赖 lifecycle-process
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                _isInForeground.value = true
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                // 旋转屏幕等配置变更时会短暂变成 0 再变回来，isChangingConfigurations 时不算退到后台
                if (startedActivities == 0 && !activity.isChangingConfigurations) _isInForeground.value = false
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        DriveSyncManager.startWatchingForChanges(this)
        DriveSyncManager.retryPendingBackupIfNeeded(this) // 补跑没成功的那次
    }
}
