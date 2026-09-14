package com.example.audiostreamer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the HAT runtime diagnostics facility.
 *
 * Deliberately does not construct any Android audio implementation: HatDiagnostics only touches Android
 * classes for optional metadata (Build/SystemClock/Debug) and logging, all of which are tolerated with the
 * unit-test `returnDefaultValues` runtime and never throw out of the diagnostics API.
 */
class HatDiagnosticsTest {

    @Before
    fun setUp() {
        HatDiagnostics.reset()
        HatDiagnostics.configure(HatDiagnostics.Config())
    }

    @After
    fun tearDown() {
        HatDiagnostics.reset()
    }

    private fun names(): List<String> = HatDiagnostics.recentEvents(1000).map { it.name }

    private fun eventNamed(name: String): HatDiagnostics.Event? =
        HatDiagnostics.recentEvents(1000).lastOrNull { it.name == name }

    // -------------------------------------------------------------------------
    // Counter aggregation
    // -------------------------------------------------------------------------

    @Test
    fun countersAggregateAndReturnUpdatedValue() {
        assertEquals(0L, HatDiagnostics.counter("tx_packets"))
        HatDiagnostics.increment("tx_packets")
        HatDiagnostics.increment("tx_packets")
        HatDiagnostics.increment("tx_packets", 5L)
        assertEquals(7L, HatDiagnostics.counter("tx_packets"))

        val newValue = HatDiagnostics.increment("tx_packets", 3L)
        assertEquals(10L, newValue)
        assertEquals(10L, HatDiagnostics.counter("tx_packets"))
    }

    @Test
    fun countersAreIsolatedPerName() {
        HatDiagnostics.increment("a", 2L)
        HatDiagnostics.increment("b", 9L)
        assertEquals(2L, HatDiagnostics.counter("a"))
        assertEquals(9L, HatDiagnostics.counter("b"))
        assertEquals(0L, HatDiagnostics.counter("missing"))
        assertEquals(mapOf("a" to 2L, "b" to 9L), HatDiagnostics.counterSnapshot())
    }

    // -------------------------------------------------------------------------
    // Stats reset
    // -------------------------------------------------------------------------

    @Test
    fun resetStatsClearsCountersTimingsAndEvents() {
        HatDiagnostics.increment("x", 4L)
        HatDiagnostics.recordTime("captureRead", 1_000_000L)
        HatDiagnostics.info("SOME_EVENT")

        HatDiagnostics.resetStats()

        assertEquals(0L, HatDiagnostics.counter("x"))
        assertEquals(0, HatDiagnostics.ringSize())
        val timing = HatDiagnostics.timing("captureRead")
        assertEquals(0L, timing?.snapshot()?.count)
        assertEquals(0L, HatDiagnostics.statsTicks())
    }

    @Test
    fun resetClearsRunIdentityAndSections() {
        HatDiagnostics.startRun("tx:1.2.3.4:5000")
        HatDiagnostics.registerSection("CAPTURE") { mapOf("readCalls" to 1) }
        assertTrue(HatDiagnostics.runId().isNotEmpty())

        HatDiagnostics.reset()

        assertEquals("", HatDiagnostics.runId())
        assertEquals("", HatDiagnostics.streamId())
        assertEquals(0L, HatDiagnostics.generation())
        assertFalse(HatDiagnostics.snapshot().contains("readCalls=1"))
    }

    // -------------------------------------------------------------------------
    // Ring buffer boundedness
    // -------------------------------------------------------------------------

    @Test
    fun eventRingBufferIsBoundedAndKeepsNewestEvents() {
        HatDiagnostics.configure(HatDiagnostics.Config(ringCapacity = 16, snapshotOnError = false))

        repeat(200) { HatDiagnostics.info("EVENT_$it") }

        assertEquals(16, HatDiagnostics.ringSize())
        val events = HatDiagnostics.recentEvents(1000)
        assertEquals(16, events.size)
        assertEquals("EVENT_199", events.last().name)
        assertEquals("EVENT_184", events.first().name)
    }

    @Test
    fun ringCapacityIsClampedToSafeBounds() {
        HatDiagnostics.setRingCapacity(1)
        assertTrue(HatDiagnostics.config.ringCapacity >= 16)

        HatDiagnostics.setRingCapacity(1_000_000)
        assertTrue(HatDiagnostics.config.ringCapacity <= 4096)
    }

    @Test
    fun recentEventsLimitReturnsTailOnly() {
        HatDiagnostics.configure(HatDiagnostics.Config(ringCapacity = 64, snapshotOnError = false))
        repeat(20) { HatDiagnostics.info("E$it") }

        val tail = HatDiagnostics.recentEvents(5)
        assertEquals(5, tail.size)
        assertEquals("E15", tail.first().name)
        assertEquals("E19", tail.last().name)
    }

