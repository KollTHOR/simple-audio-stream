package com.example.audiostreamer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.audiostreamer.node.LocalNodeManager
import com.google.android.material.color.DynamicColors

/**
 * Custom Application class for HAT AudioStreamer.
 * Initializes network utilities and local node state on app startup.
 */
class AudioStreamerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Dark is the only supported theme: the palette (and its greys) are tuned for dark
        // surfaces, so forcing night here removes the light-mode contrast bugs by construction.
        // Set before any Activity inflates to avoid a light→dark flash.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        DynamicColors.applyToActivitiesIfAvailable(this) // Material You: wallpaper-adaptive tinting on Android 12+
        NetworkUtils.init(this)
        DiscoveryManager.init(this)
        LocalNodeManager.init(this)
        UserAlertCenter.init(this)
    }
}
