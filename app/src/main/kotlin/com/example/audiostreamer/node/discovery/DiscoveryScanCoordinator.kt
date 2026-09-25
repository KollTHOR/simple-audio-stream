package com.example.audiostreamer.node.discovery

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.audiostreamer.AppLogger as Log
import com.example.audiostreamer.BleDiscoveryManager
import com.example.audiostreamer.DiscoveryManager
import com.example.audiostreamer.HatDiagnostics
import com.example.audiostreamer.NetworkUtils
import com.example.audiostreamer.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sequential discovery scan phases.
 */
enum class DiscoveryScanPhase {
    IDLE,
    LOCAL_WIFI,
    WIFI_DIRECT,
    WIFI_AWARE,
    BLE,
    COMPLETE
}

/**
 * Status of an individual discovery phase.
 */
enum class PhaseStatus {
    WAITING,     // ○ Waiting
    SEARCHING,   // ● Searching
    FOUND,       // ✓ Found
    SKIPPED,     // — Skipped
    FAILED,      // ✕ Failed
    TIMED_OUT    // No devices found within timeout
}

/**
 * Detailed report of a single discovery phase.
 */
data class PhaseReport(
    val phase: DiscoveryScanPhase,
    val status: PhaseStatus = PhaseStatus.WAITING,
    val durationMs: Long = 0L,
    val discoveredCount: Int = 0,
    val reason: String? = null
)

/**
 * Live immutable snapshot of the unified discovery scan state.
 */
data class DiscoveryScanState(
    val currentPhase: DiscoveryScanPhase = DiscoveryScanPhase.IDLE,
    val phaseStatus: PhaseStatus = PhaseStatus.WAITING,
    val totalSeconds: Int = 0,
    val secondsRemaining: Int = 0,
    val phaseTimeRemainingMs: Long = 0L,
    val statusMessage: String = "Tap Scan to discover nearby devices",
    val phaseReports: Map<DiscoveryScanPhase, PhaseReport> = emptyMap(),
    val totalDiscoveredNodes: List<DiscoveredNodeEntry> = emptyList(),
    val isScanning: Boolean = false,
    val scanSummary: String? = null
)

/**
 * Dedicated coordinator and state machine orchestrating sequential HAT discovery.
 *
 * Sequence:
 * 1. LOCAL WI-FI (3s)
 * 2. WI-FI DIRECT (4s)
 * 3. WI-FI AWARE (4s)
 * 4. BLE (5s)
 *
 * Invariants:
 * - Starts only the current provider for that phase.
 * - If at least one valid HAT Node is found in a phase, finishes the scan early.
 * - If transport is unsupported/offline/permission denied, marks phase SKIPPED and immediately advances.
 * - Stops active providers cleanly upon phase transition or early termination.
 * - Emits structured diagnostics for all state machine transitions.
 */
object DiscoveryScanCoordinator {
    private const val TAG = "DiscoveryScanCoordinator"

    const val TIMEOUT_LAN_MS = 30_000L
    const val TIMEOUT_WIFI_DIRECT_MS = 30_000L
    const val TIMEOUT_WIFI_AWARE_MS = 30_000L
    const val TIMEOUT_BLE_MS = 30_000L

    // When at least one device is found during a phase, allow 5000ms for secondary nodes
    // on the same transport to finish DNS-SD TXT / beacon resolution
    const val SETTLE_WINDOW_MS = 5_000L
    const val MIN_SEARCH_MS = 5_000L

    private val lock = Any()
    private val isScanActive = AtomicBoolean(false)
    private var scanJob: Job? = null
    private var scanStartTimeMs: Long = 0L

    private val _scanState = MutableStateFlow(DiscoveryScanState())
    val scanState: StateFlow<DiscoveryScanState> = _scanState.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Public Control API
    // ─────────────────────────────────────────────────────────────────────────

