package com.example.audiostreamer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.widget.Toast
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Central runtime diagnostics for the HAT audio pipeline.
 *
 * Design rules (do not violate at a call site):
 *  - nothing here blocks or performs I/O on the caller: [event]/[increment]/[recordTime] are lock-free
 *    except for the short bounded ring-buffer append;
 *  - packet-level logging is DISABLED unless [Config.packetLoggingEnabled] is set, and even then it is
 *    rate bounded by the ring buffer;
 *  - no unbounded collection exists: the event ring, the counter table, the timing table and the per-timing
 *    percentile sample rings are all fixed/bounded;
 *  - periodic statistics run on ONE dedicated daemon thread started by [startPeriodicStats], never on an
 *    audio thread, and a failing task can never escape or kill that thread;
 *  - no exception raised by diagnostics may propagate into audio/transport code.
 *
 * Everything the audio loop touches ([increment], [recordTime], [packet]) only writes atomics, so the hot
 * path never allocates beyond the handful of events emitted per second.
 */
object HatDiagnostics {

    const val TAG = "HatDiag"

    /** Minimum severity that is emitted. Rank order defines verbosity (`STATS` is the most verbose). */
    enum class Level(val rank: Int) {
        OFF(0), ERROR(1), WARN(2), INFO(3), DEBUG(4), STATS(5)
    }

    enum class Severity(val level: Level) {
        ERROR(Level.ERROR),
        WARN(Level.WARN),
        INFO(Level.INFO),
        DEBUG(Level.DEBUG),
        STATS(Level.STATS)
    }

    data class Config(
        val level: Level = Level.STATS,
        val packetLoggingEnabled: Boolean = false,
        val statsIntervalMs: Long = 1_000L,
        val ringCapacity: Int = 512,
        /** Emit a full diagnostic snapshot + recent history when an ERROR is recorded. */
        val snapshotOnError: Boolean = true,
        /** Minimum spacing between automatic error snapshots, so an error storm cannot flood the log. */
        val errorSnapshotMinIntervalMs: Long = 5_000L,
        val statsEnabled: Boolean = true
    )

    /** Static device/run metadata rendered into the snapshot. Set once per process by the running service. */
    data class Metadata(
        val appVersion: String? = null,
        val gitRevision: String? = null,
        val deviceModel: String? = null,
        val manufacturer: String? = null,
        val androidVersion: String? = null,
        val apiLevel: Int? = null,
        val audioOutputDevice: String? = null,
        val audioSampleRate: Int? = null,
        val audioChannelConfig: String? = null,
        val networkTransport: String? = null
    )

    /** Fixed-size rolling aggregate for one measured operation. */
    class Timing(
        private val name: String
    ) {
        private val count = AtomicLong()
        private val totalNs = AtomicLong()
        private val minNs = AtomicLong(Long.MAX_VALUE)
        private val maxNs = AtomicLong(Long.MIN_VALUE)
        private val samples = LongArray(SAMPLE_RING_SIZE) { -1L }
        private val sampleIndex = AtomicInteger()

        fun record(nanos: Long) {
            if (nanos < 0L) return
            count.incrementAndGet()
            totalNs.addAndGet(nanos)
            updateMin(nanos)
            updateMax(nanos)
            samples[sampleIndex.getAndIncrement() and SAMPLE_RING_MASK] = nanos
        }

        private fun updateMin(value: Long) {
            while (true) {
                val current = minNs.get()
                if (value >= current) return
                if (minNs.compareAndSet(current, value)) return
            }
        }

        private fun updateMax(value: Long) {
            while (true) {
                val current = maxNs.get()
                if (value <= current) return
                if (maxNs.compareAndSet(current, value)) return
            }
        }

        data class Snapshot(
            val name: String,
            val count: Long,
            val avgNs: Long,
            val minNs: Long,
            val maxNs: Long,
            val p95Ns: Long,
            val p99Ns: Long
        ) {
            fun toMs(value: Long): String = String.format(java.util.Locale.US, "%.2f", value / 1_000_000.0)
        }

        fun snapshot(): Snapshot {
            val c = count.get()
            if (c == 0L) return Snapshot(name, 0L, 0L, 0L, 0L, 0L, 0L)
            val sorted = samples.filter { it >= 0L }.sorted()
            val p95 = percentile(sorted, 0.95)
            val p99 = percentile(sorted, 0.99)
            return Snapshot(
                name = name,
                count = c,
                avgNs = totalNs.get() / c,
                minNs = minNs.get().takeIf { it != Long.MAX_VALUE } ?: 0L,
                maxNs = maxNs.get().takeIf { it != Long.MIN_VALUE } ?: 0L,
                p95Ns = p95,
                p99Ns = p99
            )
        }

        private fun percentile(sorted: List<Long>, fraction: Double): Long {
            if (sorted.isEmpty()) return 0L
            val idx = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }

        fun reset() {
            count.set(0L)
            totalNs.set(0L)
            minNs.set(Long.MAX_VALUE)
            maxNs.set(Long.MIN_VALUE)
            sampleIndex.set(0)
            for (i in samples.indices) samples[i] = -1L
        }

        private companion object {
            const val SAMPLE_RING_SIZE = 256
            const val SAMPLE_RING_MASK = SAMPLE_RING_SIZE - 1
        }
    }

