package com.example.audiostreamer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    val port: Int = AudioConfig.DEFAULT_PORT,
    val lastSeenMs: Long = SystemClock.elapsedRealtime()
)

object BleDiscoveryManager {
    private const val TAG = "BleDiscoveryManager"

    // 128-bit custom service UUID for High-definition Audio Transport discovery
    val HAT_SERVICE_UUID: UUID = UUID.fromString("00001850-0000-1000-8000-00805f9b34fb")
    val HAT_PARCEL_UUID = ParcelUuid(HAT_SERVICE_UUID)
    private val DIRECT_GROUP_CHARACTERISTIC_UUID: UUID = UUID.fromString("db2f8b94-b1f8-4c61-b32a-dfd0f27e2c14")
    private const val DIRECT_GROUP_ADVERTISEMENT_MARKER = "H:rx:gatt1"

    private val _bleDevices = MutableStateFlow<List<BleDiscoveredDevice>>(emptyList())
    val bleDevices: StateFlow<List<BleDiscoveredDevice>> = _bleDevices.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    @Volatile private var applicationContext: Context? = null
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var gattCredentialPayload: ByteArray? = null
    @Volatile private var pendingAdvertisement: PendingAdvertisement? = null
    private val advertisingGeneration = AtomicInteger(0)
    private val gattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val pendingGattClients = ConcurrentHashMap.newKeySet<String>()
    private val connectedClientMtus = ConcurrentHashMap<String, Int>()

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var pruneJob: Job? = null

