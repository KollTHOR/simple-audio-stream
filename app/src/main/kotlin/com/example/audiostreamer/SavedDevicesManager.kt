package com.example.audiostreamer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a user-saved device that remains permanently in Available Devices
 * until explicitly removed.
 */
data class SavedDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = AudioConfig.DEFAULT_PORT,
    val transportType: String = "Local Wi-Fi"
)

object SavedDevicesManager {
    private const val PREF_NAME = "saved_devices_prefs"
    private const val KEY_SAVED = "saved_devices_json"

    fun getSavedDevices(context: Context): List<SavedDevice> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SAVED, null) ?: return emptyList()
        val list = mutableListOf<SavedDevice>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ip = obj.optString("ip")
                if (ip.isNotBlank()) {
                    list.add(
                        SavedDevice(
                            id = obj.optString("id", ip),
                            name = obj.optString("name", "Audio Device"),
                            ip = ip,
                            port = obj.optInt("port", AudioConfig.DEFAULT_PORT),
                            transportType = obj.optString("transportType", "Local Wi-Fi")
                        )
                    )
                }
            }
        } catch (ignored: Exception) {}
        return list
    }

    fun saveDevice(context: Context, device: SavedDevice): Boolean {
        val current = getSavedDevices(context).toMutableList()
        current.removeAll { it.ip == device.ip || (device.id.isNotBlank() && it.id == device.id) }
        current.add(0, device)
        val arr = JSONArray()
        for (d in current) {
            val obj = JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("ip", d.ip)
                put("port", d.port)
                put("transportType", d.transportType)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAVED, arr.toString())
            .apply()
        return true
    }

    fun removeDevice(context: Context, ipOrId: String): Boolean {
        val current = getSavedDevices(context).toMutableList()
        val changed = current.removeAll { it.ip == ipOrId || it.id == ipOrId }
        if (changed) {
            val arr = JSONArray()
            for (d in current) {
                val obj = JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("ip", d.ip)
                    put("port", d.port)
                    put("transportType", d.transportType)
                }
                arr.put(obj)
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SAVED, arr.toString())
                .apply()
        }
        return changed
    }

    fun isDeviceSaved(context: Context, ip: String): Boolean {
        return getSavedDevices(context).any { it.ip == ip }
    }
}
