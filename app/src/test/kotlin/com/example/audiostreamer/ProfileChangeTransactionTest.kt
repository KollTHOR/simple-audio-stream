package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runtime-level tests for the transmitter profile-change transaction.
 *
 * Unlike the pure callback tests in [TransportConfigTest], these tests drive the REAL
 * [StreamProfileTransactionManager] through the same callback shape that AudioCaptureService uses, backed by
 * the production [CapturePipelineInitializer] that performs the capture-pipeline initialization step.
 *
 * Covered:
 * A. successful profile change: stop old producer -> initialize -> commit -> announce -> resume
 * B. AudioRecord initialization failure: no commit, no announcement, no producer, resources released
 * C. encoder initialization failure (null and throwing): no commit, no announcement, no producer,
 *    AudioRecord + encoder resources released
 * D. concurrent profile changes stay serialized
 * E. repeated same-profile change is a genuine no-op
 */
class ProfileChangeTransactionTest {

    // ------------------------------------------------------------ fakes

    /** Stands in for android.media.AudioRecord in JVM tests. */
    private class FakeAudioRecord(val sampleRateHz: Int) {
        @Volatile var released = false
    }

    /** Stands in for the MediaCodec backed AacEncoder/OpusEncoder in JVM tests. */
    private class FakeEncoder {
        @Volatile var released = false
    }

    /**
     * Drives the real transaction callbacks exactly the way AudioCaptureService.performProfileChange does,
     * using the production [CapturePipelineInitializer] as the initialization boundary.
     */
    private class FakeCaptureRuntime {
        @Volatile var audioRecordUsable = true
        @Volatile var encoderUsable = true
        @Volatile var encoderFactoryThrows = false
        @Volatile var recordFactoryThrows = false
        @Volatile var failResume = false
        @Volatile var resumeThrows = false
        @Volatile var announceThrows = false

        val events = Collections.synchronizedList(mutableListOf<String>())
        val releasedRecords = Collections.synchronizedList(mutableListOf<FakeAudioRecord>())
        val releasedEncoders = Collections.synchronizedList(mutableListOf<FakeEncoder>())
        val announcedGenerations = Collections.synchronizedList(mutableListOf<Long>())

        /** Released resources are also accumulated here so reuse can be ruled out across resets. */
        val allReleasedRecords = Collections.synchronizedList(mutableListOf<FakeAudioRecord>())
        val allReleasedEncoders = Collections.synchronizedList(mutableListOf<FakeEncoder>())

        @Volatile var activePipeline: CapturePipeline<FakeAudioRecord, FakeEncoder, FakeEncoder>? = null
        @Volatile var producerRunning = false
        @Volatile var authoritativeGenerationDuringInit: Long? = null
        @Volatile var currentGenerationDuringInit: Long? = null
        @Volatile var generationCommittedAtAnnounce: Long? = null
        @Volatile var producerRunningAtAnnounce: Boolean? = null

        val initializer = CapturePipelineInitializer<FakeAudioRecord, FakeEncoder, FakeEncoder>(
            createAudioRecord = { cfg ->
                if (recordFactoryThrows) throw IllegalStateException("simulated AudioRecord factory failure")
                val candidate = FakeAudioRecord(cfg.sampleRateHz)
                if (audioRecordUsable) {
                    candidate
                } else {
                    // Mirrors the production probe loop: an unusable candidate is released by the factory.
                    candidate.released = true
                    releasedRecords.add(candidate)
                    allReleasedRecords.add(candidate)
                    null
                }
            },
            createOpusEncoder = { createEncoder() },
            createAacEncoder = { createEncoder() },
            releaseAudioRecord = { rec ->
                rec.released = true
                releasedRecords.add(rec)
                allReleasedRecords.add(rec)
            },
            releaseOpusEncoder = { enc ->
                enc.released = true
                releasedEncoders.add(enc)
                allReleasedEncoders.add(enc)
            },
            releaseAacEncoder = { enc ->
                enc.released = true
                releasedEncoders.add(enc)
                allReleasedEncoders.add(enc)
            }
        )

