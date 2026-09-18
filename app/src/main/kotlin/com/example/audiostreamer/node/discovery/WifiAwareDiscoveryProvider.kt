package com.example.audiostreamer.node.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import com.example.audiostreamer.HatDiagnostics
import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────────────
// Public data models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * HAT Node discovered via Wi-Fi Aware service discovery.
 *
 * [peerHandle] is the Wi-Fi Aware radio-layer peer handle — it is NOT the HAT identity.
 * The stable [NodeInfo.id] is exchanged via application-layer message after discovery.
 */
data class WifiAwareDiscoveredNode(
    val nodeInfo: NodeInfo,
    /** Wi-Fi Aware radio peer handle — transport endpoint, NOT HAT identity. */
    val peerHandle: PeerHandle,
    val serviceName: String = WifiAwareDiscoveryProvider.SERVICE_NAME,
    val discoveryLatencyMs: Long = 0L,       // attach-start → onServiceDiscovered
    val messageExchangeLatencyMs: Long = 0L, // service-found → Node ID received
    val networkSetupLatencyMs: Long = 0L,    // network request → onAvailable (or -1 if not attempted)
    val firstSeenEpochMs: Long = System.currentTimeMillis(),
    val lastSeenEpochMs: Long = System.currentTimeMillis()
)

/** Ordered lifecycle states for Wi-Fi Aware operations. */
enum class WifiAwareDiscoveryState {
    /** Wi-Fi Aware feature is not available on this device. */
    UNSUPPORTED,
    /** Feature present, provider not started. */
    IDLE,
    /** Attaching to the Wi-Fi Aware framework. */
    ATTACHING,
    /** Attached. Waiting to publish or subscribe. */
    ATTACHED,
    /** Publishing HAT service (Device A). */
    PUBLISHING,
    /** Subscribing to HAT service (Device B). */
    SUBSCRIBING,
    /** Actively discovering peers. */
    DISCOVERING,
    /** Stopped/torn down cleanly. */
    STOPPED,
    /** Unrecoverable error. */
    ERROR
}

/** Detailed record of a single Wi-Fi Aware operation phase for timing analysis. */
data class WifiAwareOperationTiming(
    val phase: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long,
    val success: Boolean,
    val failureReason: String? = null
) {
    override fun toString(): String =
        "[$phase] ${if (success) "OK" else "FAIL"} ${durationMs}ms" +
                (if (!failureReason.isNullOrBlank()) " reason=$failureReason" else "")
}

// ─────────────────────────────────────────────────────────────────────────────
// Provider
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Wi-Fi Aware Capability / Prototype Discovery Provider for HAT.
 *
 * **Purpose:** Determine whether Wi-Fi Aware is usable on real devices and measure every phase:
 * - Feature availability check
 * - [WifiAwareManager] attach
 * - Publish (Device A role) / Subscribe (Device B role)
 * - Service + peer discovery
 * - Application-layer Node ID exchange via `sendMessage` / `onMessageReceived`
 * - Network / data-path establishment via [WifiAwareNetworkSpecifier]
 *
 * **Architecture rules:**
 * - [PeerHandle] is NOT the HAT Node identity. The stable `hat-node-<UUID>` ID is
 *   exchanged in the application-layer message payload.
 * - This provider is **not** wired into the production HAT connection manager.
 *   It is a pure diagnostic/prototype module.
 * - Discovery does NOT automatically open an audio stream or a [com.example.audiostreamer.node.HatLink].
 * - Every failure is logged with the exact reason; nothing is swallowed silently.
 * - Audio code is untouched.
 *
 * Requires [android.Manifest.permission.ACCESS_WIFI_STATE],
 * [android.Manifest.permission.CHANGE_WIFI_STATE], and
 * [android.Manifest.permission.ACCESS_FINE_LOCATION] (Android < 13) or
 * [android.Manifest.permission.NEARBY_WIFI_DEVICES] (Android ≥ 13).
 */
@Suppress("DEPRECATION")
object WifiAwareDiscoveryProvider {

    private const val TAG = "WifiAwareDiscoveryProvider"

    /** Wi-Fi Aware service name used for HAT. */
    const val SERVICE_NAME = "hat-audio-transport"

    /** Application-layer message type prefix for Node ID exchange. */
    const val MSG_NODE_ID_PREFIX = "HATID:"

    // ─── State ───────────────────────────────────────────────────────────────

    private val lock = Any()

    private val _state = MutableStateFlow(WifiAwareDiscoveryState.IDLE)
    val state: StateFlow<WifiAwareDiscoveryState> = _state.asStateFlow()

    private val _isPublishing = MutableStateFlow(false)
    val isPublishing: StateFlow<Boolean> = _isPublishing.asStateFlow()

    private val _isSubscribing = MutableStateFlow(false)
    val isSubscribing: StateFlow<Boolean> = _isSubscribing.asStateFlow()

