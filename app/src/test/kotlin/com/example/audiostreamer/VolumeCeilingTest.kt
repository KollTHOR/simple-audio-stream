package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class VolumeCeilingTest {

    @Before
    fun setUp() {
        AudioCaptureService.remoteVolumePercent.set(100)
        AudioCaptureService.receiverVolumes.clear()
    }

    @Test
    fun testMasterVolumeActsAsCeilingForReceiverVolumes() {
        // Master at 70%
        AudioCaptureService.remoteVolumePercent.set(70)

        // Attempt to set receiver volume higher than master ceiling (e.g. 90%)
        AudioCaptureService.setReceiverVolume("192.168.1.50", "node-1", 90)

        // Must be clamped to 70%
        assertEquals(70, AudioCaptureService.getReceiverVolume("192.168.1.50", "node-1"))

        // Set receiver volume lower than master ceiling (e.g. 40%)
        AudioCaptureService.setReceiverVolume("192.168.1.50", "node-1", 40)

        // Must remain 40%
        assertEquals(40, AudioCaptureService.getReceiverVolume("192.168.1.50", "node-1"))
    }

    @Test
    fun testUnsetReceiverVolumeDefaultsToMasterVolume() {
        AudioCaptureService.remoteVolumePercent.set(65)
        assertEquals(65, AudioCaptureService.getReceiverVolume("192.168.1.99", null))
    }

    @Test
    fun testMasterVolumeClampRules() {
        AudioCaptureService.remoteVolumePercent.set(80)
        AudioCaptureService.setReceiverVolume("192.168.1.10", null, 60)
        AudioCaptureService.setReceiverVolume("192.168.1.20", null, 40)

        assertEquals(60, AudioCaptureService.getReceiverVolume("192.168.1.10", null))
        assertEquals(40, AudioCaptureService.getReceiverVolume("192.168.1.20", null))

        // Lower master to 50%
        val newMaster = 50
        AudioCaptureService.remoteVolumePercent.set(newMaster)
        for (entry in AudioCaptureService.receiverVolumes.entries) {
            if (entry.value > newMaster) {
                entry.setValue(newMaster)
            }
        }

        // Receiver 1 (was 60%) is now clamped to 50%
        assertEquals(50, AudioCaptureService.getReceiverVolume("192.168.1.10", null))
        // Receiver 2 (was 40%) stays at 40%
        assertEquals(40, AudioCaptureService.getReceiverVolume("192.168.1.20", null))
    }
}