    fun startScan(context: Context, scope: CoroutineScope) {
        synchronized(lock) {
            stopScanInternal(context, notifyComplete = false)
            isScanActive.set(true)
            scanStartTimeMs = System.currentTimeMillis()

            val initialReports = linkedMapOf(
                DiscoveryScanPhase.LOCAL_WIFI to PhaseReport(DiscoveryScanPhase.LOCAL_WIFI, PhaseStatus.WAITING),
                DiscoveryScanPhase.WIFI_DIRECT to PhaseReport(DiscoveryScanPhase.WIFI_DIRECT, PhaseStatus.WAITING),
                DiscoveryScanPhase.WIFI_AWARE to PhaseReport(DiscoveryScanPhase.WIFI_AWARE, PhaseStatus.WAITING),
                DiscoveryScanPhase.BLE to PhaseReport(DiscoveryScanPhase.BLE, PhaseStatus.WAITING)
            )

            _scanState.value = DiscoveryScanState(
                currentPhase = DiscoveryScanPhase.LOCAL_WIFI,
                phaseStatus = PhaseStatus.SEARCHING,
                totalSeconds = (TIMEOUT_LAN_MS / 1000L).toInt(),
                secondsRemaining = (TIMEOUT_LAN_MS / 1000L).toInt(),
                phaseTimeRemainingMs = TIMEOUT_LAN_MS,
                statusMessage = "Checking Local Wi-Fi...",
                phaseReports = initialReports,
                totalDiscoveredNodes = HatDiscoveryRegistry.discoveredNodes.value,
                isScanning = true,
                scanSummary = null
            )

            Log.i(TAG, "DISCOVERY_SCAN_START: Initiating sequential scan")
            HatDiagnostics.event(
                HatDiagnostics.Severity.INFO,
                "DISCOVERY_SCAN_START",
                mapOf("timestamp" to scanStartTimeMs, "mode" to "sequential")
            )

            scanJob = scope.launch(Dispatchers.Default) {
                try {
                    executeSequentialScan(context, scope)
                } catch (e: Exception) {
                    Log.e(TAG, "Scan job interrupted or failed", e)
                } finally {
                    finishScan(context)
                }
            }
        }
    }

    fun stopScan(context: Context) {
        synchronized(lock) {
            if (!isScanActive.get() && scanJob == null) return
            Log.i(TAG, "stopScan requested by caller")
            stopScanInternal(context, notifyComplete = true)
        }
    }

