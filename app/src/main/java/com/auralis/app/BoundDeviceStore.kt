package com.auralis.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class BoundDevice(
    val deviceId: String,
    val displayName: String,
    val lastSeenMs: Long = 0L
)

object BoundDeviceStore {
    private const val PREFS = "auralis_bound_devices"
    private const val KEY   = "devices_json"
    private val gson = Gson()

    fun getAll(context: Context): List<BoundDevice> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<BoundDevice>>() {}.type)
        } catch (_: Exception) { emptyList() }
    }

    fun bind(context: Context, device: BoundDevice) {
        val list = getAll(context).toMutableList()
        list.removeAll { it.deviceId == device.deviceId }
        list.add(0, device.copy(lastSeenMs = System.currentTimeMillis()))
        save(context, list)
    }

    fun updateSeen(context: Context, deviceId: String) {
        val list = getAll(context).toMutableList()
        val idx = list.indexOfFirst { it.deviceId == deviceId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(lastSeenMs = System.currentTimeMillis())
            save(context, list)
        }
    }

    fun unbind(context: Context, deviceId: String) {
        save(context, getAll(context).filter { it.deviceId != deviceId })
    }

    fun isBound(context: Context, deviceId: String) =
        getAll(context).any { it.deviceId == deviceId }

    private fun save(context: Context, list: List<BoundDevice>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, gson.toJson(list)).apply()
    }
}