    data class Event(
        val timestampMs: Long,
        val severity: Severity,
        val name: String,
        val runId: String,
        val streamId: String,
        val generation: Long,
        val fields: Map<String, Any?>?,
        val throwable: Throwable?
    ) {
        fun render(): String {
            val sb = StringBuilder(96)
            sb.append(name)
            if (runId.isNotEmpty()) sb.append(" run=").append(runId)
            if (streamId.isNotEmpty()) sb.append(" stream=").append(streamId)
            if (generation > 0L) sb.append(" gen=").append(generation)
            val f = fields
            if (f != null) {
                for ((k, v) in f) {
                    sb.append(' ').append(k).append('=').append(fmt(v))
                }
            }
            return sb.toString()
        }
    }

    /** Ordered sections that make up the diagnostic snapshot. Providers are optional per section. */
    private val SNAPSHOT_SECTIONS = listOf(
        "CONFIG", "GENERATION", "TX", "RX", "CAPTURE", "JITTER", "PLAYBACK", "RECEIVERS"
    )

    @Volatile
    var config: Config = Config()
        private set

    private val ringLock = Any()
    @Volatile private var ring = ArrayDeque<Event>(512)

    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val timings = ConcurrentHashMap<String, Timing>()
    private val sections = ConcurrentHashMap<String, () -> Map<String, Any?>>()
    private val periodicTasks = ConcurrentHashMap<String, () -> Unit>()
    private val firedOnce = ConcurrentHashMap.newKeySet<String>()
    private val generationCreatedAtMs = ConcurrentHashMap<Long, Long>()

    @Volatile private var metadata: Metadata = Metadata()
    @Volatile private var runIdValue: String = ""
    @Volatile private var streamIdValue: String = ""
    private val generationValue = AtomicLong(0L)

    private var statsThread: Thread? = null
    @Volatile private var statsRunning = false
    private val lastSnapshotMs = AtomicLong(0L)
    private val statsTicks = AtomicLong(0L)

    // ---------------------------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------------------------

    fun configure(newConfig: Config) {
        guard("configure") {
            val capacity = newConfig.ringCapacity.coerceIn(16, 4096)
            config = newConfig.copy(
                statsIntervalMs = newConfig.statsIntervalMs.coerceIn(200L, 60_000L),
                ringCapacity = capacity
            )
            synchronized(ringLock) {
                ring = ArrayDeque(capacity)
            }
        }
    }

    fun setLevel(level: Level) {
        configure(config.copy(level = level))
    }

    fun setPacketLoggingEnabled(enabled: Boolean) {
        configure(config.copy(packetLoggingEnabled = enabled))
    }