    private fun stopScanInternal(context: Context, notifyComplete: Boolean) {
        scanJob?.cancel()
        scanJob = null
        isScanActive.set(false)
        stopAllProviders(context)

        if (notifyComplete) {
            val finalNodes = HatDiscoveryRegistry.discoveredNodes.value
            val summary = generateCompactSummary(_scanState.value.phaseReports)
            val msg = if (finalNodes.isNotEmpty()) {
                "Found ${finalNodes.size} nearby device(s)"
            } else {
                "Scan stopped"
            }
            _scanState.value = _scanState.value.copy(
                currentPhase = DiscoveryScanPhase.COMPLETE,
                phaseStatus = PhaseStatus.WAITING,
                isScanning = false,
                statusMessage = msg,
                totalDiscoveredNodes = finalNodes,
                scanSummary = summary,
                secondsRemaining = 0,
                phaseTimeRemainingMs = 0L
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sequential Execution Loop
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun executeSequentialScan(context: Context, scope: CoroutineScope) {
        // ── Phase 1: Local Wi-Fi ─────────────────────────────────────────────
        val lanCompleted = runPhase(
            context = context,
            phase = DiscoveryScanPhase.LOCAL_WIFI,
            timeoutMs = TIMEOUT_LAN_MS,
            searchingMessage = "Checking Local Wi-Fi...",
            checkAvailability = { checkLanAvailability(context) },
            startProvider = {
                DiscoveryManager.triggerScan(scope)
                LanDiscoveryProvider.startDiscovery(context)
            },
            stopProvider = {
                // Keep LAN listener running cumulatively in background throughout scan;
                // full cleanup is executed by stopAllProviders() at scan completion.
            },
            countDiscovered = {
                HatDiscoveryRegistry.discoveredNodes.value.count { it.hasSource(DiscoverySource.LAN) }
            }
        )
        Log.i(TAG, "Local Wi-Fi phase complete (found=$lanCompleted); advancing to Wi-Fi Direct")

        // ── Phase 2: Wi-Fi Direct ────────────────────────────────────────────
        val directCompleted = runPhase(
            context = context,
            phase = DiscoveryScanPhase.WIFI_DIRECT,
            timeoutMs = TIMEOUT_WIFI_DIRECT_MS,
            searchingMessage = "Searching nearby Wi-Fi Direct devices...",
            checkAvailability = { checkWifiDirectAvailability(context) },
            startProvider = {
                WifiDirectManager.discoverPeers(context)
                WifiDirectDiscoveryProvider.startDiscovery(context)
            },
            stopProvider = {
                WifiDirectDiscoveryProvider.stopDiscovery()
                WifiDirectManager.stopPeerDiscovery(context)
            },
            countDiscovered = {
                HatDiscoveryRegistry.discoveredNodes.value.count { it.hasSource(DiscoverySource.WIFI_DIRECT) }
            }
        )
        Log.i(TAG, "Wi-Fi Direct phase complete (found=$directCompleted); advancing to Wi-Fi Aware")

        // ── Phase 3: Wi-Fi Aware ─────────────────────────────────────────────
        val awareCompleted = runPhase(
            context = context,
            phase = DiscoveryScanPhase.WIFI_AWARE,
            timeoutMs = TIMEOUT_WIFI_AWARE_MS,
            searchingMessage = "Searching nearby Wi-Fi Aware devices...",
            checkAvailability = { checkWifiAwareAvailability(context) },
            startProvider = {
                WifiAwareDiscoveryProvider.probeCapability(context)
                WifiAwareDiscoveryProvider.startSubscribing(context)
            },
            stopProvider = {
                WifiAwareDiscoveryProvider.stopSubscribing()
            },
            countDiscovered = {
                HatDiscoveryRegistry.discoveredNodes.value.count { it.hasSource(DiscoverySource.WIFI_AWARE) }
            }
        )
        Log.i(TAG, "Wi-Fi Aware phase complete (found=$awareCompleted); advancing to BLE")

        // ── Phase 4: BLE Presence ────────────────────────────────────────────
        val bleCompleted = runPhase(
            context = context,
            phase = DiscoveryScanPhase.BLE,
            timeoutMs = TIMEOUT_BLE_MS,
            searchingMessage = "Searching nearby Bluetooth devices...",
            checkAvailability = { checkBleAvailability(context) },
            startProvider = {
                BleDiscoveryManager.startScanning(context)
                HatBlePresenceProvider.startScanning(context)
            },
            stopProvider = {
                BleDiscoveryManager.stopScanning()
                HatBlePresenceProvider.stopScanning()
            },
            countDiscovered = {
                HatDiscoveryRegistry.discoveredNodes.value.count { it.hasSource(DiscoverySource.BLE) }
            }
        )
        Log.i(TAG, "BLE phase complete (found=$bleCompleted); all sequential phases executed")
    }

    private suspend fun runPhase(
        context: Context,
        phase: DiscoveryScanPhase,
        timeoutMs: Long,
        searchingMessage: String,
        checkAvailability: () -> Pair<Boolean, String?>,
        startProvider: () -> Unit,
        stopProvider: () -> Unit,
        countDiscovered: () -> Int
    ): Boolean {
        val (available, skipReason) = checkAvailability()
        val phaseStartMs = System.currentTimeMillis()

        if (!available) {
            Log.i(TAG, "DISCOVERY_PHASE_SKIPPED: phase=$phase reason=$skipReason")
            HatDiagnostics.event(
                HatDiagnostics.Severity.WARN,
                "DISCOVERY_PHASE_SKIPPED",
                mapOf("phase" to phase.name, "reason" to (skipReason ?: "Unavailable"))
            )
            updatePhaseReport(phase, PhaseStatus.SKIPPED, 0L, 0, skipReason)
            return false
        }

        Log.i(TAG, "DISCOVERY_PHASE_START: phase=$phase timeoutMs=$timeoutMs")
        HatDiagnostics.event(
            HatDiagnostics.Severity.INFO,
            "DISCOVERY_PHASE_START",
            mapOf("phase" to phase.name, "timeoutMs" to timeoutMs)
        )

        updatePhaseSearching(phase, timeoutMs, searchingMessage)

        try {
            startProvider()
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Failed starting provider"
            Log.e(TAG, "DISCOVERY_PHASE_FAILED: phase=$phase error=$errorMsg", e)
            HatDiagnostics.event(
                HatDiagnostics.Severity.ERROR,
                "DISCOVERY_PHASE_FAILED",
                mapOf("phase" to phase.name, "error" to errorMsg)
            )
            updatePhaseReport(phase, PhaseStatus.FAILED, System.currentTimeMillis() - phaseStartMs, 0, errorMsg)
            try { stopProvider() } catch (_: Exception) {}
            return false
        }

        var foundEarly = false
        var settleStartMs = 0L
        val intervalMs = 200L
        var elapsedMs = 0L

        while (elapsedMs < timeoutMs) {
            delay(intervalMs)
            elapsedMs = System.currentTimeMillis() - phaseStartMs
            val remainingMs = maxOf(0L, timeoutMs - elapsedMs)
            val secondsRemaining = ((remainingMs + 999L) / 1000L).toInt()

            val currentDiscovered = countDiscovered()
            updatePhaseTick(phase, secondsRemaining, remainingMs, searchingMessage, currentDiscovered)

            if (currentDiscovered > 0) {
                if (settleStartMs == 0L) {
                    settleStartMs = System.currentTimeMillis()
                }
                // Allow a settle window for secondary nodes to resolve, ensuring at least MIN_SEARCH_MS
                if ((System.currentTimeMillis() - settleStartMs >= SETTLE_WINDOW_MS && elapsedMs >= MIN_SEARCH_MS) || elapsedMs >= timeoutMs) {
                    foundEarly = true
                    break
                }
            }
        }

        try {
            stopProvider()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping provider for phase $phase: ${e.message}")
        }

        val totalDurationMs = System.currentTimeMillis() - phaseStartMs
        val finalCount = countDiscovered()

        if (finalCount > 0) {
            Log.i(TAG, "DISCOVERY_PHASE_FOUND: phase=$phase durationMs=$totalDurationMs count=$finalCount")
            HatDiagnostics.event(
                HatDiagnostics.Severity.INFO,
                "DISCOVERY_PHASE_FOUND",
                mapOf("phase" to phase.name, "durationMs" to totalDurationMs, "discoveredCount" to finalCount)
            )
            updatePhaseReport(phase, PhaseStatus.FOUND, totalDurationMs, finalCount, null)
            return true
        } else {
            Log.i(TAG, "DISCOVERY_PHASE_TIMEOUT: phase=$phase durationMs=$totalDurationMs count=0")
            HatDiagnostics.event(
                HatDiagnostics.Severity.INFO,
                "DISCOVERY_PHASE_TIMEOUT",
                mapOf("phase" to phase.name, "durationMs" to totalDurationMs, "discoveredCount" to 0)
            )
            updatePhaseReport(phase, PhaseStatus.TIMED_OUT, totalDurationMs, 0, "No devices found")
            return false
        }
    }

    private fun finishScan(context: Context) {
        synchronized(lock) {
            isScanActive.set(false)
            scanJob = null
            stopAllProviders(context)

            val totalDurationMs = System.currentTimeMillis() - scanStartTimeMs
            val finalNodes = HatDiscoveryRegistry.discoveredNodes.value
            val totalCount = finalNodes.size

            val summary = generateCompactSummary(_scanState.value.phaseReports)
            val finalBanner = if (totalCount > 0) {
                "Found $totalCount nearby device(s)"
            } else {
                "No nearby HAT devices found"
            }

            Log.i(TAG, "DISCOVERY_SCAN_COMPLETE: totalCount=$totalCount durationMs=$totalDurationMs summary=$summary")
            HatDiagnostics.event(
                HatDiagnostics.Severity.INFO,
                "DISCOVERY_SCAN_COMPLETE",
                mapOf(
                    "totalDiscoveredCount" to totalCount,
                    "totalDurationMs" to totalDurationMs,
                    "summary" to summary
                )
            )

            _scanState.value = _scanState.value.copy(
                currentPhase = DiscoveryScanPhase.COMPLETE,
                phaseStatus = PhaseStatus.WAITING,
                isScanning = false,
                statusMessage = finalBanner,
                totalDiscoveredNodes = finalNodes,
                scanSummary = summary,
                secondsRemaining = 0,
                phaseTimeRemainingMs = 0L
            )
        }
    }

    private fun stopAllProviders(context: Context) {
        try { LanDiscoveryProvider.stopDiscovery() } catch (_: Exception) {}
        try { DiscoveryManager.stopDiscovery() } catch (_: Exception) {}
        try { WifiDirectDiscoveryProvider.stopDiscovery() } catch (_: Exception) {}
        try { WifiDirectManager.stopPeerDiscovery(context) } catch (_: Exception) {}
        try { WifiAwareDiscoveryProvider.stopSubscribing() } catch (_: Exception) {}
        try { BleDiscoveryManager.stopScanning() } catch (_: Exception) {}
        try { HatBlePresenceProvider.stopScanning() } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Availability & Capability Probes
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkLanAvailability(context: Context): Pair<Boolean, String?> {
        return if (NetworkUtils.isLanAvailable(context)) {
            true to null
        } else {
            false to "Offline (No Wi-Fi)"
        }
    }

    private fun checkWifiDirectAvailability(context: Context): Pair<Boolean, String?> {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            return false to "Unsupported on device"
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm != null && !wm.isWifiEnabled) {
            return false to "Wi-Fi is turned off"
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationEnabled = lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        if (!locationEnabled) {
            return false to "Location (GPS) is turned off"
        }
        val hasNearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasNearby) {
            return false to "Permission required"
        }
        return true to null
    }

    private fun checkWifiAwareAvailability(context: Context): Pair<Boolean, String?> {
        if (!WifiAwareDiscoveryProvider.isSupported(context)) {
            return false to "Unsupported on device"
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm != null && !wm.isWifiEnabled) {
            return false to "Wi-Fi is turned off"
        }
        return true to null
    }

    private fun checkBleAvailability(context: Context): Pair<Boolean, String?> {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return false to "Unsupported on device"
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            return false to "Bluetooth is turned off"
        }
        if (!BleDiscoveryManager.hasPermissions(context)) {
            return false to "Permission required"
        }
        return true to null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State Mutation Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updatePhaseSearching(phase: DiscoveryScanPhase, timeoutMs: Long, message: String) {
        val currentReports = _scanState.value.phaseReports.toMutableMap()
        currentReports[phase] = PhaseReport(phase, PhaseStatus.SEARCHING, 0L, 0, null)
        val sec = ((timeoutMs + 999L) / 1000L).toInt()

        _scanState.value = _scanState.value.copy(
            currentPhase = phase,
            phaseStatus = PhaseStatus.SEARCHING,
            totalSeconds = sec,
            secondsRemaining = sec,
            phaseTimeRemainingMs = timeoutMs,
            statusMessage = message,
            phaseReports = currentReports,
            totalDiscoveredNodes = HatDiscoveryRegistry.discoveredNodes.value,
            isScanning = true
        )
    }

    private fun updatePhaseTick(
        phase: DiscoveryScanPhase,
        secondsRemaining: Int,
        timeRemainingMs: Long,
        message: String,
        discoveredCount: Int
    ) {
        val currentReports = _scanState.value.phaseReports.toMutableMap()
        currentReports[phase] = PhaseReport(phase, PhaseStatus.SEARCHING, 0L, discoveredCount, null)

        _scanState.value = _scanState.value.copy(
            currentPhase = phase,
            secondsRemaining = secondsRemaining,
            phaseTimeRemainingMs = timeRemainingMs,
            statusMessage = message,
            phaseReports = currentReports,
            totalDiscoveredNodes = HatDiscoveryRegistry.discoveredNodes.value
        )
    }

    private fun updatePhaseReport(
        phase: DiscoveryScanPhase,
        status: PhaseStatus,
        durationMs: Long,
        count: Int,
        reason: String?
    ) {
        val currentReports = _scanState.value.phaseReports.toMutableMap()
        currentReports[phase] = PhaseReport(
            phase = phase,
            status = status,
            durationMs = durationMs,
            discoveredCount = count,
            reason = reason
        )

        _scanState.value = _scanState.value.copy(
            phaseReports = currentReports,
            totalDiscoveredNodes = HatDiscoveryRegistry.discoveredNodes.value
        )
    }

    private fun generateCompactSummary(reports: Map<DiscoveryScanPhase, PhaseReport>): String {
        fun formatReport(phase: DiscoveryScanPhase): String {
            val r = reports[phase] ?: return "Waiting"
            return when (r.status) {
                PhaseStatus.FOUND -> "${r.discoveredCount}"
                PhaseStatus.TIMED_OUT -> "0"
                PhaseStatus.SKIPPED -> r.reason ?: "skipped"
                PhaseStatus.FAILED -> "failed"
                PhaseStatus.SEARCHING -> "searching"
                PhaseStatus.WAITING -> "waiting"
            }
        }
        return "Local Wi-Fi: ${formatReport(DiscoveryScanPhase.LOCAL_WIFI)} • " +
                "Wi-Fi Direct: ${formatReport(DiscoveryScanPhase.WIFI_DIRECT)} • " +
                "Wi-Fi Aware: ${formatReport(DiscoveryScanPhase.WIFI_AWARE)} • " +
                "Bluetooth: ${formatReport(DiscoveryScanPhase.BLE)}"
    }
}
