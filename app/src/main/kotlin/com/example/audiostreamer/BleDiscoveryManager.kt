package com.example.audiostreamer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.audiostreamer.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class BleDiscoveredDevice(
    val name: String,
    val bluetoothAddress: String,
    val role: String = "receiver",
    val p2pSsid: String? = null,
    val p2pPassphrase: String? = null,
    val p2pGoIp: String? = null,
    val lastSeenMs: Long = SystemClock.elapsedRealtime()
)

object BleDiscoveryManager {
    private const val TAG = "BleDiscoveryManager"

    // 128-bit custom service UUID for High-definition Audio Transport discovery
    val HAT_SERVICE_UUID: UUID = UUID.fromString("00001850-0000-1000-8000-00805f9b34fb")
    val HAT_PARCEL_UUID = ParcelUuid(HAT_SERVICE_UUID)

    private val _bleDevices = MutableStateFlow<List<BleDiscoveredDevice>>(emptyList())
    val bleDevices: StateFlow<List<BleDiscoveredDevice>> = _bleDevices.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var pruneJob: Job? = null

    fun hasPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val adv = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val conn = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            scan && adv && conn
        } else {
            val bt = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val loc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            bt && loc
        }
    }

    private fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    // -------------------------------------------------------------------------
    // BLE Advertising (e.g. Receiver advertises P2P autonomous group credentials)
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startAdvertising(
        context: Context,
        role: String = "receiver",
        p2pSsid: String? = null,
        p2pPassphrase: String? = null,
        p2pGoIp: String? = null
    ) {
        if (_isAdvertising.value) return
        if (!hasPermissions(context)) {
            Log.w(TAG, "Cannot start BLE advertising: missing Bluetooth permissions")
            return
        }

        val adapter = getBluetoothAdapter(context) ?: return
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter is disabled, cannot start advertising")
            return
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "Device does not support BLE advertising")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()

        // Encode compact payload in service data
        // Format: JSON with {"r": role, "s": ssid, "p": pass, "g": goIp}
        val json = JSONObject().apply {
            put("r", if (role == "receiver") "rx" else "tx")
            if (!p2pSsid.isNullOrEmpty()) put("s", p2pSsid)
            if (!p2pPassphrase.isNullOrEmpty()) put("p", p2pPassphrase)
            if (!p2pGoIp.isNullOrEmpty()) put("g", p2pGoIp)
        }
        val serviceDataBytes = json.toString().toByteArray(Charsets.UTF_8).take(24).toByteArray()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(HAT_PARCEL_UUID)
            .addServiceData(HAT_PARCEL_UUID, serviceDataBytes)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE Advertising started successfully (role: $role, p2p: ${p2pSsid != null})")
                _isAdvertising.value = true
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE Advertising failed with error code: $errorCode")
                _isAdvertising.value = false
            }
        }

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE advertisement: ${e.message}")
            _isAdvertising.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!_isAdvertising.value && advertiseCallback == null) return
        try {
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        } catch (ignored: Exception) {}
        advertiser = null
        advertiseCallback = null
        _isAdvertising.value = false
        Log.i(TAG, "BLE Advertising stopped")
    }

    // -------------------------------------------------------------------------
    // BLE Scanning (e.g. Transmitter scans for offline Receivers)
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScanning(context: Context) {
        if (_isScanning.value) return
        if (!hasPermissions(context)) {
            Log.w(TAG, "Cannot start BLE scan: missing Bluetooth permissions")
            return
        }

        val adapter = getBluetoothAdapter(context) ?: return
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter is disabled, cannot start scanning")
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner not available")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(HAT_PARCEL_UUID)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { handleScanResult(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE Scan failed with error code: $errorCode")
                _isScanning.value = false
            }
        }

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            _isScanning.value = true
            pruneJob?.cancel()
            pruneJob = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    delay(5000L)
                    pruneStaleDevices()
                }
            }
            Log.i(TAG, "BLE Scanning started for HAT devices")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scan: ${e.message}")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!_isScanning.value && scanCallback == null) return
        pruneJob?.cancel()
        pruneJob = null
        try {
            scanCallback?.let { scanner?.stopScan(it) }
        } catch (ignored: Exception) {}
        scanner = null
        scanCallback = null
        _isScanning.value = false
        Log.i(TAG, "BLE Scanning stopped")
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val device = result.device ?: return
        val serviceData = record.getServiceData(HAT_PARCEL_UUID) ?: ByteArray(0)

        var role = "receiver"
        var p2pSsid: String? = null
        var p2pPassphrase: String? = null
        var p2pGoIp: String? = null

        if (serviceData.isNotEmpty()) {
            try {
                val str = String(serviceData, Charsets.UTF_8).trim()
                if (str.startsWith("{") && str.endsWith("}")) {
                    val json = JSONObject(str)
                    val r = json.optString("r", "rx")
                    role = if (r == "tx") "transmitter" else "receiver"
                    p2pSsid = json.optString("s").takeIf { it.isNotEmpty() }
                    p2pPassphrase = json.optString("p").takeIf { it.isNotEmpty() }
                    p2pGoIp = json.optString("g").takeIf { it.isNotEmpty() }
                }
            } catch (ignored: Exception) {}
        }

        val rawName = try { record.deviceName ?: device.name } catch (e: Exception) { null }
        val effectiveName = if (!rawName.isNullOrBlank()) rawName else "Nearby $role"

        val discovered = BleDiscoveredDevice(
            name = effectiveName,
            bluetoothAddress = device.address,
            role = role,
            p2pSsid = p2pSsid,
            p2pPassphrase = p2pPassphrase,
            p2pGoIp = p2pGoIp ?: "192.168.49.1",
            lastSeenMs = SystemClock.elapsedRealtime()
        )

        val current = _bleDevices.value.toMutableList()
        val idx = current.indexOfFirst { it.bluetoothAddress == discovered.bluetoothAddress }
        if (idx >= 0) {
            current[idx] = discovered
        } else {
            current.add(discovered)
            Log.i(TAG, "Discovered new BLE peer: ${discovered.name} (${discovered.bluetoothAddress})")
        }
        _bleDevices.value = current
    }

    fun pruneStaleDevices() {
        val now = SystemClock.elapsedRealtime()
        val filtered = _bleDevices.value.filter { now - it.lastSeenMs < 15000 }
        if (filtered.size != _bleDevices.value.size) {
            _bleDevices.value = filtered
        }
    }
}
