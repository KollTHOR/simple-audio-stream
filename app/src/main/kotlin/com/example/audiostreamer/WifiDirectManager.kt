package com.example.audiostreamer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import android.os.Build
import com.example.audiostreamer.AppLogger as Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WifiDirectManager {
    private const val TAG = "WifiDirect"
    const val DEFAULT_GO_IP = "192.168.49.1"
    const val P2P_DEFAULT_SSID = "DIRECT-SA-AudioReceiver"
    const val P2P_DEFAULT_PASSPHRASE = "SimpleAudio123"

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    private val _isP2pSupported = MutableStateFlow(true)
    val isP2pSupported: StateFlow<Boolean> = _isP2pSupported.asStateFlow()

    private val _isP2pEnabled = MutableStateFlow(false)
    val isP2pEnabled: StateFlow<Boolean> = _isP2pEnabled.asStateFlow()

    private val _isGroupCreated = MutableStateFlow(false)
    val isGroupCreated: StateFlow<Boolean> = _isGroupCreated.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _groupOwnerIp = MutableStateFlow<String?>(null)
    val groupOwnerIp: StateFlow<String?> = _groupOwnerIp.asStateFlow()

    private val _networkSsid = MutableStateFlow<String?>(null)
    val networkSsid: StateFlow<String?> = _networkSsid.asStateFlow()

    private val _networkPassphrase = MutableStateFlow<String?>(null)
    val networkPassphrase: StateFlow<String?> = _networkPassphrase.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredPeers: StateFlow<List<WifiP2pDevice>> = _discoveredPeers.asStateFlow()

    private val _isScanningPeers = MutableStateFlow(false)
    val isScanningPeers: StateFlow<Boolean> = _isScanningPeers.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    var thisDeviceAddress: String? = null
    var thisDeviceName: String? = null
    var onConnectedCallback: ((goIp: String) -> Unit)? = null

    fun hasPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            hasNearby && hasFine
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun init(context: Context) {
        if (wifiP2pManager != null) return
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            Log.w(TAG, "Wi-Fi Direct (P2P) hardware not supported on this device")
            _isP2pSupported.value = false
            _statusMessage.value = "Wi-Fi Direct not supported"
            return
        }
        wifiP2pManager = mgr
        channel = mgr.initialize(context.applicationContext, context.mainLooper) {
            Log.w(TAG, "Wi-Fi Direct channel disconnected, reinitializing...")
            channel = wifiP2pManager?.initialize(context.applicationContext, context.mainLooper, null)
        }
        Log.i(TAG, "Wi-Fi Direct initialized successfully")
        registerReceiver(context)
    }

    private fun registerReceiver(context: Context) {
        if (isReceiverRegistered) return
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        _isP2pEnabled.value = enabled
                        _statusMessage.value = if (enabled) "Wi-Fi Direct Ready" else "Wi-Fi Direct Disabled"
                        Log.i(TAG, "WIFI_P2P_STATE_CHANGED: enabled=$enabled")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (!hasPermissions(ctx)) {
                            Log.w(TAG, "PEERS_CHANGED broadcast received but permissions not granted")
                            return
                        }
                        wifiP2pManager?.requestPeers(channel) { peerList ->
                            val peers = peerList.deviceList.toList()
                            _discoveredPeers.value = peers
                            _isScanningPeers.value = false
                            Log.i(TAG, "Discovered ${peers.size} Wi-Fi Direct peer(s)")
                            for (p in peers) {
                                Log.i(TAG, " -> Peer: '${p.deviceName}' (${p.deviceAddress}), status=${p.status}")
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        val connected = networkInfo?.isConnected == true
                        _isConnected.value = connected
                        Log.i(TAG, "WIFI_P2P_CONNECTION_CHANGED: connected=$connected")

                        if (connected) {
                            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                                if (info != null) {
                                    val goIp = info.groupOwnerAddress?.hostAddress ?: DEFAULT_GO_IP
                                    _groupOwnerIp.value = goIp
                                    _statusMessage.value = "Connected via Wi-Fi Direct ($goIp)"
                                    Log.i(TAG, "P2P Link established! GO IP: $goIp, isGroupOwner=${info.isGroupOwner}")
                                    onConnectedCallback?.invoke(goIp)
                                }
                            }
                            wifiP2pManager?.requestGroupInfo(channel) { group ->
                                if (group != null) {
                                    _networkSsid.value = group.networkName
                                    _networkPassphrase.value = group.passphrase
                                    Log.i(TAG, "P2P Group Info: SSID=${group.networkName}, Passphrase=${group.passphrase}, Clients=${group.clientList.size}")
                                    for (c in group.clientList) {
                                        Log.i(TAG, " -> P2P Client: '${c.deviceName}' (${c.deviceAddress})")
                                    }
                                }
                            }
                        } else {
                            if (!_isGroupCreated.value) {
                                _groupOwnerIp.value = null
                                _statusMessage.value = "Disconnected"
                                Log.i(TAG, "P2P Disconnected")
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val dev = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        if (dev != null) {
                            thisDeviceAddress = dev.deviceAddress
                            thisDeviceName = dev.deviceName
                            Log.i(TAG, "Local P2P Device: '${dev.deviceName}' (${dev.deviceAddress}), status=${dev.status}")
                        }
                    }
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(
                    receiver!!,
                    intentFilter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                context.applicationContext.registerReceiver(receiver!!, intentFilter)
            }
            isReceiverRegistered = true
            Log.i(TAG, "Wi-Fi Direct broadcast receiver registered (RECEIVER_EXPORTED)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering Wi-Fi Direct receiver: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun createAutonomousGroup(context: Context, onResult: (success: Boolean, ssid: String?, goIp: String?) -> Unit) {
        init(context)
        Log.i(TAG, "createAutonomousGroup requested")
        if (!hasPermissions(context)) {
            _statusMessage.value = "Permissions required for Wi-Fi Direct"
            Log.w(TAG, "createAutonomousGroup aborted: missing permissions")
            onResult(false, null, null)
            return
        }

        val mgr = wifiP2pManager ?: run {
            Log.w(TAG, "createAutonomousGroup aborted: wifiP2pManager is null")
            onResult(false, null, null)
            return
        }
        val ch = channel ?: run {
            Log.w(TAG, "createAutonomousGroup aborted: channel is null")
            onResult(false, null, null)
            return
        }

        // 1. Check if group already exists
        mgr.requestGroupInfo(ch) { existingGroup ->
            if (existingGroup != null && existingGroup.isGroupOwner) {
                _isGroupCreated.value = true
                _groupOwnerIp.value = DEFAULT_GO_IP
                _networkSsid.value = existingGroup.networkName
                _networkPassphrase.value = existingGroup.passphrase ?: P2P_DEFAULT_PASSPHRASE
                _statusMessage.value = "Group Active: ${existingGroup.networkName} (IP: $DEFAULT_GO_IP)"
                Log.i(TAG, "Reusing existing autonomous group: SSID=${existingGroup.networkName}, Pass=${existingGroup.passphrase}")
                mgr.discoverPeers(ch, null) // ensure discoverable
                onResult(true, existingGroup.networkName, DEFAULT_GO_IP)
            } else {
                // 2. Create new autonomous group
                createGroupWithConfig(mgr, ch, onResult)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createGroupWithConfig(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onResult: (success: Boolean, ssid: String?, goIp: String?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(P2P_DEFAULT_SSID)
                    .setPassphrase(P2P_DEFAULT_PASSPHRASE)
                    .build()
                Log.i(TAG, "Creating autonomous group with credentials: SSID=$P2P_DEFAULT_SSID...")
                mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        _isGroupCreated.value = true
                        _groupOwnerIp.value = DEFAULT_GO_IP
                        _networkSsid.value = P2P_DEFAULT_SSID
                        _networkPassphrase.value = P2P_DEFAULT_PASSPHRASE
                        _statusMessage.value = "Group Active: $P2P_DEFAULT_SSID (IP: $DEFAULT_GO_IP)"
                        Log.i(TAG, "Autonomous group created successfully with SSID: $P2P_DEFAULT_SSID, Pass: $P2P_DEFAULT_PASSPHRASE")
                        mgr.discoverPeers(ch, null)
                        onResult(true, P2P_DEFAULT_SSID, DEFAULT_GO_IP)
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "createGroup with custom config failed (code $reason). Trying standard createGroup...")
                        createStandardGroup(mgr, ch, onResult)
                    }
                })
                return
            } catch (e: Exception) {
                Log.w(TAG, "createGroup with config threw: ${e.message}. Falling back to standard createGroup...")
            }
        }
        createStandardGroup(mgr, ch, onResult)
    }

    @SuppressLint("MissingPermission")
    private fun createStandardGroup(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onResult: (success: Boolean, ssid: String?, goIp: String?) -> Unit
    ) {
        Log.i(TAG, "Creating standard autonomous Wi-Fi Direct group...")
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isGroupCreated.value = true
                _groupOwnerIp.value = DEFAULT_GO_IP
                mgr.requestGroupInfo(ch) { group ->
                    val ssid = group?.networkName ?: P2P_DEFAULT_SSID
                    val passphrase = group?.passphrase ?: P2P_DEFAULT_PASSPHRASE
                    _networkSsid.value = ssid
                    _networkPassphrase.value = passphrase
                    _statusMessage.value = "Group Active: $ssid (IP: $DEFAULT_GO_IP)"
                    Log.i(TAG, "Autonomous group created. SSID: $ssid, Passphrase: $passphrase, IP: $DEFAULT_GO_IP")
                    mgr.discoverPeers(ch, null)
                    onResult(true, ssid, DEFAULT_GO_IP)
                }
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY) {
                    Log.w(TAG, "createGroup returned BUSY (2). Removing existing group before retry...")
                    mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            retryCreateStandardGroup(mgr, ch, onResult)
                        }
                        override fun onFailure(r: Int) {
                            retryCreateStandardGroup(mgr, ch, onResult)
                        }
                    })
                } else {
                    _isGroupCreated.value = false
                    val reasonStr = parseReason(reason)
                    _statusMessage.value = "Failed creating group ($reasonStr)"
                    Log.w(TAG, "Failed creating autonomous group: $reasonStr")
                    onResult(false, null, null)
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun retryCreateStandardGroup(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onResult: (success: Boolean, ssid: String?, goIp: String?) -> Unit
    ) {
        Log.i(TAG, "Retrying createGroup after clean removal...")
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isGroupCreated.value = true
                _groupOwnerIp.value = DEFAULT_GO_IP
                mgr.requestGroupInfo(ch) { group ->
                    val ssid = group?.networkName ?: P2P_DEFAULT_SSID
                    val passphrase = group?.passphrase ?: P2P_DEFAULT_PASSPHRASE
                    _networkSsid.value = ssid
                    _networkPassphrase.value = passphrase
                    _statusMessage.value = "Group Active: $ssid (IP: $DEFAULT_GO_IP)"
                    Log.i(TAG, "Autonomous group created on retry. SSID: $ssid, Passphrase: $passphrase")
                    mgr.discoverPeers(ch, null)
                    onResult(true, ssid, DEFAULT_GO_IP)
                }
            }

            override fun onFailure(reason: Int) {
                _isGroupCreated.value = false
                val reasonStr = parseReason(reason)
                _statusMessage.value = "Failed creating group ($reasonStr)"
                Log.w(TAG, "Retry create group failed: $reasonStr")
                onResult(false, null, null)
            }
        })
    }

    fun removeGroup(context: Context) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        Log.i(TAG, "Removing autonomous Wi-Fi Direct group...")
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isGroupCreated.value = false
                _networkSsid.value = null
                _networkPassphrase.value = null
                _groupOwnerIp.value = null
                _statusMessage.value = "Group removed"
                Log.i(TAG, "Wi-Fi Direct group removed successfully")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed removing group: ${parseReason(reason)}")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers(context: Context) {
        init(context)
        Log.i(TAG, "discoverPeers requested")
        if (!hasPermissions(context)) {
            _statusMessage.value = "Permissions required for Wi-Fi Direct"
            Log.w(TAG, "discoverPeers aborted: missing permissions")
            return
        }
        val mgr = wifiP2pManager ?: run {
            Log.w(TAG, "discoverPeers aborted: wifiP2pManager is null")
            return
        }
        val ch = channel ?: run {
            Log.w(TAG, "discoverPeers aborted: channel is null")
            return
        }

        _isScanningPeers.value = true
        _statusMessage.value = "Scanning Wi-Fi Direct peers..."
        Log.i(TAG, "Initiating WifiP2pManager.discoverPeers()...")

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "WifiP2pManager.discoverPeers() succeeded. Scanning radio channels for peers...")
            }
            override fun onFailure(reason: Int) {
                _isScanningPeers.value = false
                val reasonStr = parseReason(reason)
                _statusMessage.value = "Peer scan failed ($reasonStr)"
                Log.w(TAG, "WifiP2pManager.discoverPeers() failed: $reasonStr. (Ensure Wi-Fi & Location are ON)")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectWithCredentials(
        context: Context,
        ssid: String,
        passphrase: String?,
        onConnected: (goIp: String) -> Unit
    ) {
        init(context)
        val pass = passphrase ?: P2P_DEFAULT_PASSPHRASE
        Log.i(TAG, "connectWithCredentials: SSID='$ssid', Pass='$pass'")
        if (!hasPermissions(context)) {
            _statusMessage.value = "Permissions required for Wi-Fi Direct"
            Log.w(TAG, "connectWithCredentials aborted: missing permissions")
            return
        }
        val mgr = wifiP2pManager ?: run {
            Log.w(TAG, "connectWithCredentials aborted: wifiP2pManager is null")
            return
        }
        val ch = channel ?: run {
            Log.w(TAG, "connectWithCredentials aborted: channel is null")
            return
        }

        onConnectedCallback = onConnected

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(pass)
                .build()

            _statusMessage.value = "Connecting to $ssid..."
            Log.i(TAG, "Calling WifiP2pManager.connect() with WPA2 passphrase config...")
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _statusMessage.value = "Connecting to $ssid..."
                    Log.i(TAG, "WifiP2pManager.connect() accepted! Waiting for connection event...")
                }

                override fun onFailure(reason: Int) {
                    val reasonStr = parseReason(reason)
                    _statusMessage.value = "Direct connect failed ($reasonStr)"
                    Log.w(TAG, "WifiP2pManager.connect() failed: $reasonStr for $ssid")
                }
            })
        } else {
            Log.i(TAG, "Pre-Android 10: falling back to peer discovery")
            discoverPeers(context)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(
        context: Context,
        device: WifiP2pDevice,
        onConnected: (goIp: String) -> Unit
    ) {
        init(context)
        Log.i(TAG, "connectToPeer: '${device.deviceName}' (${device.deviceAddress})")
        if (!hasPermissions(context)) {
            _statusMessage.value = "Permissions required for Wi-Fi Direct"
            Log.w(TAG, "connectToPeer aborted: missing permissions")
            return
        }
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        onConnectedCallback = onConnected

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // Prefer client mode so receiver stays Group Owner
        }

        _statusMessage.value = "Connecting to ${device.deviceName}..."
        Log.i(TAG, "Initiating WPS-PBC connect to ${device.deviceName}...")
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "WPS connection handshake initiated to ${device.deviceName}")
                _statusMessage.value = "Connecting to ${device.deviceName}..."
            }

            override fun onFailure(reason: Int) {
                val reasonStr = parseReason(reason)
                _statusMessage.value = "Connection failed ($reasonStr)"
                Log.w(TAG, "Connection failed to ${device.deviceName}: $reasonStr")
            }
        })
    }

    fun disconnect(context: Context) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        Log.i(TAG, "Disconnecting P2P link...")
        mgr.cancelConnect(ch, null)
        mgr.removeGroup(ch, null)
        _isConnected.value = false
        _groupOwnerIp.value = null
        _statusMessage.value = "Disconnected"
    }

    fun cleanup(context: Context) {
        if (isReceiverRegistered && receiver != null) {
            try {
                context.applicationContext.unregisterReceiver(receiver)
            } catch (ignored: Exception) {}
            isReceiverRegistered = false
            receiver = null
            Log.i(TAG, "Wi-Fi Direct cleaned up")
        }
    }

    private fun parseReason(reason: Int): String = when (reason) {
        WifiP2pManager.BUSY -> "BUSY (2)"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED (1)"
        WifiP2pManager.ERROR -> "ERROR (0)"
        else -> "Code $reason"
    }
}
