package com.example.audiostreamer.node.transport

import android.bluetooth.BluetoothAdapter
import com.example.audiostreamer.BleDiscoveryManager

/**
 * Bluetooth Low Energy (BLE) discovery and bootstrap transport backend.
 *
 * Requirements:
 * - BLE is NOT an audio transport.
 * - It is a discovery/bootstrap mechanism that advertises Wi-Fi credentials/endpoints.
 */
class BleTransport : HatTransport {
    override val type: HatTransportType = HatTransportType.BLE

    override val isAvailable: Boolean
        get() = try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (e: Exception) {
            false
        }

    override val state: TransportState
        get() = if (isAvailable) {
            if (BleDiscoveryManager.isAdvertising.value || BleDiscoveryManager.isScanning.value) {
                TransportState.LISTENING
            } else {
                TransportState.AVAILABLE
            }
        } else {
            TransportState.UNAVAILABLE
        }

    override val localAddress: TransportAddress? = null
    override val remoteAddress: TransportAddress? = null

    override fun connect(remote: TransportAddress): Result<Unit> {
        return Result.failure(
            UnsupportedOperationException(
                "BLE is a discovery/bootstrap mechanism, not an audio transport"
            )
        )
    }

    override fun listen(port: Int): Result<Unit> {
        return try {
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun close() {
        // Lifecycle managed by BleDiscoveryManager
    }

    override fun getDiagnostics(): Map<String, Any?> = linkedMapOf(
        "type" to type.name,
        "isAudioTransport" to type.isAudioTransport,
        "isBootstrapOnly" to type.isBootstrapOnly,
        "isAvailable" to isAvailable,
        "state" to state.name,
        "role" to "DISCOVERY_BOOTSTRAP",
        "isAdvertising" to BleDiscoveryManager.isAdvertising.value,
        "isScanning" to BleDiscoveryManager.isScanning.value,
        "discoveredDevicesCount" to BleDiscoveryManager.bleDevices.value.size
    )
}
