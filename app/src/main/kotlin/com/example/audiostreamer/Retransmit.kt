package com.example.audiostreamer

import kotlin.concurrent.withLock

/**
 * Selective retransmission (ARQ) for the HAT transport.
 *
 * Wi-Fi has ample bandwidth; the reliability problem on a LAN is sporadic *lost* datagrams, not
 * throughput. XOR FEC (block-of-4) recovers isolated loss but costs a permanent 25% airtime and
 * cannot fix a burst of >1 loss in a block. This module adds NACK-based retransmission so a
 * receiver can pull an exact missing packet it still needs, while the sender keeps a short replay
 * ring of already-encoded datagrams to serve it from.
 *
 * Roles:
 * - [RetransmitProtocol]: pure encode/decode of the `TYPE_RETX_REQUEST` payload.
 * - [RetransmitBuffer]: sender-side bounded ring of sent audio datagrams.
 * - [NackTracker]: receiver-side gap ledger with playout deadlines, attempt caps, and de-dup.
 *
 * All three are Android-free and take an injected monotonic `nowMs` clock so the whole protocol is
 * unit-testable. Sequence arithmetic follows the codebase's 16-bit signed-[diff] convention
 * ([SequenceTracker.diff]) and therefore wraps correctly.
 */
object RetransmitProtocol {
    /**
     * Encodes missing sequence numbers into a NACK payload: `[count:u16 BE][seq:u16 BE]...`.
     * [seqs] must already be de-duplicated and non-negative; out-of-range entries are dropped and
     * the list is truncated to [HatPacket.RETX_MAX_SEQS_PER_REQUEST]. Returns empty for no requests.
     */
    fun encodeMissingSeqs(seqs: Collection<Int>): ByteArray {
        val valid = seqs.asSequence()
            .map { it and 0xFFFF }
            .distinct()
            .sorted()
            .take(HatPacket.RETX_MAX_SEQS_PER_REQUEST)
            .toList()
        val buf = ByteArray(2 + valid.size * 2)
        HatPacket.writeUInt16BE(buf, 0, valid.size)
        for (i in valid.indices) HatPacket.writeUInt16BE(buf, 2 + i * 2, valid[i])
        return buf
    }

    /**
     * Decodes a NACK payload into the list of requested sequence numbers. Returns null when the
     * payload is structurally invalid (declared count disagrees with length, or count exceeds the
     * per-request cap), so callers can reject malformed datagrams rather than act on garbage.
     */
    fun decodeMissingSeqs(payload: ByteArray, offset: Int, length: Int): List<Int>? {
        if (length < 2) return null
        val count = HatPacket.readUInt16BE(payload, offset)
        if (count < 0 || count > HatPacket.RETX_MAX_SEQS_PER_REQUEST) return null
        if (length != 2 + count * 2) return null
        val out = ArrayList<Int>(count)
        for (i in 0 until count) out += HatPacket.readUInt16BE(payload, offset + 2 + i * 2)
        return out
    }
}

/**
 * Bounded replay ring of sent audio datagrams, so the transmitter can re-emit an exact packet on a
 * NACK without re-encoding. Stores the full on-the-wire datagram (header + payload) for [capacity]
 * packets; lookups are validated against the packet's stream generation to reject stale requests
 * after a profile/generation change.
 *
 * Thread-safe by internal lock: the capture thread records as it sends, while the control-listener
 * thread looks up to serve retransmit requests. Only the copied bytes leave the lock, so the
 * sender's own `socketSendLock` still governs the actual socket send.
 */
class RetransmitBuffer(val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) { "capacity must be a power of two" }
    }

    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val mask = capacity - 1
    // Slot buffers are allocated lazily on first record: an idle transmitter that never sends
    // (or never loses a packet) does not pay capacity × MAX_PACKET_SIZE up front.
    private val slots = arrayOfNulls<ByteArray>(capacity)
    private val slotLen = IntArray(capacity)
    private val slotSeq = IntArray(capacity) { -1 }
    private val slotGen = LongArray(capacity)

    companion object {
        const val DEFAULT_CAPACITY = 64
        private const val SLOT_SIZE = AudioConfig.MAX_PACKET_SIZE + HatPacket.HEADER_SIZE
    }

    /**
     * Stores [data] (offset..len) as the datagram for [seq]. [len] must fit the slot size; oversized
     * payloads are skipped rather than truncated, so a stored lookup is always a complete packet.
     */
    fun record(seq: Int, generation: Long, data: ByteArray, offset: Int, len: Int): Boolean = lock.withLock {
        if (len <= 0 || len > SLOT_SIZE) return@withLock false
        val idx = seq and mask
        val slot = slots[idx] ?: ByteArray(SLOT_SIZE).also { slots[idx] = it }
        System.arraycopy(data, offset, slot, 0, len)
        slotLen[idx] = len
        slotSeq[idx] = seq and 0xFFFF
        slotGen[idx] = generation
        true
    }

    /**
     * Copies the stored datagram for [seq] into [dest] and returns its length, or 0 when nothing
     * valid is present. A slot is only returned when its recorded sequence matches [seq] (guards
     * against ring aliasing across the 16-bit space) and, if [requireGeneration] is set, when its
     * stream generation matches — so a NACK for a previous generation is never replayed.
     */
    fun lookupInto(seq: Int, requireGeneration: Long?, dest: ByteArray): Int = lock.withLock {
        val idx = seq and mask
        if (slotSeq[idx] != (seq and 0xFFFF)) return@withLock 0
        if (requireGeneration != null && slotGen[idx] != requireGeneration) return@withLock 0
        val slot = slots[idx] ?: return@withLock 0
        val len = slotLen[idx]
        if (len <= 0 || len > dest.size) return@withLock 0
        System.arraycopy(slot, 0, dest, 0, len)
        len
    }

    fun clear() = lock.withLock {
        slots.fill(null)
        slotSeq.fill(-1)
        slotLen.fill(0)
        slotGen.fill(0L)
    }
}

