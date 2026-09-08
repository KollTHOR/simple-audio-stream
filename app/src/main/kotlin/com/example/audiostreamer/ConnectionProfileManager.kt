package com.example.audiostreamer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object ConnectionProfileManager {
    private const val TAG = "ConnectionProfileMgr"
    private const val PREF_NAME = "connection_profiles_prefs"
    private const val KEY_PROFILES = "saved_profiles_json"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

    private val _profilesFlow = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    val profilesFlow: StateFlow<List<ConnectionProfile>> = _profilesFlow.asStateFlow()

    private val _activeProfileFlow = MutableStateFlow<ConnectionProfile?>(null)
    val activeProfileFlow: StateFlow<ConnectionProfile?> = _activeProfileFlow.asStateFlow()

    fun init(context: Context) {
        val list = loadProfiles(context)
        _profilesFlow.value = list

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
        _activeProfileFlow.value = active
    }

    @Synchronized
    fun getProfiles(context: Context): List<ConnectionProfile> {
        return loadProfiles(context)
    }

    @Synchronized
    fun saveProfile(context: Context, profile: ConnectionProfile) {
        val current = loadProfiles(context).toMutableList()
        val index = current.indexOfFirst { it.id == profile.id || (it.targetIp == profile.targetIp && it.connectionType == profile.connectionType) }
        if (index >= 0) {
            current[index] = profile.copy(id = current[index].id)
        } else {
            current.add(0, profile)
        }
        persistProfiles(context, current)
        _profilesFlow.value = current

        if (_activeProfileFlow.value?.id == profile.id || _activeProfileFlow.value == null) {
            setActiveProfile(context, profile)
        }
    }

    @Synchronized
    fun deleteProfile(context: Context, profileId: String) {
        val current = loadProfiles(context).toMutableList()
        current.removeAll { it.id == profileId }
        persistProfiles(context, current)
        _profilesFlow.value = current

        if (_activeProfileFlow.value?.id == profileId) {
            val next = current.firstOrNull()
            setActiveProfile(context, next)
        }
    }

    @Synchronized
    fun setActiveProfile(context: Context, profile: ConnectionProfile?) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, profile?.id).apply()
        _activeProfileFlow.value = profile
        Log.i(TAG, "Active profile set to: ${profile?.name} (${profile?.targetIp})")
    }

    fun findMatchingProfile(context: Context, targetIp: String): ConnectionProfile? {
        return loadProfiles(context).firstOrNull { it.targetIp == targetIp }
    }

    fun createOrUpdateFromDiscoveredDevice(
        context: Context,
        device: DiscoveredDevice,
        type: ConnectionType = ConnectionType.LOCAL_WIFI
    ): ConnectionProfile {
        val existing = findMatchingProfile(context, device.ip)
        val updated = existing?.copy(
            name = device.name,
            capabilitiesMask = if (device.capabilitiesMask != 0) device.capabilitiesMask else existing.capabilitiesMask,
            p2pSsid = device.p2pSsid ?: existing.p2pSsid,
            p2pPassphrase = device.p2pPassphrase ?: existing.p2pPassphrase,
            lastConnectedTimeMs = System.currentTimeMillis()
        ) ?: ConnectionProfile(
            name = device.name,
            targetIp = device.ip,
            port = device.port,
            connectionType = type,
            p2pSsid = device.p2pSsid,
            p2pPassphrase = device.p2pPassphrase,
            capabilitiesMask = device.capabilitiesMask,
            lastConnectedTimeMs = System.currentTimeMillis()
        )
        saveProfile(context, updated)
        return updated
    }

    @Synchronized
    fun updateProfilePreferences(
        context: Context,
        profileId: String,
        name: String,
        targetIp: String,
        port: Int,
        preferredStreamingProfile: String
    ): ConnectionProfile? {
        val current = loadProfiles(context).toMutableList()
        val index = current.indexOfFirst { it.id == profileId }
        if (index >= 0) {
            val updated = current[index].copy(
                name = name,
                targetIp = targetIp,
                port = port,
                preferredStreamingProfile = preferredStreamingProfile,
                lastConnectedTimeMs = System.currentTimeMillis()
            )
            current[index] = updated
            persistProfiles(context, current)
            _profilesFlow.value = current
            if (_activeProfileFlow.value?.id == profileId) {
                setActiveProfile(context, updated)
            }
            return updated
        }
        return null
    }

    private fun loadProfiles(context: Context): List<ConnectionProfile> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val array = JSONArray(rawJson)
            val list = mutableListOf<ConnectionProfile>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(ConnectionProfile.fromJson(obj))
            }
            list.sortedByDescending { it.lastConnectedTimeMs }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing saved connection profiles: ${e.message}")
            emptyList()
        }
    }

    private fun persistProfiles(context: Context, list: List<ConnectionProfile>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (p in list) {
            array.put(p.toJson())
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }
}
