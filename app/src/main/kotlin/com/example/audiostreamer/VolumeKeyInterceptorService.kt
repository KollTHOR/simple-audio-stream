package com.example.audiostreamer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.audiostreamer.AppLogger as Log
import java.util.concurrent.atomic.AtomicBoolean

class VolumeKeyInterceptorService : AccessibilityService() {

    companion object {
        private const val TAG = "VolKeyInterceptor"
        val isRunning = AtomicBoolean(false)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning.set(true)
        Log.i(TAG, "VolumeKeyInterceptorService connected")
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning.set(false)
        Log.i(TAG, "VolumeKeyInterceptorService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        // Only intercept when audio transmitter is actively running
        if (!AudioCaptureService.isRunning.get()) {
            return false
        }

        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0 || event.repeatCount % 3 == 0) {
                val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 5 else -5
                val current = AudioCaptureService.remoteVolumePercent.get()
                val newVol = (current + delta).coerceIn(0, 100)

                val intent = Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_SET_VOLUME
                    putExtra(AudioCaptureService.EXTRA_VOLUME_PERCENT, newVol)
                }
                startService(intent)
            }
        }

        // Consume both ACTION_DOWN and ACTION_UP to avoid un-silencing the transmitter phone
        return true
    }
}