        private fun createEncoder(): FakeEncoder? {
            if (encoderFactoryThrows) throw IllegalStateException("simulated encoder initialization failure")
            val encoder = FakeEncoder()
            if (encoderUsable) return encoder
            // Mirrors createOpusEncoderOrNull()/createAacEncoderOrNull(): a half-initialized encoder is
            // released by the factory itself and reported as unavailable.
            encoder.released = true
            releasedEncoders.add(encoder)
            allReleasedEncoders.add(encoder)
            return null
        }

        // --- transaction callbacks (same shape as AudioCaptureService.performProfileChange) ---

        fun stop() {
            events.add("STOP")
            initializer.release(activePipeline)
            activePipeline = null
            producerRunning = false
        }

        fun initialize(config: NegotiatedStreamConfig, manager: StreamProfileTransactionManager): Boolean {
            events.add("INIT")
            // Observations proving that the config/generation is still NOT committed while initializing.
            authoritativeGenerationDuringInit = manager.activeTransmitterConfig?.generation
            currentGenerationDuringInit = manager.currentStreamGeneration.get()
            val pipeline = initializer.initialize(config) ?: return false
            activePipeline = pipeline
            return true
        }

        fun announce(config: NegotiatedStreamConfig, manager: StreamProfileTransactionManager) {
            events.add("ANNOUNCE")
            announcedGenerations.add(config.generation)
            generationCommittedAtAnnounce = manager.activeTransmitterConfig?.generation
            producerRunningAtAnnounce = producerRunning
            if (announceThrows) throw IllegalStateException("simulated announcement failure")
        }

        fun resume(config: NegotiatedStreamConfig): Boolean {
            events.add("RESUME")
            if (failResume || activePipeline == null) return false
            if (resumeThrows) throw IllegalStateException("simulated producer startup failure")
            producerRunning = true
            return true
        }

        /** Drops everything observed so far, e.g. releases caused by stopping a previously running stream. */
        fun resetObservations() {
            events.clear()
            releasedRecords.clear()
            releasedEncoders.clear()
            announcedGenerations.clear()
        }
    }

    private fun changeProfile(
        manager: StreamProfileTransactionManager,
        runtime: FakeCaptureRuntime,
        target: LatencyTarget
    ): ProfileChangeResult = manager.changeProfile(
        targetProfile = target,
        onStopTransmission = { runtime.stop() },
        onPublishAnnouncement = { config -> runtime.announce(config, manager) },
        onReconfigureCapture = { config -> runtime.initialize(config, manager) },
        onResumeTransmission = { config -> runtime.resume(config) }
    )

    // ------------------------------------------------------------ test A

    @Test
    fun testSuccessfulProfileChange_StopsInitializesCommitsAnnouncesAndResumes() {
        val manager = StreamProfileTransactionManager()
        val runtime = FakeCaptureRuntime()

        val initial = changeProfile(manager, runtime, LatencyTarget.BALANCED)
        assertTrue(initial is ProfileChangeResult.Applied)
        assertEquals(1L, manager.currentStreamGeneration.get())
        assertEquals(listOf("STOP", "INIT", "ANNOUNCE", "RESUME"), runtime.events.toList())

        runtime.events.clear()
        val transition = changeProfile(manager, runtime, LatencyTarget.LOW_LATENCY)

        assertTrue("Profile change must succeed", transition is ProfileChangeResult.Applied)
        val applied = transition as ProfileChangeResult.Applied
        assertEquals(2L, applied.newConfig.generation)
        assertEquals(AudioCodec.OPUS, applied.newConfig.codec)

        // Successful sequence: stop old -> initialize complete pipeline -> commit -> announce -> resume.
        assertEquals(
            listOf("STOP", "INIT", "ANNOUNCE", "RESUME"),
            runtime.events.toList()
        )

        // Literal sequencing STOP -> INIT -> COMMIT -> ANNOUNCE -> RESUME:
        // the generation is NOT committed while the pipeline is being initialized ...
        assertEquals("Config must not be committed during initialization", 1L, runtime.authoritativeGenerationDuringInit)
        assertEquals("Generation must not be consumed during initialization", 1L, runtime.currentGenerationDuringInit)
        // ... commit happens before the announcement ...
        assertEquals("Config must be committed before it is announced", 2L, runtime.generationCommittedAtAnnounce)
        // ... and the producer only starts after the announcement.
        assertEquals("Announcement must precede producer start", false, runtime.producerRunningAtAnnounce)
        assertTrue("Producer must be started as the final activation step", applied.producerStarted)

        // New pipeline exists (with its Opus encoder) and the producer is running.
        assertNotNull(runtime.activePipeline)
        assertNotNull(
            "Compressed low-latency config must carry its encoder resource",
            runtime.activePipeline?.opusEncoder
        )
        assertTrue(runtime.producerRunning)
        assertTrue(manager.isTransmissionActive)
        assertEquals(2L, manager.activeTransmitterConfig?.generation)
        assertEquals(listOf(1L, 2L), runtime.announcedGenerations.toList())
    }