    // -------------------------------------------------------------------------
    // Concurrent event recording
    // -------------------------------------------------------------------------

    @Test
    fun concurrentEventRecordingStaysBoundedAndNeverThrows() {
        HatDiagnostics.configure(HatDiagnostics.Config(ringCapacity = 64, snapshotOnError = false))
        val threads = 8
        val perThread = 250
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = AtomicInteger(0)

        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    try {
                        HatDiagnostics.info("T$t-E$i", mapOf("thread" to t, "index" to i))
                        HatDiagnostics.increment("concurrent", 1L)
                    } catch (e: Throwable) {
                        failures.incrementAndGet()
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("worker pool must finish", pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(0, failures.get())
        assertEquals(threads * perThread.toLong(), HatDiagnostics.counter("concurrent"))
        assertTrue("ring must stay bounded", HatDiagnostics.ringSize() <= 64)
    }

    @Test
    fun concurrentCounterAndTimingUpdatesAreLossless() {
        val threads = 4
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                repeat(perThread) { HatDiagnostics.recordTime("send", 1_000L) }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals((threads * perThread).toLong(), HatDiagnostics.timing("send")?.snapshot()?.count)
    }

    // -------------------------------------------------------------------------
    // Timing aggregates
    // -------------------------------------------------------------------------

    @Test
    fun timingAggregatesCountMinMaxAvgAndPercentiles() {
        HatDiagnostics.recordTime("encode", 1_000L)
        HatDiagnostics.recordTime("encode", 3_000L)
        HatDiagnostics.recordTime("encode", 2_000L)

        val snapshot = HatDiagnostics.timing("encode")!!.snapshot()
        assertEquals(3L, snapshot.count)
        assertEquals(2_000L, snapshot.avgNs)
        assertEquals(1_000L, snapshot.minNs)
        assertEquals(3_000L, snapshot.maxNs)
        assertTrue(snapshot.p95Ns in 2_000L..3_000L)
        assertTrue(snapshot.p99Ns in 2_000L..3_000L)
    }

    @Test
    fun negativeDurationsAreIgnored() {
        HatDiagnostics.recordTime("decode", -5L)
        assertEquals(0L, HatDiagnostics.timing("decode")?.snapshot()?.count)
    }

    // -------------------------------------------------------------------------
    // Diagnostic configuration
    // -------------------------------------------------------------------------

    @Test
    fun levelFiltersLowerSeverities() {
        HatDiagnostics.configure(HatDiagnostics.Config(level = HatDiagnostics.Level.WARN, snapshotOnError = false))

        HatDiagnostics.info("HIDDEN_INFO")
        HatDiagnostics.debug("HIDDEN_DEBUG")
        HatDiagnostics.stats("HIDDEN_STATS")
        assertEquals(0, HatDiagnostics.ringSize())

        HatDiagnostics.warn("SHOWN_WARN")
        HatDiagnostics.error("SHOWN_ERROR", tr = null)
        assertEquals(listOf("SHOWN_WARN", "SHOWN_ERROR"), names())
    }

    @Test
    fun levelOffDisablesAllDiagnostics() {
        HatDiagnostics.configure(HatDiagnostics.Config(level = HatDiagnostics.Level.OFF, snapshotOnError = false))
        HatDiagnostics.error("NEVER_EMITTED")
        HatDiagnostics.info("NEVER_EMITTED_2")
        assertEquals(0, HatDiagnostics.ringSize())
    }

    @Test
    fun packetLoggingIsDisabledByDefaultAndOptIn() {
        HatDiagnostics.configure(HatDiagnostics.Config(snapshotOnError = false))
        assertFalse(HatDiagnostics.config.packetLoggingEnabled)
        assertFalse(HatDiagnostics.isPacketLoggingEnabled())

        HatDiagnostics.packet("PKT_AUDIO") { mapOf("seq" to 1) }
        assertEquals(0, HatDiagnostics.ringSize())

        HatDiagnostics.setPacketLoggingEnabled(true)
        HatDiagnostics.packet("PKT_AUDIO") { mapOf("seq" to 1) }
        assertEquals(listOf("PKT_AUDIO"), names())
    }

    @Test
    fun packetLoggingDoesNotEvaluateFieldsWhenDisabled() {
        HatDiagnostics.configure(HatDiagnostics.Config(packetLoggingEnabled = false, snapshotOnError = false))
        var built = false
        HatDiagnostics.packet("PKT") {
            built = true
            emptyMap()
        }
        assertFalse("field supplier must not run while disabled", built)
    }

    @Test
    fun statsIntervalIsClamped() {
        HatDiagnostics.setStatsIntervalMs(1L)
        assertTrue(HatDiagnostics.config.statsIntervalMs >= 200L)
        HatDiagnostics.setStatsIntervalMs(10_000_000L)
        assertTrue(HatDiagnostics.config.statsIntervalMs <= 60_000L)
    }

    @Test
    fun periodicStatsTaskRunsAndIsSafelyIsolatedFromFailures() {
        val ticks = AtomicInteger(0)
        HatDiagnostics.registerPeriodicTask("failing") { throw IllegalStateException("boom") }
        HatDiagnostics.registerPeriodicTask("counting") { ticks.incrementAndGet() }

        HatDiagnostics.runStatsTick()

        assertEquals(1, ticks.get())
        assertEquals(1L, HatDiagnostics.statsTicks())
        // The failing task must not have prevented the runtime stats event from being emitted.
        assertNotNull(eventNamed("RUNTIME_STATS"))

        HatDiagnostics.unregisterPeriodicTask("counting")
        HatDiagnostics.unregisterPeriodicTask("failing")
        HatDiagnostics.runStatsTick()
        assertEquals(1, ticks.get())
    }

    // -------------------------------------------------------------------------
    // Snapshot generation
    // -------------------------------------------------------------------------

    @Test
    fun snapshotContainsAllExpectedSectionsAndRegisteredValues() {
        HatDiagnostics.setMetadata(
            HatDiagnostics.Metadata(
                appVersion = "1.8.6",
                deviceModel = "TestDevice",
                manufacturer = "TestVendor",
                androidVersion = "15",
                apiLevel = 35,
                audioOutputDevice = "AudioTrack",
                audioSampleRate = 48000,
                audioChannelConfig = "stereo",
                networkTransport = "UDP"
            )
        )
        HatDiagnostics.startRun("tx:192.168.0.1:5000", runId = "run-abc")
        HatDiagnostics.setGeneration(7L)
        HatDiagnostics.registerSection("CONFIG") { mapOf("generation" to 7L, "profile" to "LOW_LATENCY") }
        HatDiagnostics.registerSection("JITTER") { mapOf("bufferPackets" to 3, "jitterMs" to 1.25) }
        HatDiagnostics.increment("rx_generation_mismatch", 2L)
        HatDiagnostics.info("CONFIG_COMMITTED", mapOf("profile" to "LOW_LATENCY"))
        HatDiagnostics.recordTime("captureRead", 2_000_000L)

        val snapshot = HatDiagnostics.snapshot()

        listOf(
            "=== HAT DIAGNOSTIC SNAPSHOT ===", "[RUN]", "[APP]", "[DEVICE]", "[ANDROID]", "[NETWORK]",
            "[CONFIG]", "[JITTER]", "[AUDIO LOOP TIMING]", "[ERROR COUNTERS]", "[COUNTERS]", "[RECENT EVENTS]"
        ).forEach { section ->
            assertTrue("snapshot must contain $section", snapshot.contains(section))
        }
        assertTrue(snapshot.contains("runId=run-abc"))
        assertTrue(snapshot.contains("streamId=tx:192.168.0.1:5000"))
        assertTrue(snapshot.contains("generation=7"))
        assertTrue(snapshot.contains("model=TestDevice"))
        assertTrue(snapshot.contains("appVersion=1.8.6"))
        assertTrue(snapshot.contains("audioOutputDevice=AudioTrack"))
        assertTrue(snapshot.contains("profile=LOW_LATENCY"))
        assertTrue(snapshot.contains("jitterMs=1.25"))
        assertTrue(snapshot.contains("rx_generation_mismatch=2"))
        assertTrue(snapshot.contains("captureRead: count=1"))
        assertTrue(snapshot.contains("CONFIG_COMMITTED"))
    }

    @Test
    fun snapshotSurvivesFailingSectionProvider() {
        HatDiagnostics.registerSection("TX") { throw RuntimeException("provider exploded") }
        HatDiagnostics.registerSection("CAPTURE") { mapOf("readCalls" to 11) }

        val snapshot = HatDiagnostics.snapshot()

        // The failing TX provider must not abort the snapshot; other sections still render.
        assertTrue(snapshot.contains("readCalls=11"))
        assertTrue(snapshot.contains("[COUNTERS]"))
        assertTrue(snapshot.contains("=== HAT DIAGNOSTIC SNAPSHOT ==="))
    }

    @Test
    fun snapshotWithoutMetadataStillRenders() {
        val snapshot = HatDiagnostics.snapshot()
        assertTrue(snapshot.contains("appVersion=null"))
        assertTrue(snapshot.contains("[DEVICE]"))
    }

    // -------------------------------------------------------------------------
    // Generation lifecycle timing
    // -------------------------------------------------------------------------

    @Test
    fun generationLifecycleEventsCarryIdentifiersAndTiming() {
        HatDiagnostics.startRun("tx:10.0.0.1:5000", runId = "run-gen")
        HatDiagnostics.setGeneration(3L)
        Thread.sleep(5L)
        HatDiagnostics.lifecycle("GENERATION_FIRST_TX", 3L)

        val firstTx = eventNamed("GENERATION_FIRST_TX")
        assertNotNull("GENERATION_FIRST_TX must be recorded", firstTx)
        assertEquals(3L, firstTx!!.generation)
        assertEquals("run-gen", firstTx.runId)
        assertEquals("tx:10.0.0.1:5000", firstTx.streamId)
        val elapsed = firstTx.fields?.get("sinceCreatedMs") as? Long
        assertNotNull("lifecycle marker must carry elapsed time since generation creation", elapsed)
        assertTrue("elapsed must be >= 0", elapsed!! >= 0L)
        assertEquals(3L, firstTx.fields?.get("generation"))
    }

    @Test
    fun setGenerationOnlyEmitsOnChange() {
        HatDiagnostics.startRun("s")
        HatDiagnostics.setGeneration(2L)
        HatDiagnostics.setGeneration(2L)
        HatDiagnostics.setGeneration(4L)

        val changes = HatDiagnostics.recentEvents(50).filter { it.name == "GENERATION_CHANGED" }
        assertEquals(2, changes.size)
        assertEquals(4L, HatDiagnostics.generation())
        assertEquals(0L, changes.first().fields?.get("previous"))
        assertEquals(2L, changes.first().fields?.get("current"))
        assertEquals(2L, changes.last().fields?.get("previous"))
        assertEquals(4L, changes.last().fields?.get("current"))
    }

    @Test
    fun firstTimeFiresOncePerRun() {
        HatDiagnostics.startRun("s")
        assertTrue(HatDiagnostics.firstTime("first_rx_5"))
        assertFalse(HatDiagnostics.firstTime("first_rx_5"))

        HatDiagnostics.startRun("s2")
        assertTrue("marker must reset for a new run", HatDiagnostics.firstTime("first_rx_5"))
    }

    @Test
    fun runIdIsUniqueAndCarriesDeviceHint() {
        val a = HatDiagnostics.newRunId("Pixel")
        val b = HatDiagnostics.newRunId("Pixel")
        assertTrue(a.contains("Pixel"))
        assertTrue(b.contains("Pixel"))
        assertFalse("run ids must be unique", a == b)
    }

    // -------------------------------------------------------------------------
    // Error snapshot trigger
    // -------------------------------------------------------------------------

    @Test
    fun errorEmitsEventAndSnapshotContextWithoutEscaping() {
        HatDiagnostics.configure(
            HatDiagnostics.Config(snapshotOnError = true, errorSnapshotMinIntervalMs = 0L)
        )
        HatDiagnostics.startRun("rx:5000")
        HatDiagnostics.increment("rx_decode_errors", 3L)

        HatDiagnostics.error("PLAYBACK_ERROR", mapOf("thread" to "AudioPlaybackThread"), RuntimeException("x"))

        val error = eventNamed("PLAYBACK_ERROR")
        assertNotNull(error)
        assertEquals(HatDiagnostics.Severity.ERROR, error!!.severity)
        assertNotNull(error.throwable)
    }

    @Test
    fun errorSnapshotIsRateLimited() {
        HatDiagnostics.configure(
            HatDiagnostics.Config(snapshotOnError = true, errorSnapshotMinIntervalMs = 60_000L)
        )
        // Repeated snapshot requests inside the rate-limit window must be silently coalesced, never throw.
        repeat(5) { HatDiagnostics.snapshotContext("GENERATION_MISMATCH", mapOf("count" to it)) }
        HatDiagnostics.error("NETWORK_FAILURE", tr = RuntimeException("n"))
        assertNotNull(eventNamed("NETWORK_FAILURE"))
    }

    @Test
    fun snapshotContextIsNoOpWhenDisabled() {
        HatDiagnostics.configure(HatDiagnostics.Config(snapshotOnError = false))
        HatDiagnostics.snapshotContext("PLAYBACK_UNDERRUN", mapOf("delta" to 1))
        assertEquals(0, HatDiagnostics.ringSize())
    }

    @Test
    fun severityOrderingMatchesSpecifiedLevels() {
        assertEquals(1, HatDiagnostics.Level.ERROR.rank)
        assertEquals(2, HatDiagnostics.Level.WARN.rank)
        assertEquals(3, HatDiagnostics.Level.INFO.rank)
        assertEquals(4, HatDiagnostics.Level.DEBUG.rank)
        assertEquals(5, HatDiagnostics.Level.STATS.rank)
        assertEquals(0, HatDiagnostics.Level.OFF.rank)
    }
}
