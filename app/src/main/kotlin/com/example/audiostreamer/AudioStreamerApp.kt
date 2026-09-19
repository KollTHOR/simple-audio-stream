package com.example.audiostreamer

import android.app.Application
import com.example.audiostreamer.node.LocalNodeManager

/**
 * Custom Application class for HAT AudioStreamer.
 * Initializes network utilities and local node state on app startup.
 */
class AudioStreamerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NetworkUtils.init(this)
        LocalNodeManager.init(this)
    }
}
