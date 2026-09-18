package com.example.audiostreamer.node

import com.example.audiostreamer.node.transport.HatTransportType
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Transport Candidate & Availability
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Availability state of a candidate transport for a discovered Node.
 */
enum class TransportAvailability {
    /** The transport is reachable, verified, and ready for connection. */
    AVAILABLE,
    /** The transport is being probed or negotiated (e.g. Wi-Fi Aware attach, P2P discovery). */
    PROBING,
    /** The transport was previously established but is experiencing degradation or packet loss. */
    DEGRADED,
    /** The transport is not currently available or supported on local/remote nodes. */
    UNAVAILABLE,
    /** The transport was attempted and explicitly failed (connection refused, timeout, etc.). */
    FAILED
}

/**
 * A concrete candidate transport evaluated for establishing a [HatLink] to a specific remote Node.
 *
 * **Architecture Rules:**
 * - Candidate transports for audio links are strictly: `LAN`, `WIFI_AWARE`, `WIFI_DIRECT`.
 * - BLE and NFC are non-audio discovery/bootstrap mechanisms and must NOT be used as audio transport candidates.
 */
data class TransportCandidate(
    val type: HatTransportType,
    val availability: TransportAvailability = TransportAvailability.AVAILABLE,
    val endpointAddress: String? = null,
    val endpointPort: Int? = null,
    val isDirect: Boolean = false,
    val priorityRank: Int = 0,
    val lastVerifiedEpochMs: Long = System.currentTimeMillis(),
    val failureReason: String? = null,
    val details: Map<String, Any?> = emptyMap()
) {
    init {
        require(type.isAudioTransport) {
            "TransportCandidate type must be a high-bandwidth audio transport (LAN, WIFI_AWARE, WIFI_DIRECT). $type is bootstrap/discovery only."
        }
    }

    val isUsable: Boolean
        get() = availability == TransportAvailability.AVAILABLE || availability == TransportAvailability.PROBING

    fun withAvailability(newAvailability: TransportAvailability, reason: String? = null): TransportCandidate =
        copy(availability = newAvailability, failureReason = reason, lastVerifiedEpochMs = System.currentTimeMillis())
}

// ─────────────────────────────────────────────────────────────────────────────
// Transport Priority Policies
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Defines an explicit ordering policy for prioritizing and selecting candidate transports.
 * Does NOT rely on arbitrary hardcoded "best" logic.
 */
interface TransportPriorityPolicy {
    val name: String
    val preferredOrder: List<HatTransportType>

    /**
     * Selects the highest-priority usable candidate matching this policy.
     */
    fun selectCandidate(candidates: List<TransportCandidate>): TransportCandidate? {
        val usableCandidates = candidates.filter { it.isUsable }
        for (preferredType in preferredOrder) {
            val match = usableCandidates.firstOrNull { it.type == preferredType }
            if (match != null) return match
        }
        return null
    }
}

/**
 * Predefined explicit transport priority policies.
 */
enum class TransportPriority(
    override val preferredOrder: List<HatTransportType>
) : TransportPriorityPolicy {
    /**
     * Priority: Wi-Fi Aware -> Wi-Fi Direct -> LAN.
     */
    WIFI_AWARE_FIRST(listOf(HatTransportType.WIFI_AWARE, HatTransportType.WIFI_DIRECT, HatTransportType.LAN)),

    /**
     * Priority: Wi-Fi Direct -> Wi-Fi Aware -> LAN.
     */
    WIFI_DIRECT_FIRST(listOf(HatTransportType.WIFI_DIRECT, HatTransportType.WIFI_AWARE, HatTransportType.LAN)),

    /**
     * Priority: LAN -> Wi-Fi Aware -> Wi-Fi Direct.
     */
    LAN_FIRST(listOf(HatTransportType.LAN, HatTransportType.WIFI_AWARE, HatTransportType.WIFI_DIRECT)),

    /**
     * Balanced default policy preferring direct high-bandwidth paths: Wi-Fi Aware -> Wi-Fi Direct -> LAN.
     */
    BALANCED(listOf(HatTransportType.WIFI_AWARE, HatTransportType.WIFI_DIRECT, HatTransportType.LAN));

    companion object {
        val DEFAULT = BALANCED

        fun custom(order: List<HatTransportType>, name: String = "CUSTOM"): TransportPriorityPolicy =
            CustomTransportPriority(name, order)
    }
}

/**
 * Custom explicit transport priority policy.
 */
data class CustomTransportPriority(
    override val name: String,
    override val preferredOrder: List<HatTransportType>
) : TransportPriorityPolicy

// ─────────────────────────────────────────────────────────────────────────────
// Link Attempt
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Lifecycle state of a [LinkAttempt].
 */
enum class AttemptState {
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMED_OUT
}

/**
 * Represents a discrete, instrumented attempt to establish a [HatLink] to a remote node.
 * Tracks timing, target candidate, exact failure reasons, and final outcome.
 */
data class LinkAttempt(
    val attemptId: String = UUID.randomUUID().toString(),
    val remoteNodeId: String,
    val candidate: TransportCandidate,
    val startTimeEpochMs: Long = System.currentTimeMillis(),
    var endTimeEpochMs: Long? = null,
    var state: AttemptState = AttemptState.IN_PROGRESS,
    var failureReason: String? = null,
    var failureDetails: Map<String, Any?> = emptyMap()
) {
    val durationMs: Long
        get() = (endTimeEpochMs ?: System.currentTimeMillis()) - startTimeEpochMs

    fun markSuccess(): LinkAttempt {
        endTimeEpochMs = System.currentTimeMillis()
        state = AttemptState.SUCCESS
        failureReason = null
        return this
    }

    fun markFailed(reason: String, details: Map<String, Any?> = emptyMap()): LinkAttempt {
        endTimeEpochMs = System.currentTimeMillis()
        state = AttemptState.FAILED
        failureReason = reason
        failureDetails = details
        return this
    }

    fun markCancelled(reason: String = "Cancelled"): LinkAttempt {
        endTimeEpochMs = System.currentTimeMillis()
        state = AttemptState.CANCELLED
        failureReason = reason
        return this
    }

    fun markTimedOut(reason: String = "Connection attempt timed out"): LinkAttempt {
        endTimeEpochMs = System.currentTimeMillis()
        state = AttemptState.TIMED_OUT
        failureReason = reason
        return this
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Node Link Context
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Retained link management state for a single discovered HAT Node.
 */
data class DiscoveredNodeLinkContext(
    val remoteNode: NodeInfo,
    val candidateTransports: MutableList<TransportCandidate> = mutableListOf(),
    var activeLink: HatLink? = null,
    var currentAttempt: LinkAttempt? = null,
    val attemptHistory: MutableList<LinkAttempt> = mutableListOf(),
    var lastSelectedTransport: HatTransportType? = null,
    var autoReconnect: Boolean = true,
    var lastStateChangeEpochMs: Long = System.currentTimeMillis()
) {
    val nodeId: String get() = remoteNode.id
    val nodeName: String get() = remoteNode.name
    val isConnected: Boolean get() = activeLink?.isAlive == true && activeLink?.state == LinkState.CONNECTED

    fun recordAttempt(attempt: LinkAttempt, maxHistory: Int = 20) {
        currentAttempt = attempt
        synchronized(attemptHistory) {
            attemptHistory.add(attempt)
            if (attemptHistory.size > maxHistory) {
                attemptHistory.removeAt(0)
            }
        }
        lastStateChangeEpochMs = System.currentTimeMillis()
    }
}
