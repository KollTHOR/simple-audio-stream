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
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WifiDirectManager {
    private const val TAG = "WifiDirectManager"
    const val DEFAULT_GO_IP = "192.168.49.1"

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

    private val _discoveredPeers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredPeers: StateFlow<List<WifiP2pDevice>> = _discoveredPeers.asStateFlow()

    private val _statusMessage = MutableStateFlow("Wi-Fi Direct Idle")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    var onConnectedCallback: ((goIp: String) -> Unit)? = null

    fun hasPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun init(context: Context) {
        if (wifiP2pManager != null) return
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            Log.w(TAG, "Wi-Fi P2P not supported on this device")
            _isP2pSupported.value = false
            _statusMessage.value = "Wi-Fi Direct not supported"
            return
        }
        wifiP2pManager = mgr
        channel = mgr.initialize(context.applicationContext, context.mainLooper) {
            Log.w(TAG, "Wi-Fi P2P channel disconnected, reinitializing...")
            channel = wifiP2pManager?.initialize(context.applicationContext, context.mainLooper, null)
        }
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
                        Log.d(TAG, "WIFI_P2P_STATE_CHANGED: enabled=$enabled")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (!hasPermissions(ctx)) return
                        wifiP2pManager?.requestPeers(channel) { peerList ->
                            val peers = peerList.deviceList.toList()
                            _discoveredPeers.value = peers
                            Log.d(TAG, "Discovered ${peers.size} Wi-Fi Direct peer(s)")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        val connected = networkInfo?.isConnected == true
                        _isConnected.value = connected
                        if (connected) {
                            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                                val goIp = info.groupOwnerAddress?.hostAddress ?: DEFAULT_GO_IP
                                _groupOwnerIp.value = goIp
                                _statusMessage.value = "Connected via Wi-Fi Direct ($goIp)"
                                Log.i(TAG, "Wi-Fi Direct connected. Group Owner: $goIp, isGroupOwner=${info.isGroupOwner}")
                                onConnectedCallback?.invoke(goIp)
                            }
                        } else {
                            if (!_isGroupCreated.value) {
                                _groupOwnerIp.value = null
                                _statusMessage.value = "Disconnected"
                            }
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver!!,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    fun createAutonomousGroup(context: Context, onResult: (success: Boolean, ssid: String?, goIp: String?) -> Unit) {
        init(context)
        if (!hasPermissions(context)) {
            _statusMessage.value = "Permissions required for Wi-Fi Direct"
            onResult(false, null, null)
            return
        }

        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        // Clean any existing group first
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                createGroupInternal(mgr, ch, onResult)
            }
            override fun onFailure(reason: Int) {
                createGroupInternal(mgr, ch, onResult)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroupInternal(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onResult: (success: Boolean, ssid: String?, goIp: String?) -> Unit
    ) {
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isGroupCreated.value = true
                _groupOwnerIp.value = DEFAULT_GO_IP
                mgr.requestGroupInfo(ch) { group ->
                    val ssid = group?.networkName ?: "DIRECT-SimpleAudioStream"
                    _networkSsid.value = ssid
                    _statusMessage.value = "Group Active: $ssid (IP: $DEFAULT_GO_IP)"
                    Log.i(TAG, "Autonomous group created successfully. SSID: $ssid, IP: $DEFAULT_GO_IP")
                    onResult(true, ssid, DEFAULT_GO_IP)
                }
            }

            override fun onFailure(reason: Int) {
                _isGroupCreated.value = false
                _statusMessage.value = "Failed creating group (code: $reason)"
                Log.w(TAG, "Failed creating autonomous group: reason=$reason")
                onResult(false, null, null)
            }
        })
    }

    fun removeGroup(context: Context) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isGroupCreated.value = false
                _networkSsid.value = null
                _groupOwnerIp.value = null
                _statusMessage.value = "Group removed"
                Log.i(TAG, "Wi-Fi Direct group removed")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed removing group: reason=$reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers(context: Context) {
        init(context)
        if (!hasPermissions(context)) {
            _statusMessage.value = "Permissions required for Wi-Fi Direct"
            return
        }
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _statusMessage.value = "Scanning Wi-Fi Direct peers..."
                Log.d(TAG, "Wi-Fi Direct peer discovery started")
            }
            override fun onFailure(reason: Int) {
                _statusMessage.value = "Peer discovery failed (code: $reason)"
                Log.w(TAG, "Peer discovery failed: reason=$reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(
        context: Context,
        device: WifiP2pDevice,
        onConnected: (goIp: String) -> Unit
    ) {
        init(context)
        if (!hasPermissions(context)) {
            _statusMessage.value = "Permissions required for Wi-Fi Direct"
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
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Initiated connection to ${device.deviceName}")
                _statusMessage.value = "Connecting to ${device.deviceName}..."
            }

            override fun onFailure(reason: Int) {
                _statusMessage.value = "Connection failed (code: $reason)"
                Log.w(TAG, "Connection failed to ${device.deviceName}: reason=$reason")
            }
        })
    }

    fun disconnect(context: Context) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
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
        }
    }
}