    private val _statusMessage = MutableStateFlow("Not started")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _discoveredNodes = MutableStateFlow<List<WifiAwareDiscoveredNode>>(emptyList())
    val discoveredNodes: StateFlow<List<WifiAwareDiscoveredNode>> = _discoveredNodes.asStateFlow()

    // ─── Feature capability flags (set once on probeCapability) ──────────────

    @Volatile private var featurePresent: Boolean? = null      // null = not checked
    @Volatile private var managerAvailable: Boolean? = null    // null = not checked
    @Volatile private var instantCommModeSupported: Boolean? = null

    // ─── Framework objects ────────────────────────────────────────────────────

    @Volatile private var awareManager: WifiAwareManager? = null
    @Volatile private var awareSession: WifiAwareSession? = null
    @Volatile private var publishSession: PublishDiscoverySession? = null
    @Volatile private var subscribeSession: SubscribeDiscoverySession? = null
    @Volatile private var applicationContext: Context? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // ─── Metrics (thread-safe atomics) ────────────────────────────────────────

    private val metricAttachAttempts = AtomicInteger(0)
    private val metricAttachSuccesses = AtomicInteger(0)
    private val metricAttachFailures = AtomicInteger(0)
    private val metricPublishAttempts = AtomicInteger(0)
    private val metricPublishSuccesses = AtomicInteger(0)
    private val metricPublishFailures = AtomicInteger(0)
    private val metricSubscribeAttempts = AtomicInteger(0)
    private val metricSubscribeSuccesses = AtomicInteger(0)
    private val metricSubscribeFailures = AtomicInteger(0)
    private val metricServiceDiscoveries = AtomicInteger(0)
    private val metricMessagesSent = AtomicInteger(0)
    private val metricMessagesReceived = AtomicInteger(0)
    private val metricMessageFailures = AtomicInteger(0)
    private val metricNetworkRequests = AtomicInteger(0)
    private val metricNetworkAvailable = AtomicInteger(0)
    private val metricNetworkFailures = AtomicInteger(0)

    // ─── Timing ───────────────────────────────────────────────────────────────

    private val attachStartMs = AtomicLong(0L)
    private val publishStartMs = AtomicLong(0L)
    private val subscribeStartMs = AtomicLong(0L)
    private val sessionStartMs = AtomicLong(0L)  // set when framework attach completes

    private val lastAttachDurationMs = AtomicLong(-1L)
    private val lastPublishDurationMs = AtomicLong(-1L)
    private val lastSubscribeDurationMs = AtomicLong(-1L)
    private val lastDiscoveryDurationMs = AtomicLong(-1L)
    private val lastMessageExchangeDurationMs = AtomicLong(-1L)
    private val lastNetworkSetupDurationMs = AtomicLong(-1L)

    private val recentOperations = ConcurrentLinkedDeque<WifiAwareOperationTiming>()

    // ─── Peer tracking ────────────────────────────────────────────────────────

    /** Keyed by PeerHandle.hashCode() — best approximation since PeerHandle is opaque. */
    private val activePeers = ConcurrentHashMap<Int, WifiAwareDiscoveredNode>()

    /**
     * Pending peer handles awaiting the Node ID application-layer message.
     * Key = PeerHandle.hashCode(), Value = discovery timestamp (ms).
     */
    private val pendingPeerDiscoveryMs = ConcurrentHashMap<Int, Long>()

    // ─── Availability broadcast receiver (Android O+) ─────────────────────────

