package com.example.audiostreamer

import android.app.Application
import com.example.audiostreamer.node.LocalNodeManager
import com.google.android.material.color.DynamicColors

/**
 * Custom Application class for HAT AudioStreamer.
 * Initializes network utilities and local node state on app startup.
 */
class AudioStreamerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this) // Material You: wallpaper-adaptive tinting on Android 12+
        NetworkUtils.init(this)
        DiscoveryManager.init(this)
        LocalNodeManager.init(this)
    }
}