    // ------------------------------------------------------------ test B

    @Test
    fun testAudioRecordInitializationFailure_AbortsWithoutCommitAnnouncementOrProducer() {
        val manager = StreamProfileTransactionManager()
        val runtime = FakeCaptureRuntime()

        changeProfile(manager, runtime, LatencyTarget.BALANCED)
        val previousConfig = manager.activeTransmitterConfig
        val previousGeneration = manager.currentStreamGeneration.get()
        val previousRecord = runtime.activePipeline!!.audioRecord
        runtime.resetObservations()

        runtime.audioRecordUsable = false
        val result = changeProfile(manager, runtime, LatencyTarget.LOW_LATENCY)

        assertTrue("Failed AudioRecord init must abort the transaction", result is ProfileChangeResult.InitializationFailed)
        val failed = result as ProfileChangeResult.InitializationFailed
        assertEquals(2L, failed.attemptedGeneration)
        assertSame(previousConfig, failed.previousConfig)

        // No new config committed: previous generation/config remain authoritative.
        assertEquals(previousGeneration, manager.currentStreamGeneration.get())
        assertSame(previousConfig, manager.activeTransmitterConfig)
        assertEquals(1L, manager.activeTransmitterConfig?.generation)
        assertFalse(manager.isTransmissionActive)

        // No announcement for the attempted generation (no phantom generation visible to receivers).
        assertTrue(runtime.announcedGenerations.isEmpty())

        // No producer was started.
        assertEquals(listOf("STOP", "INIT"), runtime.events.toList())
        assertNull(runtime.activePipeline)
        assertFalse(runtime.producerRunning)

        // Exactly the previously running stream's record (released on stop) and the unusable record created
        // by the aborted attempt (released by the factory) were released: nothing else leaked.
        assertEquals(1, runtime.releasedRecords.count { it === previousRecord })
        val abortedRecords = runtime.releasedRecords.filter { it !== previousRecord }
        assertEquals(1, abortedRecords.size)
        assertTrue(abortedRecords.single().released)
        assertTrue("No encoder must be created for an aborted record init", runtime.releasedEncoders.isEmpty())
    }

    // ------------------------------------------------------------ test C

    @Test
    fun testEncoderInitializationFailure_AbortsAndReleasesAudioRecord() {
        val manager = StreamProfileTransactionManager()
        val runtime = FakeCaptureRuntime()

        changeProfile(manager, runtime, LatencyTarget.BALANCED)
        val previousConfig = manager.activeTransmitterConfig
        val previousRecord = runtime.activePipeline!!.audioRecord
        runtime.resetObservations()

        runtime.encoderUsable = false
        val result = changeProfile(manager, runtime, LatencyTarget.LOW_LATENCY)

        assertTrue(result is ProfileChangeResult.InitializationFailed)
        assertEquals(2L, (result as ProfileChangeResult.InitializationFailed).attemptedGeneration)

        // No commit, no announcement, no producer.
        assertEquals(1L, manager.currentStreamGeneration.get())
        assertSame(previousConfig, manager.activeTransmitterConfig)
        assertFalse(manager.isTransmissionActive)
        assertTrue(runtime.announcedGenerations.isEmpty())
        assertEquals(listOf("STOP", "INIT"), runtime.events.toList())
        assertNull(runtime.activePipeline)
        assertFalse(runtime.producerRunning)

        // AudioRecord released AND the unusable encoder released. The only other record release is the
        // stopped previous stream's own record.
        assertEquals(1, runtime.releasedRecords.count { it === previousRecord })
        val abortedRecords = runtime.releasedRecords.filter { it !== previousRecord }
        assertEquals(1, abortedRecords.size)
        assertTrue(abortedRecords.single().released)
        assertEquals(1, runtime.releasedEncoders.size)
        assertTrue(runtime.releasedEncoders.single().released)
    }

