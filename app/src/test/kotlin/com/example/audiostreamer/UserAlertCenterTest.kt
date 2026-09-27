package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Headless coverage for the alert bus. [UserAlertCenter.uiVisible] is forced true so the Android
 * notification path is never taken (no app context in JVM tests) and only flow semantics are
 * exercised.
 */
class UserAlertCenterTest {

    @Before
    fun setUp() {
        UserAlertCenter.reset()
        UserAlertCenter.uiVisible = true
    }

    @Test
    fun errorPublishBecomesTheLatestAlert() {
        val marker = "test-error-${System.nanoTime()}"
        UserAlertCenter.error(marker)
        val alert = UserAlertCenter.latest.value
        assertNotNull("error should surface on the bus", alert)
        assertEquals(AlertSeverity.ERROR, alert!!.severity)
        assertEquals(marker, alert.message)
    }

    @Test
    fun loggerBridgeProducesErrors() {
        val marker = "test-bridge-${System.nanoTime()}"
        UserAlertCenter.publishFromLogger("SomeService: $marker")
        val alert = UserAlertCenter.latest.value
        assertNotNull(alert)
        assertEquals(AlertSeverity.ERROR, alert!!.severity)
        assertEquals("SomeService: $marker", alert.message)
    }

    @Test
    fun diagnosticsInternalIdsAreStripped() {
        val marker = "test-noise-${System.nanoTime()}"
        UserAlertCenter.publishFromLogger("HatDiagnostics: THREAD_FAILURE run=r123 stream=s45 gen=7 detail=$marker")
        val message = UserAlertCenter.latest.value!!.message
        assertEquals("HatDiagnostics: THREAD_FAILURE detail=$marker", message)
    }

    @Test
    fun identicalMessageWithinWindowDoesNotReplaceTheAlert() {
        val marker = "test-dup-${System.nanoTime()}"
        UserAlertCenter.error(marker)
        val first = UserAlertCenter.latest.value!!

        UserAlertCenter.error(marker)
        val afterDup = UserAlertCenter.latest.value!!
        assertEquals("duplicate must not mint a new alert", first.id, afterDup.id)
    }

    @Test
    fun consumeClearsOnlyTheMatchingAlert() {
        val marker = "test-consume-${System.nanoTime()}"
        UserAlertCenter.warn(marker)
        val alert = UserAlertCenter.latest.value!!

        UserAlertCenter.consume(UserAlert(id = alert.id + 9_999, severity = AlertSeverity.WARN, message = "other", timestampMs = 0L))
        assertEquals("foreign id must not clear the current alert", alert.id, UserAlertCenter.latest.value?.id)

        UserAlertCenter.consume(alert)
        assertNull(UserAlertCenter.latest.value)
    }

    @Test
    fun blankMessagesAreIgnored() {
        val marker = "test-blank-${System.nanoTime()}"
        UserAlertCenter.error(marker)
        val before = UserAlertCenter.latest.value

        UserAlertCenter.error("   ")
        assertEquals(before, UserAlertCenter.latest.value)
    }
}