    fun setStatsIntervalMs(intervalMs: Long) {
        configure(config.copy(statsIntervalMs = intervalMs))
        if (statsRunning) {
            stopPeriodicStats()
            startPeriodicStats()
        }
    }

    fun setRingCapacity(capacity: Int) {
        configure(config.copy(ringCapacity = capacity))
    }

    fun setMetadata(newMetadata: Metadata) {
        metadata = newMetadata
    }

    fun metadata(): Metadata = metadata

    // ---------------------------------------------------------------------------------------------
    // Run / stream / generation identity
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens a new runtime session. [runId] should be unique per process run and include a timestamp and
     * device hint; callers use [newRunId] to build one.
     */
    fun startRun(streamId: String, runId: String = newRunId()) {
        guard("startRun") {
            runIdValue = runId
            streamIdValue = streamId
            firedOnce.clear()
            generationCreatedAtMs.clear()
            generationValue.set(0L)
        }
    }

    /** Builds a unique run id from the wall clock, an incrementing session counter and a device hint. */
    fun newRunId(deviceHint: String? = null): String {
        val hint = (deviceHint ?: runCatching { Build.MODEL }.getOrNull() ?: "device")
            .filter { it.isLetterOrDigit() || it == '-' }
            .take(16)
            .ifEmpty { "device" }
        val session = SESSION_COUNTER.incrementAndGet()
        return "${System.currentTimeMillis().toString(36)}-$session-$hint"
    }

    fun runId(): String = runIdValue

    fun streamId(): String = streamIdValue

    fun generation(): Long = generationValue.get()

    /**
     * Records the authoritative stream generation. Emits a GENERATION_CHANGED event only when it actually
     * changes, and remembers when a generation was first created so lifecycle events can carry timing.
     */
    fun setGeneration(generation: Long, fields: Map<String, Any?>? = null) {
        guard("setGeneration") {
            val previous = generationValue.getAndSet(generation)
            if (previous != generation) {
                // Bounded: a very long-running session that churns generations must not grow this map without
                // limit. Only the timestamps of generations still in flight are useful for lifecycle deltas.
                if (generationCreatedAtMs.size > MAX_TRACKED_GENERATIONS) generationCreatedAtMs.clear()
                generationCreatedAtMs.putIfAbsent(generation, System.currentTimeMillis())
                info("GENERATION_CHANGED", (fields ?: emptyMap()) + mapOf("previous" to previous, "current" to generation))
            }
        }
    }

    /** True the first time [key] is seen since the last [startRun]; used for GENERATION_FIRST_* markers. */
    fun firstTime(key: String): Boolean = firedOnce.add(key)