    @Test
    fun testEncoderInitializationThrow_ReleasesAudioRecordWithoutLeaking() {
        val manager = StreamProfileTransactionManager()
        val runtime = FakeCaptureRuntime()

        changeProfile(manager, runtime, LatencyTarget.BALANCED)
        val previousConfig = manager.activeTransmitterConfig
        val previousRecord = runtime.activePipeline!!.audioRecord
        runtime.resetObservations()

        runtime.encoderFactoryThrows = true
        val result = changeProfile(manager, runtime, LatencyTarget.LOW_LATENCY)

        assertTrue(result is ProfileChangeResult.InitializationFailed)
        assertEquals(1L, manager.currentStreamGeneration.get())
        assertSame(previousConfig, manager.activeTransmitterConfig)
        assertFalse(manager.isTransmissionActive)
        assertTrue(runtime.announcedGenerations.isEmpty())
        assertNull(runtime.activePipeline)
        assertFalse(runtime.producerRunning)

        // The AudioRecord created before the encoder blew up must have been released.
        assertEquals(1, runtime.releasedRecords.count { it === previousRecord })
        val abortedRecords = runtime.releasedRecords.filter { it !== previousRecord }
        assertEquals(1, abortedRecords.size)
        assertTrue(abortedRecords.single().released)
    }

    // ------------------------------------------------------------ test D

    @Test
    fun testConcurrentProfileChangesRemainSerialized() {
        val manager = StreamProfileTransactionManager()
        val runtime = FakeCaptureRuntime()
        changeProfile(manager, runtime, LatencyTarget.BALANCED)
        runtime.resetObservations()

        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)

        val t1 = Thread {
            startLatch.await()
            changeProfile(manager, runtime, LatencyTarget.LOW_LATENCY)
            doneLatch.countDown()
        }
        val t2 = Thread {
            startLatch.await()
            changeProfile(manager, runtime, LatencyTarget.RELIABLE)
            doneLatch.countDown()
        }

        t1.start()
        t2.start()
        startLatch.countDown()
        assertTrue("Both concurrent transactions must finish", doneLatch.await(5, TimeUnit.SECONDS))
        t1.join()
        t2.join()