    private data class PendingAdvertisement(
        val generation: Int,
        val settings: AdvertiseSettings,
        val advertiseData: AdvertiseData,
        val scanResponseData: AdvertiseData,
        val callback: AdvertiseCallback
    )

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedClientMtus.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            connectedClientMtus[device.address] = mtu
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid != DIRECT_GROUP_CHARACTERISTIC_UUID) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }

            val payload = gattCredentialPayload ?: ByteArray(0)
            if (offset > payload.size) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }

            val chunkSize = (connectedClientMtus[device.address] ?: 23) - 1
            val end = minOf(payload.size, offset + chunkSize)
            val response = payload.copyOfRange(offset, end)
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val pending = pendingAdvertisement ?: return
            if (pending.generation != advertisingGeneration.get()) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed to add BLE bootstrap GATT service (status=$status)")
                stopAdvertising()
                return
            }

            try {
                advertiser?.startAdvertising(
                    pending.settings,
                    pending.advertiseData,
                    pending.scanResponseData,
                    pending.callback
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting BLE bootstrap advertisement: ${e.message}", e)
                stopAdvertising()
            }
        }
    }

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
    // BLE GATT bootstrap. BLE exchanges Wi-Fi Direct setup details; audio uses Wi-Fi Direct.
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startAdvertising(
        context: Context,
        role: String = "receiver",
        p2pSsid: String? = null,
        p2pPassphrase: String? = null,
        p2pGoIp: String? = null,
        p2pPort: Int = AudioConfig.DEFAULT_PORT,
        deviceName: String = "Wi-Fi Direct Receiver"
    ) {
        if (role != "receiver" || p2pSsid.isNullOrBlank() || p2pPassphrase.isNullOrBlank() || p2pGoIp.isNullOrBlank()) {
            Log.w(TAG, "BLE bootstrap requires an active receiver Wi-Fi Direct group")
            return
        }
        val payload = BleDirectHandshakeCodec.encode(
            BleDirectHandshake(
                deviceName = deviceName,
                ssid = p2pSsid,
                passphrase = p2pPassphrase,
                groupOwnerIp = p2pGoIp,
                port = p2pPort
            )
        )
        if (payload.size > 512) {
            Log.e(TAG, "BLE Direct bootstrap payload exceeds 512 bytes (${payload.size})")
            return
        }
        if (gattCredentialPayload?.contentEquals(payload) == true && gattServer != null) return
        if (_isAdvertising.value || gattServer != null) stopAdvertising()
        if (!hasPermissions(context)) {
            Log.w(TAG, "Cannot start BLE bootstrap: missing Bluetooth permissions")
            return
        }

        val appContext = context.applicationContext
        applicationContext = appContext
        val adapter = getBluetoothAdapter(appContext) ?: return
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter is disabled")
            return
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "Device does not support BLE advertising")
            return
        }

        gattCredentialPayload = payload

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(HAT_PARCEL_UUID)
            .build()
        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(HAT_PARCEL_UUID, DIRECT_GROUP_ADVERTISEMENT_MARKER.toByteArray(Charsets.US_ASCII))
            .build()

        val generation = advertisingGeneration.incrementAndGet()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE Direct bootstrap advertising started for $deviceName")
                _isAdvertising.value = true
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE Direct bootstrap advertising failed with error code: $errorCode")
                stopAdvertising()
            }
        }
        advertiseCallback = callback
        pendingAdvertisement = PendingAdvertisement(generation, settings, advertiseData, scanResponseData, callback)

        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val server = manager?.openGattServer(appContext, gattServerCallback)
        if (server == null) {
            Log.e(TAG, "Unable to open BLE GATT server for Direct bootstrap")
            stopAdvertising()
            return
        }
        gattServer = server

        val service = BluetoothGattService(HAT_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                DIRECT_GROUP_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        if (!server.addService(service)) {
            Log.e(TAG, "Unable to register BLE Direct bootstrap GATT service")
            stopAdvertising()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertisingGeneration.incrementAndGet()
        pendingAdvertisement = null
        try {
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        } catch (ignored: Exception) {}
        advertiser = null
        advertiseCallback = null
        _isAdvertising.value = false
        gattCredentialPayload = null
        connectedClientMtus.clear()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        Log.i(TAG, "BLE Direct bootstrap advertising stopped")
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

        applicationContext = context.applicationContext

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

        _bleDevices.value = emptyList()

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
        closeGattClients()
        Log.i(TAG, "BLE Scanning stopped")
    }

    @SuppressLint("MissingPermission")
    private fun beginDirectHandshake(context: Context, device: BluetoothDevice) {
        if (!pendingGattClients.add(device.address)) return

        val callback = object : BluetoothGattCallback() {
            private var serviceDiscoveryStarted = false

            private fun discoverServices(gatt: BluetoothGatt) {
                if (serviceDiscoveryStarted) return
                serviceDiscoveryStarted = true
                if (!gatt.discoverServices()) finishGattClient(device.address, gatt)
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finishGattClient(device.address, gatt)
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.requestMtu(247)) discoverServices(gatt)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                discoverServices(gatt)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishGattClient(device.address, gatt)
                    return
                }

                val characteristic = gatt.getService(HAT_PARCEL_UUID.uuid)
                    ?.getCharacteristic(DIRECT_GROUP_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    finishGattClient(device.address, gatt)
                    return
                }

                @Suppress("DEPRECATION")
                val readStarted = gatt.readCharacteristic(characteristic)
                if (!readStarted) finishGattClient(device.address, gatt)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                handleHandshakeRead(device, gatt, characteristic.value ?: ByteArray(0), status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                handleHandshakeRead(device, gatt, value, status)
            }
        }

        try {
            val gatt = device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                pendingGattClients.remove(device.address)
            } else {
                gattClients[device.address] = gatt
            }
        } catch (e: Exception) {
            pendingGattClients.remove(device.address)
            Log.w(TAG, "Could not connect to BLE bootstrap peer ${device.address}: ${e.message}")
        }
    }

    private fun handleHandshakeRead(device: BluetoothDevice, gatt: BluetoothGatt, value: ByteArray, status: Int) {
        if (!_isScanning.value) {
            finishGattClient(device.address, gatt)
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val credentials = BleDirectHandshakeCodec.decode(value)
            if (credentials != null) {
                publishDiscoveredDevice(
                    BleDiscoveredDevice(
                        name = credentials.deviceName,
                        bluetoothAddress = device.address,
                        role = "receiver",
                        p2pSsid = credentials.ssid,
                        p2pPassphrase = credentials.passphrase,
                        p2pGoIp = credentials.groupOwnerIp,
                        port = credentials.port,
                        lastSeenMs = SystemClock.elapsedRealtime()
                    )
                )
            } else {
                Log.w(TAG, "BLE peer returned invalid or incomplete Direct-group credentials")
            }
        } else {
            Log.w(TAG, "BLE Direct bootstrap read failed (status=$status)")
        }
        finishGattClient(device.address, gatt)
    }

    private fun publishDiscoveredDevice(discovered: BleDiscoveredDevice) {
        val current = _bleDevices.value.toMutableList()
        val index = current.indexOfFirst { it.bluetoothAddress == discovered.bluetoothAddress }
        if (index >= 0) current[index] = discovered else current.add(discovered)
        _bleDevices.value = current
        Log.i(TAG, "BLE Direct bootstrap received for ${discovered.name} (${discovered.bluetoothAddress})")
    }

    @SuppressLint("MissingPermission")
    private fun finishGattClient(address: String, gatt: BluetoothGatt) {
        pendingGattClients.remove(address)
        gattClients.remove(address)
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun closeGattClients() {
        val clients = gattClients.values.toList()
        gattClients.clear()
        pendingGattClients.clear()
        clients.forEach { gatt ->
            try { gatt.disconnect() } catch (_: Exception) {}
            try { gatt.close() } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val device = result.device ?: return
        val serviceData = record.getServiceData(HAT_PARCEL_UUID) ?: ByteArray(0)

        val payloadText = String(serviceData, Charsets.UTF_8).trim()
        if (payloadText == DIRECT_GROUP_ADVERTISEMENT_MARKER) {
            applicationContext?.let { beginDirectHandshake(it, device) }
            return
        }

        var role = "receiver"
        var p2pSsid: String? = null
        var p2pPassphrase: String? = null
        var p2pGoIp: String? = null

        if (serviceData.isNotEmpty()) {
            try {
                val str = String(serviceData, Charsets.UTF_8).trim()
                if (str.startsWith("H:")) {
                    val parts = str.substring(2).split(":")
                    if (parts.isNotEmpty()) {
                        role = if (parts[0] == "tx") "transmitter" else "receiver"
                    }
                    if (parts.size > 1 && parts[1].isNotEmpty()) {
                        p2pSsid = parts[1]
                    }
                    if (parts.size > 2 && parts[2].isNotEmpty()) {
                        p2pPassphrase = parts[2]
                    }
                    if (parts.size > 3 && parts[3].isNotEmpty()) {
                        p2pGoIp = parts[3]
                    }
                } else if (str.startsWith("{") && str.endsWith("}")) {
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