    private val availabilityReceiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED) {
                val mgr = awareManager
                if (mgr == null) {
                    Log.w(TAG, "WIFI_AWARE_STATE_CHANGED but WifiAwareManager is null")
                    return
                }
                val available = mgr.isAvailable
                Log.i(TAG, "Wi-Fi Aware availability changed: isAvailable=$available")
                HatDiagnostics.info(
                    "WIFI_AWARE_AVAILABILITY_CHANGED",
                    mapOf("isAvailable" to available)
                )
                _statusMessage.value = "Wi-Fi Aware isAvailable=$available"
                if (!available && awareSession != null) {
                    Log.w(TAG, "Wi-Fi Aware became unavailable — tearing down session")
                    HatDiagnostics.warn(
                        "WIFI_AWARE_BECAME_UNAVAILABLE",
                        mapOf("reason" to "framework signalled unavailable")
                    )
                    teardownSession("Wi-Fi Aware became unavailable")
                }
            }
        }
    }
    @Volatile private var receiverRegistered = false

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Probes for Wi-Fi Aware capability without starting any session.
     * Safe to call unconditionally — returns early and sets state [WifiAwareDiscoveryState.UNSUPPORTED]
     * if the feature is absent, with the exact reason logged.
     */
    fun probeCapability(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val reason = "Wi-Fi Aware requires Android O (API 26); device is API ${Build.VERSION.SDK_INT}"
            Log.e(TAG, reason)
            HatDiagnostics.error(
                "WIFI_AWARE_UNSUPPORTED",
                mapOf("reason" to reason, "deviceApi" to Build.VERSION.SDK_INT)
            )
            featurePresent = false
            managerAvailable = false
            _state.value = WifiAwareDiscoveryState.UNSUPPORTED
            _statusMessage.value = reason
            return false
        }

        val appCtx = context.applicationContext
        applicationContext = appCtx

        val hasFeature = appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        featurePresent = hasFeature
        if (!hasFeature) {
            val reason = "FEATURE_WIFI_AWARE not present on this device"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_FEATURE_MISSING", mapOf("reason" to reason))
            _state.value = WifiAwareDiscoveryState.UNSUPPORTED
            _statusMessage.value = reason
            return false
        }
        Log.i(TAG, "FEATURE_WIFI_AWARE: present")
        HatDiagnostics.info("WIFI_AWARE_FEATURE_PRESENT")

        val mgr = appCtx.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        managerAvailable = mgr != null
        if (mgr == null) {
            val reason = "WifiAwareManager is null — getSystemService(WIFI_AWARE_SERVICE) returned null"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_MANAGER_NULL", mapOf("reason" to reason))
            _state.value = WifiAwareDiscoveryState.ERROR
            _statusMessage.value = reason
            return false
        }
        awareManager = mgr
        val isAvail = mgr.isAvailable
        Log.i(TAG, "WifiAwareManager available; isAvailable=$isAvail")
        HatDiagnostics.info(
            "WIFI_AWARE_MANAGER_OK",
            mapOf("isAvailable" to isAvail)
        )

        // Instant communication mode: API 34+
        instantCommModeSupported = if (Build.VERSION.SDK_INT >= 34) {
            val supported = appCtx.packageManager
                .hasSystemFeature("android.hardware.wifi.aware.instant_communication_mode")
            Log.i(TAG, "Instant Communication Mode supported: $supported")
            HatDiagnostics.info(
                "WIFI_AWARE_INSTANT_COMM_MODE",
                mapOf("supported" to supported)
            )
            supported
        } else {
            Log.i(TAG, "Instant Communication Mode: requires API 34 (device API ${Build.VERSION.SDK_INT})")
            HatDiagnostics.info(
                "WIFI_AWARE_INSTANT_COMM_MODE",
                mapOf("supported" to false, "reason" to "API 34+ required, device=${Build.VERSION.SDK_INT}")
            )
            false
        }

        // Register availability broadcast receiver
        if (!receiverRegistered) {
            val filter = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
            appCtx.registerReceiver(availabilityReceiver, filter)
            receiverRegistered = true
        }

        _state.value = WifiAwareDiscoveryState.IDLE
        _statusMessage.value = "Wi-Fi Aware supported and isAvailable=$isAvail"
        return true
    }

    /**
     * Attaches to the Wi-Fi Aware framework and starts publishing the HAT service
     * (Device A role: publisher).
     *
     * Must call [probeCapability] first.
     */
    fun startPublishing(context: Context) {
        if (!ensureFeatureAvailable("startPublishing")) return
        val appCtx = context.applicationContext
        applicationContext = appCtx
        ensureHandlerThread()
        attach(appCtx, afterAttach = { publish(appCtx) })
    }

    /**
     * Attaches to the Wi-Fi Aware framework and starts subscribing to the HAT service
     * (Device B role: subscriber).
     *
     * Must call [probeCapability] first.
     */
    fun startSubscribing(context: Context) {
        if (!ensureFeatureAvailable("startSubscribing")) return
        val appCtx = context.applicationContext
        applicationContext = appCtx
        ensureHandlerThread()
        attach(appCtx, afterAttach = { subscribe(appCtx) })
    }

    /** Tears down all sessions and stops the provider cleanly. */
    fun stopAll() {
        Log.i(TAG, "stopAll() called")
        HatDiagnostics.info("WIFI_AWARE_STOP_ALL")
        teardownSession("stopAll() called by application")
        cleanupHandlerThread()
        unregisterReceiver()
        _state.value = WifiAwareDiscoveryState.STOPPED
        _isPublishing.value = false
        _isSubscribing.value = false
        _statusMessage.value = "Stopped"
    }

    /** Clears the discovered node list (used in tests and UI reset). */
    fun clearDiscoveredNodes() {
        activePeers.clear()
        pendingPeerDiscoveryMs.clear()
        _discoveredNodes.value = emptyList()
    }

    /**
     * Returns a compact diagnostics snapshot suitable for [HatDiagnostics.registerSection].
     */
    fun getDiagnosticsSnapshot(): Map<String, Any?> = synchronized(lock) {
        val recentOps = recentOperations.toList().takeLast(10)
        linkedMapOf(
            "serviceName" to SERVICE_NAME,
            "state" to _state.value.name,
            "isPublishing" to _isPublishing.value,
            "isSubscribing" to _isSubscribing.value,
            "statusMessage" to _statusMessage.value,
            "featurePresent" to featurePresent,
            "managerAvailable" to managerAvailable,
            "instantCommModeSupported" to instantCommModeSupported,
            "discoveredNodesCount" to activePeers.size,
            "pendingPeersCount" to pendingPeerDiscoveryMs.size,
            "metrics" to linkedMapOf(
                "attachAttempts" to metricAttachAttempts.get(),
                "attachSuccesses" to metricAttachSuccesses.get(),
                "attachFailures" to metricAttachFailures.get(),
                "publishAttempts" to metricPublishAttempts.get(),
                "publishSuccesses" to metricPublishSuccesses.get(),
                "publishFailures" to metricPublishFailures.get(),
                "subscribeAttempts" to metricSubscribeAttempts.get(),
                "subscribeSuccesses" to metricSubscribeSuccesses.get(),
                "subscribeFailures" to metricSubscribeFailures.get(),
                "serviceDiscoveries" to metricServiceDiscoveries.get(),
                "messagesSent" to metricMessagesSent.get(),
                "messagesReceived" to metricMessagesReceived.get(),
                "messageFailures" to metricMessageFailures.get(),
                "networkRequests" to metricNetworkRequests.get(),
                "networkAvailable" to metricNetworkAvailable.get(),
                "networkFailures" to metricNetworkFailures.get()
            ),
            "timing" to linkedMapOf(
                "lastAttachDurationMs" to lastAttachDurationMs.get(),
                "lastPublishDurationMs" to lastPublishDurationMs.get(),
                "lastSubscribeDurationMs" to lastSubscribeDurationMs.get(),
                "lastDiscoveryDurationMs" to lastDiscoveryDurationMs.get(),
                "lastMessageExchangeDurationMs" to lastMessageExchangeDurationMs.get(),
                "lastNetworkSetupDurationMs" to lastNetworkSetupDurationMs.get()
            ),
            "recentOperations" to recentOps.map { it.toString() },
            "discoveredNodes" to activePeers.values.map { n ->
                "${n.nodeInfo.id} (${n.nodeInfo.name}) peer=${n.peerHandle} " +
                        "disc=${n.discoveryLatencyMs}ms msg=${n.messageExchangeLatencyMs}ms " +
                        "net=${n.networkSetupLatencyMs}ms"
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: attach
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private fun attach(context: Context, afterAttach: () -> Unit) {
        val mgr = awareManager
        if (mgr == null) {
            val reason = "Cannot attach: WifiAwareManager is null (call probeCapability first)"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_ATTACH_ERROR", mapOf("reason" to reason))
            _statusMessage.value = reason
            _state.value = WifiAwareDiscoveryState.ERROR
            return
        }

        if (awareSession != null) {
            Log.d(TAG, "Already attached — skipping re-attach")
            afterAttach()
            return
        }

        _state.value = WifiAwareDiscoveryState.ATTACHING
        _statusMessage.value = "Attaching to Wi-Fi Aware framework…"
        metricAttachAttempts.incrementAndGet()
        val t0 = System.currentTimeMillis()
        attachStartMs.set(t0)
        Log.i(TAG, "attach() started at $t0")
        HatDiagnostics.info("WIFI_AWARE_ATTACH_START", mapOf("timestampMs" to t0))

        mgr.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                val durationMs = System.currentTimeMillis() - attachStartMs.get()
                lastAttachDurationMs.set(durationMs)
                metricAttachSuccesses.incrementAndGet()
                awareSession = session
                sessionStartMs.set(System.currentTimeMillis())
                val msg = "Attached to Wi-Fi Aware in ${durationMs}ms"
                Log.i(TAG, msg)
                HatDiagnostics.info("WIFI_AWARE_ATTACHED", mapOf("durationMs" to durationMs))
                recordOp(WifiAwareOperationTiming("ATTACH", durationMs = durationMs, success = true))
                _state.value = WifiAwareDiscoveryState.ATTACHED
                _statusMessage.value = msg
                afterAttach()
            }

            override fun onAttachFailed() {
                val durationMs = System.currentTimeMillis() - attachStartMs.get()
                lastAttachDurationMs.set(durationMs)
                metricAttachFailures.incrementAndGet()
                val reason = "Wi-Fi Aware framework callback: onAttachFailed after ${durationMs}ms"
                Log.e(TAG, reason)
                HatDiagnostics.error("WIFI_AWARE_ATTACH_FAILED", mapOf("durationMs" to durationMs, "reason" to reason))
                recordOp(WifiAwareOperationTiming("ATTACH", durationMs = durationMs, success = false, failureReason = reason))
                _state.value = WifiAwareDiscoveryState.ERROR
                _statusMessage.value = reason
            }
        }, handler)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: publish
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private fun publish(context: Context) {
        val session = awareSession
        if (session == null) {
            val reason = "Cannot publish: WifiAwareSession is null"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_PUBLISH_ERROR", mapOf("reason" to reason))
            _statusMessage.value = reason
            _state.value = WifiAwareDiscoveryState.ERROR
            return
        }

        _state.value = WifiAwareDiscoveryState.PUBLISHING
        _isPublishing.value = true
        metricPublishAttempts.incrementAndGet()
        val t0 = System.currentTimeMillis()
        publishStartMs.set(t0)
        Log.i(TAG, "publish() started for service '$SERVICE_NAME'")
        HatDiagnostics.info("WIFI_AWARE_PUBLISH_START", mapOf("serviceName" to SERVICE_NAME))

        val localNodeId = localNodeId()

        val publishConfig = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        session.publish(publishConfig, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                val durationMs = System.currentTimeMillis() - publishStartMs.get()
                lastPublishDurationMs.set(durationMs)
                metricPublishSuccesses.incrementAndGet()
                publishSession = session
                val msg = "Publish started in ${durationMs}ms — HAT service '$SERVICE_NAME' now visible"
                Log.i(TAG, msg)
                HatDiagnostics.info("WIFI_AWARE_PUBLISH_STARTED", mapOf("durationMs" to durationMs, "serviceName" to SERVICE_NAME))
                recordOp(WifiAwareOperationTiming("PUBLISH", durationMs = durationMs, success = true))
                _state.value = WifiAwareDiscoveryState.DISCOVERING
                _statusMessage.value = msg
            }

            override fun onSessionConfigFailed() {
                val durationMs = System.currentTimeMillis() - publishStartMs.get()
                lastPublishDurationMs.set(durationMs)
                metricPublishFailures.incrementAndGet()
                _isPublishing.value = false
                val reason = "Publish session config failed after ${durationMs}ms"
                Log.e(TAG, reason)
                HatDiagnostics.error("WIFI_AWARE_PUBLISH_FAILED", mapOf("durationMs" to durationMs, "reason" to reason))
                recordOp(WifiAwareOperationTiming("PUBLISH", durationMs = durationMs, success = false, failureReason = reason))
                _state.value = WifiAwareDiscoveryState.ERROR
                _statusMessage.value = reason
            }

            override fun onSessionTerminated() {
                _isPublishing.value = false
                publishSession = null
                val msg = "Publish session terminated"
                Log.i(TAG, msg)
                HatDiagnostics.info("WIFI_AWARE_PUBLISH_TERMINATED")
                _statusMessage.value = msg
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingMessage(peerHandle, message, isPublisher = true, context = context)
            }

            override fun onMessageSendFailed(messageId: Int) {
                metricMessageFailures.incrementAndGet()
                Log.e(TAG, "Message send failed: messageId=$messageId")
                HatDiagnostics.error("WIFI_AWARE_MSG_SEND_FAILED", mapOf("messageId" to messageId))
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                metricMessagesSent.incrementAndGet()
                Log.d(TAG, "Message sent OK: messageId=$messageId")
            }
        }, handler)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: subscribe
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private fun subscribe(context: Context) {
        val session = awareSession
        if (session == null) {
            val reason = "Cannot subscribe: WifiAwareSession is null"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_SUBSCRIBE_ERROR", mapOf("reason" to reason))
            _statusMessage.value = reason
            _state.value = WifiAwareDiscoveryState.ERROR
            return
        }

        _state.value = WifiAwareDiscoveryState.SUBSCRIBING
        _isSubscribing.value = true
        metricSubscribeAttempts.incrementAndGet()
        val t0 = System.currentTimeMillis()
        subscribeStartMs.set(t0)
        Log.i(TAG, "subscribe() started for service '$SERVICE_NAME'")
        HatDiagnostics.info("WIFI_AWARE_SUBSCRIBE_START", mapOf("serviceName" to SERVICE_NAME))

        val localNodeId = localNodeId()

        val subscribeConfig = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        session.subscribe(subscribeConfig, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                val durationMs = System.currentTimeMillis() - subscribeStartMs.get()
                lastSubscribeDurationMs.set(durationMs)
                metricSubscribeSuccesses.incrementAndGet()
                subscribeSession = session
                val msg = "Subscribe started in ${durationMs}ms — scanning for '$SERVICE_NAME'"
                Log.i(TAG, msg)
                HatDiagnostics.info("WIFI_AWARE_SUBSCRIBE_STARTED", mapOf("durationMs" to durationMs, "serviceName" to SERVICE_NAME))
                recordOp(WifiAwareOperationTiming("SUBSCRIBE", durationMs = durationMs, success = true))
                _state.value = WifiAwareDiscoveryState.DISCOVERING
                _statusMessage.value = msg
            }

            override fun onSessionConfigFailed() {
                val durationMs = System.currentTimeMillis() - subscribeStartMs.get()
                lastSubscribeDurationMs.set(durationMs)
                metricSubscribeFailures.incrementAndGet()
                _isSubscribing.value = false
                val reason = "Subscribe session config failed after ${durationMs}ms"
                Log.e(TAG, reason)
                HatDiagnostics.error("WIFI_AWARE_SUBSCRIBE_FAILED", mapOf("durationMs" to durationMs, "reason" to reason))
                recordOp(WifiAwareOperationTiming("SUBSCRIBE", durationMs = durationMs, success = false, failureReason = reason))
                _state.value = WifiAwareDiscoveryState.ERROR
                _statusMessage.value = reason
            }

            override fun onSessionTerminated() {
                _isSubscribing.value = false
                subscribeSession = null
                val msg = "Subscribe session terminated"
                Log.i(TAG, msg)
                HatDiagnostics.info("WIFI_AWARE_SUBSCRIBE_TERMINATED")
                _statusMessage.value = msg
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?
            ) {
                val now = System.currentTimeMillis()
                val discLatency = now - sessionStartMs.get()
                metricServiceDiscoveries.incrementAndGet()
                lastDiscoveryDurationMs.set(discLatency)
                Log.i(TAG, "HAT service discovered: peer=$peerHandle discLatency=${discLatency}ms")
                HatDiagnostics.info(
                    "WIFI_AWARE_SERVICE_DISCOVERED",
                    mapOf("discLatencyMs" to discLatency, "peer" to peerHandle.toString())
                )
                recordOp(WifiAwareOperationTiming("SERVICE_DISCOVERED", durationMs = discLatency, success = true))

                val peerKey = peerHandle.hashCode()
                pendingPeerDiscoveryMs[peerKey] = now

                // Exchange Node ID at application layer — send our ID to the publisher
                val payload = buildNodeIdMessage(localNodeId)
                sendNodeIdMessage(peerHandle, session, payload)
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingMessage(peerHandle, message, isPublisher = false, context = context)
            }

            override fun onMessageSendFailed(messageId: Int) {
                metricMessageFailures.incrementAndGet()
                Log.e(TAG, "Message send failed: messageId=$messageId")
                HatDiagnostics.error("WIFI_AWARE_MSG_SEND_FAILED", mapOf("messageId" to messageId))
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                metricMessagesSent.incrementAndGet()
                Log.d(TAG, "Message sent OK: messageId=$messageId")
            }
        }, handler)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: message exchange
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildNodeIdMessage(nodeId: String): ByteArray =
        "$MSG_NODE_ID_PREFIX$nodeId".toByteArray(StandardCharsets.UTF_8)

    private fun parseNodeIdFromMessage(message: ByteArray): String? {
        val text = message.toString(StandardCharsets.UTF_8)
        if (!text.startsWith(MSG_NODE_ID_PREFIX)) return null
        val id = text.removePrefix(MSG_NODE_ID_PREFIX).trim()
        if (id.isBlank()) return null
        if (NodeIdentity.isIpOrMac(id)) {
            Log.e(TAG, "REJECTED incoming Node ID that looks like IP/MAC: '$id' — architecture violation")
            HatDiagnostics.error(
                "WIFI_AWARE_IDENTITY_REJECTED",
                mapOf("reason" to "incoming ID looks like IP/MAC", "id" to id)
            )
            return null
        }
        return id
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun sendNodeIdMessage(
        peerHandle: PeerHandle,
        discoverySession: Any,  // PublishDiscoverySession or SubscribeDiscoverySession
        payload: ByteArray
    ) {
        val msgId = System.currentTimeMillis().toInt()
        when (discoverySession) {
            is PublishDiscoverySession  -> discoverySession.sendMessage(peerHandle, msgId, payload)
            is SubscribeDiscoverySession -> discoverySession.sendMessage(peerHandle, msgId, payload)
            else -> Log.e(TAG, "Unknown discovery session type: ${discoverySession::class.java.simpleName}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleIncomingMessage(
        peerHandle: PeerHandle,
        message: ByteArray,
        isPublisher: Boolean,
        context: Context
    ) {
        val now = System.currentTimeMillis()
        metricMessagesReceived.incrementAndGet()

        val remoteNodeId = parseNodeIdFromMessage(message)
        if (remoteNodeId == null) {
            Log.e(TAG, "Received unrecognised message from peer=$peerHandle (${message.size} bytes) — ignoring")
            HatDiagnostics.error(
                "WIFI_AWARE_MSG_UNRECOGNISED",
                mapOf("peer" to peerHandle.toString(), "sizeBytes" to message.size)
            )
            return
        }

        val peerKey = peerHandle.hashCode()
        val discStartMs = pendingPeerDiscoveryMs[peerKey] ?: sessionStartMs.get()
        val discLatencyMs = discStartMs - sessionStartMs.get()
        val msgExchangeLatencyMs = now - discStartMs
        lastMessageExchangeDurationMs.set(msgExchangeLatencyMs)
        Log.i(TAG, "Node ID received: '$remoteNodeId' msgExchange=${msgExchangeLatencyMs}ms")
        HatDiagnostics.info(
            "WIFI_AWARE_NODE_ID_RECEIVED",
            mapOf(
                "remoteNodeId" to remoteNodeId,
                "msgExchangeLatencyMs" to msgExchangeLatencyMs,
                "discLatencyMs" to discLatencyMs,
                "peer" to peerHandle.toString()
            )
        )
        recordOp(WifiAwareOperationTiming("MESSAGE_EXCHANGE", durationMs = msgExchangeLatencyMs, success = true))
        _statusMessage.value = "Node ID exchanged with '$remoteNodeId' (${msgExchangeLatencyMs}ms)"

        val remoteIdentity = try {
            NodeIdentity(id = remoteNodeId, name = "HAT Aware Node")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Remote Node ID rejected by NodeIdentity: '$remoteNodeId' — ${e.message}")
            HatDiagnostics.error(
                "WIFI_AWARE_IDENTITY_INVALID",
                mapOf("remoteNodeId" to remoteNodeId, "reason" to (e.message ?: "unknown"))
            )
            return
        }

        val remoteNodeInfo = NodeInfo(
            identity = remoteIdentity,
            capabilities = NodeCapabilities(hasAudioInput = true, hasAudioOutput = true),
            deviceInfo = DevicePlatformInfo(),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.TRANSCEIVER
        )

        val discovered = WifiAwareDiscoveredNode(
            nodeInfo = remoteNodeInfo,
            peerHandle = peerHandle,
            discoveryLatencyMs = discLatencyMs,
            messageExchangeLatencyMs = msgExchangeLatencyMs,
            networkSetupLatencyMs = -1L  // updated if network path established below
        )

        activePeers[peerKey] = discovered
        pendingPeerDiscoveryMs.remove(peerKey)
        publishDiscoveredList()

        // Reply with our own Node ID so the remote peer can record us too
        val session = if (isPublisher) publishSession else subscribeSession
        if (session != null) {
            sendNodeIdMessage(peerHandle, session, buildNodeIdMessage(localNodeId()))
        }

        // Diagnostic-only network/data-path probe
        tryEstablishNetworkPath(context, peerHandle, isPublisher, peerKey, discovered)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: network / data-path (diagnostic probe)
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private fun tryEstablishNetworkPath(
        context: Context,
        peerHandle: PeerHandle,
        isPublisher: Boolean,
        peerKey: Int,
        discovered: WifiAwareDiscoveredNode
    ) {
        val session = if (isPublisher) publishSession else subscribeSession
        if (session == null) {
            Log.e(TAG, "Cannot request network path: discovery session is null")
            HatDiagnostics.error("WIFI_AWARE_NETWORK_ERROR", mapOf("reason" to "discovery session is null"))
            return
        }

        val specifier = try {
            WifiAwareNetworkSpecifier.Builder(
                when (session) {
                    is PublishDiscoverySession   -> session
                    is SubscribeDiscoverySession -> session
                    else -> {
                        Log.e(TAG, "Unknown session type for WifiAwareNetworkSpecifier")
                        return
                    }
                },
                peerHandle
            ).build()
        } catch (e: Exception) {
            val reason = "Exception building WifiAwareNetworkSpecifier: ${e.message}"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_SPECIFIER_ERROR", mapOf("reason" to reason))
            return
        }

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.e(TAG, "ConnectivityManager is null — cannot request network path")
            HatDiagnostics.error("WIFI_AWARE_NETWORK_ERROR", mapOf("reason" to "ConnectivityManager null"))
            return
        }

        metricNetworkRequests.incrementAndGet()
        val t0 = System.currentTimeMillis()
        Log.i(TAG, "Requesting Wi-Fi Aware network path for peer=$peerHandle")
        HatDiagnostics.info("WIFI_AWARE_NETWORK_REQUESTED", mapOf("peer" to peerHandle.toString()))

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val durationMs = System.currentTimeMillis() - t0
                lastNetworkSetupDurationMs.set(durationMs)
                metricNetworkAvailable.incrementAndGet()
                Log.i(TAG, "Wi-Fi Aware network AVAILABLE in ${durationMs}ms for peer=$peerHandle")
                HatDiagnostics.info(
                    "WIFI_AWARE_NETWORK_AVAILABLE",
                    mapOf("durationMs" to durationMs, "peer" to peerHandle.toString())
                )
                recordOp(WifiAwareOperationTiming("NETWORK_PATH", durationMs = durationMs, success = true))
                _statusMessage.value = "Wi-Fi Aware network path available (${durationMs}ms)"

                val updated = discovered.copy(networkSetupLatencyMs = durationMs)
                activePeers[peerKey] = updated
                publishDiscoveredList()

                // Release immediately — diagnostic probe only, not production audio
                cm.unregisterNetworkCallback(this)
                Log.i(TAG, "Network callback unregistered (diagnostic probe complete)")
            }

            override fun onUnavailable() {
                val durationMs = System.currentTimeMillis() - t0
                metricNetworkFailures.incrementAndGet()
                val reason = "Wi-Fi Aware network path UNAVAILABLE after ${durationMs}ms for peer=$peerHandle"
                Log.e(TAG, reason)
                HatDiagnostics.error(
                    "WIFI_AWARE_NETWORK_UNAVAILABLE",
                    mapOf("durationMs" to durationMs, "peer" to peerHandle.toString())
                )
                recordOp(WifiAwareOperationTiming("NETWORK_PATH", durationMs = durationMs, success = false, failureReason = reason))
                _statusMessage.value = reason
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Wi-Fi Aware network LOST for peer=$peerHandle")
                HatDiagnostics.info("WIFI_AWARE_NETWORK_LOST", mapOf("peer" to peerHandle.toString()))
            }
        }

        try {
            cm.requestNetwork(networkRequest, networkCallback)
        } catch (e: SecurityException) {
            val reason = "SecurityException requesting Wi-Fi Aware network: ${e.message}"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_NETWORK_SECURITY", mapOf("reason" to reason))
            recordOp(WifiAwareOperationTiming("NETWORK_PATH", durationMs = 0L, success = false, failureReason = reason))
            metricNetworkFailures.incrementAndGet()
        } catch (e: Exception) {
            val reason = "Exception requesting Wi-Fi Aware network: ${e.message}"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_NETWORK_ERROR", mapOf("reason" to reason))
            recordOp(WifiAwareOperationTiming("NETWORK_PATH", durationMs = 0L, success = false, failureReason = reason))
            metricNetworkFailures.incrementAndGet()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: teardown / cleanup
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private fun teardownSession(reason: String) {
        Log.i(TAG, "teardownSession: $reason")
        try { publishSession?.close() } catch (e: Exception) {
            Log.e(TAG, "Error closing publish session: ${e.message}")
            HatDiagnostics.error("WIFI_AWARE_TEARDOWN_ERROR", mapOf("stage" to "publishSession", "error" to (e.message ?: "")))
        }
        try { subscribeSession?.close() } catch (e: Exception) {
            Log.e(TAG, "Error closing subscribe session: ${e.message}")
            HatDiagnostics.error("WIFI_AWARE_TEARDOWN_ERROR", mapOf("stage" to "subscribeSession", "error" to (e.message ?: "")))
        }
        try { awareSession?.close() } catch (e: Exception) {
            Log.e(TAG, "Error closing WifiAwareSession: ${e.message}")
            HatDiagnostics.error("WIFI_AWARE_TEARDOWN_ERROR", mapOf("stage" to "awareSession", "error" to (e.message ?: "")))
        }
        publishSession = null
        subscribeSession = null
        awareSession = null
        _isPublishing.value = false
        _isSubscribing.value = false
    }

    private fun cleanupHandlerThread() {
        try { handlerThread?.quitSafely() } catch (e: Exception) {
            Log.e(TAG, "Error stopping HandlerThread: ${e.message}")
        }
        handlerThread = null
        handler = null
    }

    private fun unregisterReceiver() {
        if (receiverRegistered) {
            try {
                applicationContext?.unregisterReceiver(availabilityReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering availability receiver: ${e.message}")
            }
            receiverRegistered = false
        }
    }

    private fun ensureHandlerThread() {
        if (handler == null || handlerThread?.isAlive != true) {
            handlerThread = HandlerThread("WifiAwareDiscovery").also {
                it.start()
                handler = Handler(it.looper)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun ensureFeatureAvailable(callerName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val reason = "$callerName: Wi-Fi Aware requires Android O (API 26)"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_UNSUPPORTED", mapOf("caller" to callerName, "reason" to reason))
            return false
        }
        val fp = featurePresent
        if (fp == false) {
            val reason = "$callerName: FEATURE_WIFI_AWARE not present — cannot proceed"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_FEATURE_MISSING", mapOf("caller" to callerName))
            return false
        }
        if (fp == null) {
            val reason = "$callerName: probeCapability() has not been called"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_NOT_PROBED", mapOf("caller" to callerName))
            return false
        }
        if (awareManager == null) {
            val reason = "$callerName: WifiAwareManager is null"
            Log.e(TAG, reason)
            HatDiagnostics.error("WIFI_AWARE_MANAGER_NULL", mapOf("caller" to callerName))
            return false
        }
        return true
    }

    private fun localNodeId(): String =
        try { LocalNodeManager.getLocalNode().id } catch (e: Exception) { "hat-node-unknown" }

    private fun publishDiscoveredList() {
        _discoveredNodes.value = activePeers.values.toList()
    }

    private fun recordOp(op: WifiAwareOperationTiming) {
        while (recentOperations.size >= 50) recentOperations.pollFirst()
        recentOperations.addLast(op)
    }
}
