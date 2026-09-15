package com.example.audiostreamer

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates end-to-end synchronized HAT diagnostic test sessions on the transmitter side (Phase 3.2).
 *
 * Responsibilities:
 *  - Dispatches session announcements to connected receivers over the UDP control channel.
 *  - Handles explicit receiver acknowledgement (TEST_SESSION_JOINED) within a timeout.
 *  - Collects periodic incoming RX telemetry (RX_TEST_STATS).
 *  - Synchronizes generation transition acknowledgements (TEST_GENERATION_ACK).
 *  - Signals session termination (TEST_SESSION_END) upon test completion or cancellation.
 */
object HatTestSessionCoordinator {

    private const val TAG = "HatTestCoordinator"

    @Volatile
    var activeSessionId: String? = null
        private set

    @Volatile
    var receiverEndpoint: String? = null
        private set

    private val joinDeferredRef = AtomicReference<CompletableDeferred<HatTestReceiverInfo>?>()
    private val latestRxStatsRef = AtomicReference<HatTestControlMessage.RxStats?>()
    private val genAckMap = ConcurrentHashMap<Long, CompletableDeferred<HatTestControlMessage.GenerationAck>>()

    /**
     * Announces a new test session to connected receiver(s).
     */
    fun startSession(testSessionId: String, generation: Long): Boolean {
        activeSessionId = testSessionId
        receiverEndpoint = null
        val deferred = CompletableDeferred<HatTestReceiverInfo>()
        joinDeferredRef.set(deferred)
        latestRxStatsRef.set(null)
        genAckMap.clear()

        val msg = HatTestControlMessage.AnnounceSession(
            testSessionId = testSessionId,
            generation = generation,
            txDevice = Build.MODEL
        )
        val sent = AudioCaptureService.sendTestControlMessage(msg)
        Log.i(TAG, "Announced testSessionId=$testSessionId (generation=$generation, sent=$sent)")
        return sent
    }

    /**
     * Suspends until the receiver acknowledges joining the session, or returns null upon timeout.
     */
    suspend fun awaitReceiverJoin(testSessionId: String, timeoutMs: Long): HatTestReceiverInfo? {
        if (activeSessionId != testSessionId) return null
        val deferred = joinDeferredRef.get() ?: return null
        return withTimeoutOrNull(timeoutMs) {
            try {
                deferred.await()
            } catch (t: Throwable) {
                null
            }
        }
    }

    /**
     * Returns the latest RX stats received for the active session.
     */
    fun latestRxStats(testSessionId: String): HatTestControlMessage.RxStats? {
        if (activeSessionId != testSessionId) return null
        return latestRxStatsRef.get()
    }

    /**
     * Suspends until the receiver acknowledges playback of [generation], or returns null upon timeout.
     */
    suspend fun awaitGenerationAck(testSessionId: String, generation: Long, timeoutMs: Long): HatTestControlMessage.GenerationAck? {
        if (activeSessionId != testSessionId) return null
        val deferred = genAckMap.computeIfAbsent(generation) { CompletableDeferred() }
        return withTimeoutOrNull(timeoutMs) {
            try {
                deferred.await()
            } catch (t: Throwable) {
                null
            }
        }
    }

    /**
     * Ends the active test session and notifies the receiver.
     */
    fun endSession(testSessionId: String) {
        if (activeSessionId == testSessionId) {
            val msg = HatTestControlMessage.EndSession(testSessionId)
            AudioCaptureService.sendTestControlMessage(msg)
            Log.i(TAG, "Ended testSessionId=$testSessionId")
            activeSessionId = null
            joinDeferredRef.set(null)
            latestRxStatsRef.set(null)
            genAckMap.clear()
            receiverEndpoint = null
        }
    }

    /**
     * Dispatches an incoming test control message received on the transmitter's control socket.
     */
    fun onControlMessageReceived(msg: HatTestControlMessage, remoteAddress: InetAddress, remotePort: Int) {
        val currentSession = activeSessionId ?: return
        if (msg.testSessionId != currentSession) return

        when (msg) {
            is HatTestControlMessage.SessionJoined -> {
                val ep = "${remoteAddress.hostAddress}:$remotePort"
                receiverEndpoint = ep
                Log.i(TAG, "Receiver joined session: ${msg.device} (${msg.appVersion}) at $ep")
                val info = HatTestReceiverInfo(
                    testSessionId = msg.testSessionId,
                    device = msg.device,
                    appVersion = msg.appVersion,
                    generation = msg.generation,
                    endpoint = ep
                )
                joinDeferredRef.get()?.complete(info)
            }
            is HatTestControlMessage.RxStats -> {
                latestRxStatsRef.set(msg)
            }
            is HatTestControlMessage.GenerationAck -> {
                Log.i(TAG, "Received generation ACK for gen=${msg.generation}, profile=${msg.profile}")
                val deferred = genAckMap.computeIfAbsent(msg.generation) { CompletableDeferred() }
                deferred.complete(msg)
            }
            else -> { /* Ignore AnnounceSession and EndSession on transmitter */ }
        }
    }

    /** Test hook to inject state or reset between test runs. */
    fun reset() {
        activeSessionId = null
        joinDeferredRef.set(null)
        latestRxStatsRef.set(null)
        genAckMap.clear()
        receiverEndpoint = null
    }
}
