package com.auralis.app

import android.app.Application

class AuralisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DriveSyncManager.startWatchingForChanges(this)
        DriveSyncManager.retryPendingBackupIfNeeded(this) // 补跑没成功的那次
    }
}