    /**
     * Emits a generation lifecycle marker with the elapsed time since the generation was first observed.
     */
    fun lifecycle(name: String, generation: Long, fields: Map<String, Any?>? = null) {
        guard("lifecycle") {
            if (generation > 0L && generationCreatedAtMs.size > MAX_TRACKED_GENERATIONS) {
                generationCreatedAtMs.clear()
            }
            if (generation > 0L) generationCreatedAtMs.putIfAbsent(generation, System.currentTimeMillis())
            val created = if (generation > 0L) generationCreatedAtMs[generation] else null
            val elapsed = if (created != null) System.currentTimeMillis() - created else null
            val base = fields ?: emptyMap()
            val withGen = if (generation > 0L) base + ("generation" to generation) else base
            info(name, if (elapsed != null) withGen + ("sinceCreatedMs" to elapsed) else withGen)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------------

    fun event(severity: Severity, name: String, fields: Map<String, Any?>? = null, tr: Throwable? = null) {
        val cfg = config
        if (!enabled(severity, cfg)) return
        val ev = Event(
            timestampMs = System.currentTimeMillis(),
            severity = severity,
            name = name,
            runId = runIdValue,
            streamId = streamIdValue,
            generation = generationValue.get(),
            fields = fields,
            throwable = tr
        )
        record(ev, cfg)
    }

    fun error(name: String, fields: Map<String, Any?>? = null, tr: Throwable? = null) =
        event(Severity.ERROR, name, fields, tr)

    fun warn(name: String, fields: Map<String, Any?>? = null, tr: Throwable? = null) =
        event(Severity.WARN, name, fields, tr)

    fun info(name: String, fields: Map<String, Any?>? = null) =
        event(Severity.INFO, name, fields)

    fun debug(name: String, fields: Map<String, Any?>? = null) =
        event(Severity.DEBUG, name, fields)

    /** Periodic aggregate event. Emitted once per statistics interval by the diagnostics thread. */
    fun stats(name: String, fields: Map<String, Any?>? = null) =
        event(Severity.STATS, name, fields)

    /**
     * Per-packet/trace logging. DISABLED by default and always inexpensive when disabled: the check happens
     * before any map or string allocation by the caller being skipped entirely. Callers must nevertheless
     * avoid building the field map before calling unless packet logging is on.
     */
    fun packet(name: String, fields: () -> Map<String, Any?> = { emptyMap() }) {
        val cfg = config
        if (!cfg.packetLoggingEnabled || !enabled(Severity.DEBUG, cfg)) return
        event(Severity.DEBUG, name, fields())
    }

    fun isPacketLoggingEnabled(): Boolean = config.packetLoggingEnabled

    private fun enabled(severity: Severity, cfg: Config): Boolean {
        val level = cfg.level
        if (level == Level.OFF) return false
        return severity.level.rank <= level.rank
    }

    private fun record(ev: Event, cfg: Config) {
        try {
            val capacity = cfg.ringCapacity
            synchronized(ringLock) {
                if (ring.size >= capacity) ring.pollFirst()
                ring.addLast(ev)
            }
        } catch (ignored: Throwable) {
            // never let diagnostics break the caller
        }
        try {
            when (ev.severity) {
                Severity.ERROR -> AppLogger.e(TAG, ev.render(), ev.throwable)
                Severity.WARN -> AppLogger.w(TAG, ev.render(), ev.throwable)
                Severity.INFO -> AppLogger.i(TAG, ev.render())
                Severity.DEBUG -> AppLogger.d(TAG, ev.render())
                Severity.STATS -> AppLogger.i(TAG, ev.render())
            }
        } catch (ignored: Throwable) {
        }
        if (ev.severity == Severity.ERROR && cfg.snapshotOnError) {
            maybeEmitErrorContext(ev, cfg)
        }
    }

    /**
     * Emits the current diagnostic snapshot plus the recent ring-buffer history for an error that is not a
     * plain ERROR event (PLAYBACK_UNDERRUN, DECODE_ERROR, ...). Rate limited like [Config.snapshotOnError].
     */
    fun snapshotContext(reason: String, fields: Map<String, Any?>? = null, tr: Throwable? = null) {
        val cfg = config
        if (!cfg.snapshotOnError) return
        guard("snapshotContext") {
            val detail = if (fields.isNullOrEmpty()) {
                ""
            } else {
                " " + fields.entries.joinToString(" ") { "${it.key}=${fmt(it.value)}" }
            }
            emitSnapshotContext("$reason$detail", tr, cfg)
        }
    }

    /**
     * On a serious error, immediately emit the current diagnostic snapshot plus the recent ring-buffer
     * history. Rate limited by [Config.errorSnapshotMinIntervalMs] so an error storm cannot flood the log.
     */
    private fun maybeEmitErrorContext(ev: Event, cfg: Config) {
        guard("emitErrorContext") { emitSnapshotContext(ev.render(), ev.throwable, cfg) }
    }

    private fun emitSnapshotContext(label: String, tr: Throwable?, cfg: Config) {
        val now = System.currentTimeMillis()
        val last = lastSnapshotMs.get()
        if (now - last < cfg.errorSnapshotMinIntervalMs) return
        if (!lastSnapshotMs.compareAndSet(last, now)) return
        val report = "ERROR_CONTEXT: $label\n" + snapshotInternal(includeRecentEvents = true, includeSections = true)
        AppLogger.e(TAG, report, tr)
    }

    fun clearRing() {
        synchronized(ringLock) { ring.clear() }
    }

    fun recentEvents(limit: Int = 40): List<Event> {
        val copy: List<Event> = synchronized(ringLock) { ring.toList() }
        if (copy.size <= limit) return copy
        return copy.subList(copy.size - limit, copy.size)
    }

    fun ringSize(): Int = synchronized(ringLock) { ring.size }

    // ---------------------------------------------------------------------------------------------
    // Counters
    // ---------------------------------------------------------------------------------------------

    /** Adds [delta] to [name] and returns the new value. Allocation-free on the hot path. */
    fun increment(name: String, delta: Long = 1L): Long {
        val existing = counters[name]
        if (existing != null) {
            return existing.addAndGet(delta)
        }
        return counters.computeIfAbsent(name) { AtomicLong() }.addAndGet(delta)
    }

    fun counter(name: String): Long = counters[name]?.get() ?: 0L

    fun resetCounters() {
        counters.clear()
    }

    fun counterSnapshot(): Map<String, Long> =
        counters.entries.associate { it.key to it.value.get() }

    // ---------------------------------------------------------------------------------------------
    // Timing
    // ---------------------------------------------------------------------------------------------

    /** Records one measured duration. Safe (and allocation-free) on audio-critical threads. */
    fun recordTime(name: String, nanos: Long) {
        val existing = timings[name]
        if (existing != null) {
            existing.record(nanos)
            return
        }
        timings.computeIfAbsent(name) { Timing(it) }.record(nanos)
    }

    fun timing(name: String): Timing? = timings[name]

    fun timingSnapshot(): List<Timing.Snapshot> = timings.keys.sorted().mapNotNull { timings[it]?.snapshot() }

    fun resetTimings() {
        timings.values.forEach { it.reset() }
    }

    // ---------------------------------------------------------------------------------------------
    // Periodic statistics (dedicated thread)
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers a periodic statistics producer. [task] runs on the diagnostics thread once per interval and
     * typically emits [stats] events for aggregates it owns. It must not block for long and must never throw.
     */
    fun registerPeriodicTask(name: String, task: () -> Unit) {
        periodicTasks[name] = task
    }

    fun unregisterPeriodicTask(name: String) {
        periodicTasks.remove(name)
    }

    fun registerSection(name: String, provider: () -> Map<String, Any?>) {
        sections[name] = provider
    }

    fun unregisterSection(name: String) {
        sections.remove(name)
    }

    /**
     * Current values of every registered snapshot section, e.g. {"CAPTURE": {"framesCaptured": ...}, ...}.
     * Used by the automated test harness to read live runtime state (capture/playback/network health)
     * without parsing log text. Missing or failing providers are reported as empty maps.
     */
    fun sectionsSnapshot(): Map<String, Map<String, Any?>> {
        val out = LinkedHashMap<String, Map<String, Any?>>()
        for ((name, provider) in sections) {
            out[name] = guard("section:$name") { provider() } ?: emptyMap()
        }
        return out
    }

    fun startPeriodicStats() {
        if (statsRunning) return
        if (!config.statsEnabled) return
        statsRunning = true
        val thread = Thread({
            while (statsRunning) {
                val interval = config.statsIntervalMs.coerceAtLeast(1L)
                try {
                    Thread.sleep(interval)
                } catch (e: InterruptedException) {
                    break
                }
                if (!statsRunning) break
                runStatsTick()
            }
        }, "HatDiagnosticsStats")
        thread.isDaemon = true
        statsThread = thread
        thread.start()
    }

    fun stopPeriodicStats() {
        statsRunning = false
        statsThread?.interrupt()
        statsThread = null
    }

    fun statsTicks(): Long = statsTicks.get()

    /** Runs one statistics interval. Exposed so tests can drive the periodic path deterministically. */
    fun runStatsTick() {
        guard("runStatsTick") {
            statsTicks.incrementAndGet()
            val tick = statsTicks.get()
            for ((name, task) in periodicTasks) {
                guard("stats:$name") {
                    task()
                }
            }
            emitRuntimeStats(tick)
        }
    }

    private fun emitRuntimeStats(tick: Long) {
        guard("runtimeStats") {
            val runtime = Runtime.getRuntime()
            val heapMax = runtime.maxMemory()
            val heapUsed = runtime.totalMemory() - runtime.freeMemory()
            val fields = linkedMapOf<String, Any?>(
                "heapUsedMb" to heapUsed / (1024 * 1024),
                "heapMaxMb" to heapMax / (1024 * 1024),
                "threads" to runCatching { Thread.activeCount() }.getOrDefault(0)
            )
            runCatching { Debug.getRuntimeStat("art.gc.gc-count") }.getOrNull()?.let { fields["gcCount"] = it }
            runCatching { Debug.getRuntimeStat("art.gc.gc-time") }.getOrNull()?.let { fields["gcTimeMs"] = it }
            runCatching { Debug.getRuntimeStat("art.gc.blocking-gc-count") }.getOrNull()?.let { fields["blockingGcCount"] = it }
            runCatching { android.os.Process.getElapsedCpuTime() }.getOrNull()?.let { fields["elapsedCpuMs"] = it }
            fields["tick"] = tick
            stats("RUNTIME_STATS", fields)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Snapshot
    // ---------------------------------------------------------------------------------------------

    /** Renders the full snapshot. Never throws: a failure yields a minimal, honest placeholder. */
    fun snapshot(): String = guard("snapshot") {
        snapshotInternal(includeRecentEvents = true, includeSections = true)
    } ?: "=== HAT DIAGNOSTIC SNAPSHOT ===\n(snapshot unavailable)\n==============================="

    /**
     * Debug API: writes the diagnostic snapshot to the clipboard. Returns the snapshot text so callers can
     * also surface it without touching the clipboard.
     */
    fun copySnapshotToClipboard(context: Context): String {
        val report = snapshot()
        guard("copySnapshot") {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("HAT Diagnostics", report))
            Toast.makeText(context, "HAT diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        return report
    }

    private fun snapshotInternal(includeRecentEvents: Boolean, includeSections: Boolean): String {
        val sb = StringBuilder(4096)
        sb.appendLine("=== HAT DIAGNOSTIC SNAPSHOT ===")

        sb.appendLine("[RUN]")
        sb.appendLine("runId=$runIdValue")
        sb.appendLine("streamId=$streamIdValue")
        sb.appendLine("generation=${generationValue.get()}")
        sb.appendLine("timestamp=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine("uptimeMs=${DiagnosticClock.elapsedRealtimeMs()}")

        val md = metadata
        sb.appendLine("[APP]")
        sb.appendLine("appVersion=${fmt(md.appVersion)}")
        sb.appendLine("gitRevision=${fmt(md.gitRevision)}")
        sb.appendLine("[DEVICE]")
        sb.appendLine("manufacturer=${fmt(md.manufacturer)}")
        sb.appendLine("model=${fmt(md.deviceModel)}")
        sb.appendLine("[ANDROID]")
        sb.appendLine("version=${fmt(md.androidVersion)}")
        sb.appendLine("apiLevel=${fmt(md.apiLevel)}")
        sb.appendLine("audioOutputDevice=${fmt(md.audioOutputDevice)}")
        sb.appendLine("sampleRate=${fmt(md.audioSampleRate)}")
        sb.appendLine("channelConfiguration=${fmt(md.audioChannelConfig)}")
        sb.appendLine("[NETWORK]")
        sb.appendLine("transport=${fmt(md.networkTransport)}")

        if (includeSections) {
            val registered = mutableSetOf<String>()
            for (name in SNAPSHOT_SECTIONS) {
                val provider = sections[name] ?: continue
                registered += name
                appendSection(sb, name, provider)
            }
            for ((name, provider) in sections) {
                if (name in registered) continue
                appendSection(sb, name, provider)
            }
            appendTimingSection(sb)
        }

        sb.appendLine("[ERROR COUNTERS]")
        val countersSnapshot = counterSnapshot()
        val errorish = countersSnapshot.filterKeys { looksLikeError(it) || it.contains("lost") || it.contains("drop") }
        if (errorish.isEmpty()) sb.appendLine("(none)")
        for ((k, v) in errorish.toSortedMap()) sb.appendLine("$k=$v")

        sb.appendLine("[COUNTERS]")
        if (countersSnapshot.isEmpty()) sb.appendLine("(none)")
        for ((k, v) in countersSnapshot.toSortedMap()) sb.appendLine("$k=$v")

        if (includeRecentEvents) {
            val events = recentEvents(40)
            sb.appendLine("[RECENT EVENTS] (${events.size} of ${ringSize()})")
            for (ev in events) {
                sb.appendLine("  ${isoTime(ev.timestampMs)} ${ev.severity} ${ev.render()}")
            }
        }

        sb.appendLine("===============================")
        return sb.toString()
    }

    private fun appendSection(sb: StringBuilder, name: String, provider: () -> Map<String, Any?>) {
        sb.appendLine("[$name]")
        val values = guard("section:$name") { provider() } ?: emptyMap()
        if (values.isEmpty()) {
            sb.appendLine("(none)")
            return
        }
        for ((k, v) in values) sb.appendLine("$k=${fmt(v)}")
    }

    private fun appendTimingSection(sb: StringBuilder) {
        sb.appendLine("[AUDIO LOOP TIMING]")
        val snapshots = timingSnapshot()
        if (snapshots.isEmpty()) {
            sb.appendLine("(none)")
            return
        }
        for (t in snapshots) {
            sb.appendLine(
                "${t.name}: count=${t.count} avgMs=${t.toMs(t.avgNs)} minMs=${t.toMs(t.minNs)} " +
                    "maxMs=${t.toMs(t.maxNs)} p95Ms=${t.toMs(t.p95Ns)} p99Ms=${t.toMs(t.p99Ns)}"
            )
        }
    }

    private fun looksLikeError(name: String): Boolean =
        name.contains("error") || name.contains("fail") || name.contains("underrun") ||
            name.contains("stale") || name.contains("unknown") || name.contains("duplicate") ||
            name.contains("mismatch") || name.contains("lost") || name.contains("drop")

    // ---------------------------------------------------------------------------------------------
    // Reset
    // ---------------------------------------------------------------------------------------------

    /** Clears counters, timings, the event ring and single-fire markers. Metadata/run id are preserved. */
    fun resetStats() {
        resetCounters()
        resetTimings()
        clearRing()
        firedOnce.clear()
        generationCreatedAtMs.clear()
        statsTicks.set(0L)
    }

    /** Full reset used when the process's runtime session ends. */
    fun reset() {
        stopPeriodicStats()
        periodicTasks.clear()
        sections.clear()
        resetStats()
        runIdValue = ""
        streamIdValue = ""
        generationValue.set(0L)
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private inline fun <T> guard(operation: String, block: () -> T): T? {
        return try {
            block()
        } catch (t: Throwable) {
            try {
                AppLogger.w(TAG, "diagnostics:$operation failed: ${t.message}")
            } catch (ignored: Throwable) {
            }
            null
        }
    }

    private fun fmt(value: Any?): String = when (value) {
        null -> "null"
        is Float -> String.format(java.util.Locale.US, "%.2f", value)
        is Double -> String.format(java.util.Locale.US, "%.2f", value)
        else -> value.toString()
    }

    private fun isoTime(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(ms))

    private val SESSION_COUNTER = AtomicLong(0L)

    /** Upper bound on retained generation-creation timestamps (memory safety, not a behavioural limit). */
    private const val MAX_TRACKED_GENERATIONS = 256
}

/**
 * Kept separate from the android.os.SystemClock call site so a JVM unit test that renders a snapshot does
 * not have to rely on the mocked android runtime returning a value it understands.
 */
private object DiagnosticClock {
    fun elapsedRealtimeMs(): Long = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (ignored: Throwable) {
        0L
    }
}
