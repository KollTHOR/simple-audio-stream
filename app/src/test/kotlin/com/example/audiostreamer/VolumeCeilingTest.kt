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
    fun testMasterVolumeDeltaMovesReceiverVolumesEquallyWhenMisaligned() {
        // Master at 80%
        AudioCaptureService.remoteVolumePercent.set(80)
        // Two misaligned receivers: 60% and 40%
        AudioCaptureService.setReceiverVolume("192.168.1.10", "node-1", 60)
        AudioCaptureService.setReceiverVolume("192.168.1.20", "node-2", 40)

        assertEquals(60, AudioCaptureService.getReceiverVolume("192.168.1.10", "node-1"))
        assertEquals(40, AudioCaptureService.getReceiverVolume("192.168.1.20", "node-2"))

        // Lower master by 10% (delta = -10, newMaster = 70%)
        AudioCaptureService.applyMasterVolumeDelta(-10)
        assertEquals(70, AudioCaptureService.remoteVolumePercent.get())
        // Both receivers moved down by 10%
        assertEquals(50, AudioCaptureService.getReceiverVolume("192.168.1.10", "node-1"))
        assertEquals(30, AudioCaptureService.getReceiverVolume("192.168.1.20", "node-2"))

        // Lower master by another 20% (delta = -20, newMaster = 50%)
        AudioCaptureService.applyMasterVolumeDelta(-20)
        assertEquals(50, AudioCaptureService.remoteVolumePercent.get())
        // Both receivers moved down by 20%
        assertEquals(30, AudioCaptureService.getReceiverVolume("192.168.1.10", "node-1"))
        assertEquals(10, AudioCaptureService.getReceiverVolume("192.168.1.20", "node-2"))

        // Raise master by 20% (delta = +20, newMaster = 70%)
        AudioCaptureService.applyMasterVolumeDelta(20)
        assertEquals(70, AudioCaptureService.remoteVolumePercent.get())
        // Both receivers moved up by 20%
        assertEquals(50, AudioCaptureService.getReceiverVolume("192.168.1.10", "node-1"))
        assertEquals(30, AudioCaptureService.getReceiverVolume("192.168.1.20", "node-2"))

        // Raise master by 10% (delta = +10, newMaster = 80%)
        AudioCaptureService.applyMasterVolumeDelta(10)
        assertEquals(80, AudioCaptureService.remoteVolumePercent.get())
        // Both receivers returned to original volumes
        assertEquals(60, AudioCaptureService.getReceiverVolume("192.168.1.10", "node-1"))
        assertEquals(40, AudioCaptureService.getReceiverVolume("192.168.1.20", "node-2"))
    }

    @Test
    fun testBoundaryClampingWhenMasterChanges() {
        AudioCaptureService.remoteVolumePercent.set(30)
        AudioCaptureService.setReceiverVolume("192.168.1.10", "node-1", 10)
        AudioCaptureService.setReceiverVolume("192.168.1.20", "node-2", 25)

        // Drop master by 15% (newMaster = 15%)
        AudioCaptureService.applyMasterVolumeDelta(-15)
        assertEquals(15, AudioCaptureService.remoteVolumePercent.get())
        // Node 1 clamped to 0 (10 - 15 = -5 -> 0)
        assertEquals(0, AudioCaptureService.getReceiverVolume("192.168.1.10", "node-1"))
        // Node 2 is 10 (25 - 15 = 10)
        assertEquals(10, AudioCaptureService.getReceiverVolume("192.168.1.20", "node-2"))

        // Raise master by 15% (newMaster = 30%)
        AudioCaptureService.applyMasterVolumeDelta(15)
        assertEquals(30, AudioCaptureService.remoteVolumePercent.get())
        assertEquals(15, AudioCaptureService.getReceiverVolume("192.168.1.10", "node-1"))
        assertEquals(25, AudioCaptureService.getReceiverVolume("192.168.1.20", "node-2"))
    }

    @Test
    fun testUnsetReceiverVolumeDefaultsToMasterVolume() {
        AudioCaptureService.remoteVolumePercent.set(65)
        assertEquals(65, AudioCaptureService.getReceiverVolume("192.168.1.99", null))
    }

    @Test
    fun testDeviceSpecificVolumeAllowsIndependentSetting() {
        AudioCaptureService.remoteVolumePercent.set(70)
        AudioCaptureService.setReceiverVolume("192.168.1.50", "node-1", 90)
        assertEquals(90, AudioCaptureService.getReceiverVolume("192.168.1.50", "node-1"))

        AudioCaptureService.setReceiverVolume("192.168.1.50", "node-1", 40)
        assertEquals(40, AudioCaptureService.getReceiverVolume("192.168.1.50", "node-1"))
    }
}