        // Exactly one transaction at a time: the two transaction event groups never interleave.
        assertEquals(
            listOf("STOP", "INIT", "ANNOUNCE", "RESUME", "STOP", "INIT", "ANNOUNCE", "RESUME"),
            runtime.events.toList()
        )
        assertEquals(3L, manager.currentStreamGeneration.get())
        assertEquals(listOf(2L, 3L), runtime.announcedGenerations.toList())
        assertEquals(3L, manager.activeTransmitterConfig?.generation)
        assertTrue(manager.isTransmissionActive)
        assertTrue(runtime.producerRunning)
        assertNotNull(runtime.activePipeline)
    }

    // ------------------------------------------------------------ test E

    @Test
    fun testRepeatedSameProfileChangeIsNoOp() {
        val manager = StreamProfileTransactionManager()
        val runtime = FakeCaptureRuntime()

        changeProfile(manager, runtime, LatencyTarget.BALANCED)
        val pipelineBefore = runtime.activePipeline
        runtime.resetObservations()

        val repeated = changeProfile(manager, runtime, LatencyTarget.BALANCED)

        assertTrue(repeated is ProfileChangeResult.IgnoredSameProfile)
        val ignored = repeated as ProfileChangeResult.IgnoredSameProfile
        assertEquals(LatencyTarget.BALANCED, ignored.activeProfile)
        assertEquals(1L, ignored.generation)

        assertEquals("Generation must not advance", 1L, manager.currentStreamGeneration.get())
        assertTrue("No transaction step may run for a no-op", runtime.events.isEmpty())
        assertTrue("No announcement may be emitted for a no-op", runtime.announcedGenerations.isEmpty())
        assertSame("Running pipeline must not be touched", pipelineBefore, runtime.activePipeline)
        assertTrue(runtime.producerRunning)
        assertTrue(manager.isTransmissionActive)
    }

    // ------------------------------------------------------------ producer start failure

    @Test
    fun testProducerStartFailureLeavesCleanStoppedState() {
        val manager = StreamProfileTransactionManager()
        val runtime = FakeCaptureRuntime()
        runtime.failResume = true

        val result = changeProfile(manager, runtime, LatencyTarget.BALANCED)

        assertTrue(result is ProfileChangeResult.Applied)
        assertFalse(
            "Applied must report that the producer never started",
            (result as ProfileChangeResult.Applied).producerStarted
        )
        assertFalse("Transmission must not be reported active when no producer started", manager.isTransmissionActive)
        // The initialized pipeline is torn back down instead of being left dangling without a producer.
        assertNull(runtime.activePipeline)
        assertFalse(runtime.producerRunning)
        assertEquals(listOf("STOP", "INIT", "ANNOUNCE", "RESUME", "STOP"), runtime.events.toList())
        assertEquals(1, runtime.releasedRecords.size)
        assertTrue(runtime.releasedRecords.single().released)
    }

    @Test
    fun testProducerStartException_LeavesCleanStoppedStateWithoutLeaks() {
        val manager = StreamProfileTransactionManager()
        val runtime = FakeCaptureRuntime()
        runtime.resumeThrows = true

        val result = changeProfile(manager, runtime, LatencyTarget.BALANCED)

        assertTrue(result is ProfileChangeResult.Applied)
        val applied = result as ProfileChangeResult.Applied
        assertEquals(1L, applied.newConfig.generation)
        assertFalse("Applied must report that the producer never started", applied.producerStarted)
        assertFalse(manager.isTransmissionActive)
        assertNull(runtime.activePipeline)
        assertFalse(runtime.producerRunning)
        assertEquals(listOf("STOP", "INIT", "ANNOUNCE", "RESUME", "STOP"), runtime.events.toList())
        // The committed generation was announced, so it stays authoritative; no resource may stay allocated.
        assertEquals(1L, manager.activeTransmitterConfig?.generation)
        assertEquals(listOf(1L), runtime.announcedGenerations.toList())
        assertEquals(1, runtime.releasedRecords.size)
        assertTrue(runtime.releasedRecords.single().released)
    }

    // ------------------------------------------------------------ announcement failure

    @Test
    fun testAnnouncementFailure_ReleasesPipelineAndLeavesConsistentStoppedState() {
        val manager = StreamProfileTransactionManager()
        val runtime = FakeCaptureRuntime()

        changeProfile(manager, runtime, LatencyTarget.BALANCED)
        val previousConfig = manager.activeTransmitterConfig
        val previousRecord = runtime.activePipeline!!.audioRecord
        runtime.resetObservations()

        runtime.announceThrows = true
        val result = changeProfile(manager, runtime, LatencyTarget.LOW_LATENCY)

        // The failure is reported, never swallowed and never thrown out of the transaction.
        assertTrue(result is ProfileChangeResult.AnnouncementFailed)
        val failed = result as ProfileChangeResult.AnnouncementFailed
        assertEquals(2L, failed.abandonedGeneration)
        assertTrue("Announcement exception must be preserved", failed.cause is IllegalStateException)
        assertSame(previousConfig, failed.previousConfig)

        // No producer, no allocated capture resources, transmission reported inactive.
        assertFalse(manager.isTransmissionActive)
        assertFalse(runtime.producerRunning)
        assertNull(runtime.activePipeline)

        // The old stream's record was released by the stop step, the abandoned generation's record and
        // encoder by the post-commit abort: nothing stays allocated.
        assertEquals(1, runtime.releasedRecords.count { it === previousRecord })
        val abandonedRecords = runtime.releasedRecords.filter { it !== previousRecord }
        assertEquals(1, abandonedRecords.size)
        assertTrue(abandonedRecords.single().released)
        assertEquals(1, runtime.releasedEncoders.size)
        assertTrue(runtime.releasedEncoders.single().released)

        // Authoritative state is internally consistent: the generation stays consumed (never reused) while
        // the advertisable config rolls back to the last fully committed AND announced one.
        assertEquals("Abandoned generation must stay consumed", 2L, manager.currentStreamGeneration.get())
        assertSame(previousConfig, manager.activeTransmitterConfig)
        assertEquals(1L, manager.activeTransmitterConfig?.generation)

        // Event sequence: old producer stopped -> pipeline initialized -> announcement attempted (failed) ->
        // abandoned pipeline released. Producer startup is never attempted for the abandoned generation.
        assertEquals(listOf("STOP", "INIT", "ANNOUNCE", "STOP"), runtime.events.toList())
        assertFalse("Producer startup must not be attempted", runtime.events.contains("RESUME"))

        // The announcement was attempted (a partial burst may have reached receivers), which is exactly why
        // the abandoned generation must stay consumed instead of being rolled back for reuse.
        assertEquals(listOf(2L), runtime.announcedGenerations.toList())
    }

    // ------------------------------------------------------------ recovery after failures

    @Test
    fun testFailedTransactions_NeverReusePipelineAndGenerationsStayMonotonic() {
        val manager = StreamProfileTransactionManager()
        val runtime = FakeCaptureRuntime()

        changeProfile(manager, runtime, LatencyTarget.BALANCED)
        assertEquals(1L, manager.currentStreamGeneration.get())

        // (1) Announcement failure: generation 2 is consumed, config rolled back, pipeline released.
        runtime.announceThrows = true
        assertTrue(changeProfile(manager, runtime, LatencyTarget.LOW_LATENCY) is ProfileChangeResult.AnnouncementFailed)
        runtime.announceThrows = false
        runtime.resetObservations()

        // Because the authoritative config was rolled back, repeating the same profile is a real transaction.
        val retryResult = changeProfile(manager, runtime, LatencyTarget.LOW_LATENCY)
        assertTrue(retryResult is ProfileChangeResult.Applied)
        val retry = retryResult as ProfileChangeResult.Applied
        assertEquals("Generation must advance monotonically", 3L, retry.newConfig.generation)
        assertTrue(retry.producerStarted)
        assertEquals(3L, manager.currentStreamGeneration.get())
        val retryRecord = runtime.activePipeline!!.audioRecord
        assertFalse("Recovered pipeline must not be a released resource", retryRecord.released)
        assertFalse(
            "Recovered pipeline must not reuse any previously released resource",
            runtime.allReleasedRecords.any { it === retryRecord }
        )

        // (2) Producer startup failure: generation 4 is committed+announced, then fully released.
        runtime.resetObservations()
        runtime.failResume = true
        val failedResumeResult = changeProfile(manager, runtime, LatencyTarget.RELIABLE)
        assertTrue(failedResumeResult is ProfileChangeResult.Applied)
        val failedResume = failedResumeResult as ProfileChangeResult.Applied
        assertEquals(4L, failedResume.newConfig.generation)
        assertFalse(failedResume.producerStarted)
        assertFalse(manager.isTransmissionActive)
        assertNull(runtime.activePipeline)
        assertFalse(runtime.producerRunning)
        assertEquals("Both the stopped and the abandoned pipeline must be released", 2, runtime.releasedRecords.size)
        assertTrue(runtime.releasedRecords.all { it.released })
        assertEquals(4L, manager.activeTransmitterConfig?.generation)
        runtime.failResume = false
        runtime.resetObservations()

        // (3) The next profile change initializes and starts a fresh pipeline on a strictly newer generation.
        val recoveredResult = changeProfile(manager, runtime, LatencyTarget.LOW_LATENCY)
        assertTrue(recoveredResult is ProfileChangeResult.Applied)
        val recovered = recoveredResult as ProfileChangeResult.Applied
        assertEquals(5L, recovered.newConfig.generation)
        assertTrue(recovered.producerStarted)
        assertTrue(manager.isTransmissionActive)
        assertTrue(runtime.producerRunning)
        val recoveredRecord = runtime.activePipeline!!.audioRecord
        assertFalse(recoveredRecord.released)
        assertFalse(
            "Recovered pipeline must not reuse any previously released resource",
            runtime.allReleasedRecords.any { it === recoveredRecord }
        )
        assertEquals(5L, manager.activeTransmitterConfig?.generation)
    }

    // ------------------------------------------------------------ initializer unit tests

    @Test
    fun testInitializer_PcmConfigNeedsNoEncoderResources() {
        val runtime = FakeCaptureRuntime()

        val pipeline = runtime.initializer.initialize(pcm24Config())
        assertTrue("PCM pipeline must initialize without encoders", pipeline != null)
        assertEquals(48000, pipeline!!.audioRecord.sampleRateHz)
        assertNull(pipeline.opusEncoder)
        assertNull(pipeline.aacEncoder)
        assertTrue("No encoder may be created for an uncompressed config", runtime.releasedEncoders.isEmpty())

        runtime.initializer.release(pipeline)
        assertEquals(1, runtime.releasedRecords.size)
        assertTrue(runtime.releasedRecords.single().released)
    }

    @Test
    fun testInitializer_OpusConfigCarriesOnlyItsEncoder() {
        val runtime = FakeCaptureRuntime()

        val pipeline = runtime.initializer.initialize(opusConfig())
        assertTrue(pipeline != null)
        assertNotNull(pipeline!!.opusEncoder)
        assertNull(pipeline.aacEncoder)
        assertTrue(runtime.releasedEncoders.isEmpty())
    }

    @Test
    fun testInitializer_AacConfigCarriesOnlyItsEncoder() {
        val runtime = FakeCaptureRuntime()

        val pipeline = runtime.initializer.initialize(aacConfig())
        assertTrue(pipeline != null)
        assertNull(pipeline!!.opusEncoder)
        assertNotNull(pipeline.aacEncoder)
        assertTrue(runtime.releasedEncoders.isEmpty())
    }

    @Test
    fun testInitializer_ReturnsNullAndCreatesNoEncoderWhenRecordUnavailable() {
        val runtime = FakeCaptureRuntime()
        runtime.audioRecordUsable = false

        assertNull(runtime.initializer.initialize(opusConfig()))
        assertEquals(1, runtime.releasedRecords.size)
        assertTrue(runtime.releasedRecords.single().released)
        assertTrue(runtime.releasedEncoders.isEmpty())
    }

    @Test
    fun testInitializer_ReleasesAudioRecordWhenEncoderUnavailable() {
        val runtime = FakeCaptureRuntime()
        runtime.encoderUsable = false

        assertNull(runtime.initializer.initialize(opusConfig()))
        assertEquals(1, runtime.releasedRecords.size)
        assertTrue(runtime.releasedRecords.single().released)
        assertEquals(1, runtime.releasedEncoders.size)
        assertTrue(runtime.releasedEncoders.single().released)
    }

    @Test
    fun testInitializer_ReleasesAudioRecordWhenEncoderThrows() {
        val runtime = FakeCaptureRuntime()
        runtime.encoderFactoryThrows = true

        assertNull(runtime.initializer.initialize(opusConfig()))
        assertEquals("AudioRecord must not leak when encoder init throws", 1, runtime.releasedRecords.size)
        assertTrue(runtime.releasedRecords.single().released)
        assertTrue(runtime.releasedEncoders.isEmpty())
    }

    @Test
    fun testInitializer_ReturnsNullWhenRecordFactoryThrows() {
        val runtime = FakeCaptureRuntime()
        runtime.recordFactoryThrows = true

        assertNull(runtime.initializer.initialize(opusConfig()))
        assertTrue(runtime.releasedRecords.isEmpty())
        assertTrue(runtime.releasedEncoders.isEmpty())
    }

    // ------------------------------------------------------------ helpers

    private fun pcm24Config(generation: Long = 1L) = NegotiatedStreamConfig(
        audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
        codec = AudioCodec.PCM,
        transportProfile = TransportProfile.create(LatencyTarget.BALANCED),
        generation = generation
    )

    private fun opusConfig(generation: Long = 1L) = NegotiatedStreamConfig(
        audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_16),
        codec = AudioCodec.OPUS,
        transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true),
        generation = generation
    )

    private fun aacConfig(generation: Long = 1L) = NegotiatedStreamConfig(
        audioFormat = AudioFormatConfig(AudioSampleRate.RATE_44100, AudioBitDepth.BIT_16),
        codec = AudioCodec.AAC,
        transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true),
        generation = generation
    )
}
