package com.auralis.app

import android.content.Context
import java.util.UUID

object AuralisDeviceId {
    private const val PREFS = "auralis_device"
    private const val KEY_ID   = "device_id"
    private const val KEY_NAME = "device_name"

    fun getId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }
    }

    fun getName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_NAME, null)
            ?: android.os.Build.MODEL.also {
                prefs.edit().putString(KEY_NAME, it).apply()
            }
    }

    fun setName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, name).apply()
    }
}