/**
 * Receiver-side ledger of missing sequence numbers, deciding *what* to NACK and *when*.
 *
 * A missing packet is only worth requesting while it can still arrive before its playout deadline.
 * The tracker therefore caps total attempts and a request age, and de-duplicates: a sequence is not
 * re-requested until a resend-backoff elapses (so a lost NACK, not a lost audio packet, is what
 * drives retransmission volume). Everything is driven by an injected monotonic `nowMs` at the call
 * sites.
 */
class NackTracker(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val resendBackoffMs: Long = DEFAULT_RESEND_BACKOFF_MS,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxGapLookback: Int = DEFAULT_MAX_GAP_LOOKBACK,
    private val capacity: Int = DEFAULT_CAPACITY
) {
    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_RESEND_BACKOFF_MS = 24L   // ~2× Wi-Fi LAN RTT
        const val DEFAULT_MAX_AGE_MS = 140L         // older than the playout cushion → give up (PLC)
        const val DEFAULT_MAX_GAP_LOOKBACK = 8      // don't NACK a huge leap (reconnect) as one big gap
        const val DEFAULT_CAPACITY = 256
    }

    private data class Entry(
        var firstSeenMs: Long,
        var lastRequestedMs: Long,
        var attempts: Int
    )

    private val pending = HashMap<Int, Entry>(capacity)
    private var highestSeq = -1

    val pendingCount: Int get() = pending.size

    /**
     * Observes an arriving packet [seq] at [nowMs]: closes any pending gap it fills, advances the
     * high-water mark, and registers a bounded set of gaps for any sequences it leapt over. Call
     * once per received audio datagram (including FEC-recovered ones) — the caller does not need to
     * compute gaps itself.
     */
    fun onReceived(seq: Int, nowMs: Long) {
        val s = seq and 0xFFFF
        pending.remove(s)
        if (highestSeq == -1) {
            highestSeq = s
            return
        }
        val ahead = SequenceTracker.diff(s, highestSeq)
        if (ahead > 0) {
            // Register the sequences skipped between the old and new high-water mark, but only the
            // most recent [maxGapLookback] of them: a large leap is a restart, not a burst of loss.
            val gapStart = if (ahead > maxGapLookback) ahead - maxGapLookback else 1
            for (delta in gapStart until ahead) {
                val missing = (highestSeq + delta) and 0xFFFF
                if (!pending.containsKey(missing) && pending.size < capacity) {
                    pending[missing] = Entry(firstSeenMs = nowMs, lastRequestedMs = Long.MIN_VALUE, attempts = 0)
                }
            }
            highestSeq = s
        }
        pruneExpired(nowMs)
    }

    /**
     * Notes that sequence numbers in [missing] are absent as of [nowMs]. Existing entries are left
     * untouched so their age/attempts counters keep counting. Explicit gap reporting for callers
     * that already know what is missing; arrival-driven gaps use [onReceived].
     */
    fun onGapsDetected(missing: Collection<Int>, nowMs: Long) {
        for (m in missing) {
            val s = m and 0xFFFF
            if (!pending.containsKey(s) && pending.size < capacity) {
                pending[s] = Entry(firstSeenMs = nowMs, lastRequestedMs = Long.MIN_VALUE, attempts = 0)
            }
        }
        pruneExpired(nowMs)
    }

    /**
     * Notes a silence/keep-alive heartbeat at [seq]. Heartbeats consume sequence numbers on the
     * sender, so a silence stretch would otherwise look like a burst of lost audio at the next
     * real packet. This advances the high-water mark to [seq] (never backwards) and closes any
     * pending entry there, WITHOUT registering the intervening sequences as gaps.
     */
    fun noteSilence(seq: Int, nowMs: Long) {
        val s = seq and 0xFFFF
        pending.remove(s)
        if (highestSeq == -1 || SequenceTracker.diff(s, highestSeq) > 0) highestSeq = s
        pruneExpired(nowMs)
    }

    /**
     * Returns the sequence numbers to include in the next NACK, in ascending order, capped at
     * [HatPacket.RETX_MAX_SEQS_PER_REQUEST], and marks them requested. A sequence is returned only
     * when: it is still pending, under the attempt cap, older than nothing, and its last request
     * was at least [resendBackoffMs] ago (or it has never been requested).
     */
    fun dueForNack(nowMs: Long): List<Int> {
        pruneExpired(nowMs)
        val due = ArrayList<Int>()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (seq, e) = it.next()
            if (e.attempts >= maxAttempts) { it.remove(); continue }
            val eligible = e.lastRequestedMs == Long.MIN_VALUE ||
                (nowMs - e.lastRequestedMs) >= resendBackoffMs
            if (eligible) {
                due += seq
                e.lastRequestedMs = nowMs
                e.attempts++
                if (due.size >= HatPacket.RETX_MAX_SEQS_PER_REQUEST) break
            }
        }
        due.sort()
        return due
    }

    /** Drops entries that have exceeded [maxAgeMs] — those packets are past any useful deadline. */
    private fun pruneExpired(nowMs: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (nowMs - e.firstSeenMs > maxAgeMs) it.remove()
        }
    }

    fun clear() {
        pending.clear()
        highestSeq = -1
    }

    /** Exposed for diagnostics/tests: current high-water sequence. */
    fun highestSeen(): Int = highestSeq
}
