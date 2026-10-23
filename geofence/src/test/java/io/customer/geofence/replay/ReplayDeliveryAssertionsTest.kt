package io.customer.geofence.replay

import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReplayDeliveryAssertionsTest {
    private val epoch = 1_000_000_000L
    private val dwell = PendingGeofenceDelivery(
        geofenceId = "B", transition = Event.GeofenceTransition.DWELL,
        timestamp = epoch + 90, userId = "synthetic", transitionId = "transition",
        visitId = "visit", enteredAt = epoch + 30,
        dwellThresholdSeconds = 60, dwellDurationSeconds = 60, detectionSource = "native"
    )

    @Test
    fun compare_whenExpectedQueueIsMissingOrDuplicated_thenFails() {
        assertNotNull(ReplayDeliveryAssertions.compare(checkpoint(), emptyList(), epoch))
        assertNotNull(ReplayDeliveryAssertions.compare(checkpoint(), listOf(dwell, dwell), epoch))
        assertNull(ReplayDeliveryAssertions.compare(checkpoint(), listOf(dwell), epoch))
    }

    @Test
    fun compare_whenNoDwellExpectedButOneQueued_thenFails() {
        assertNotNull(ReplayDeliveryAssertions.compare(checkpoint("count" to 0), listOf(dwell), epoch))
        assertNull(ReplayDeliveryAssertions.compare(checkpoint("count" to 0), emptyList(), epoch))
    }

    @Test
    fun compare_whenTimestampOrDurationWrong_thenFails() {
        assertNotNull(ReplayDeliveryAssertions.compare(checkpoint("timestampAt" to 89), listOf(dwell), epoch))
        assertNotNull(ReplayDeliveryAssertions.compare(checkpoint("dwellDurationSeconds" to 59), listOf(dwell), epoch))
        assertNotNull(ReplayDeliveryAssertions.compare(checkpoint("enteredAtAt" to 30.5), listOf(dwell), epoch))
        assertNull(ReplayDeliveryAssertions.compare(checkpoint("timestampAt" to 90, "enteredAtAt" to 30), listOf(dwell), epoch))
    }

    @Test
    fun compare_whenUnknownDurationReplacedWithZero_thenFails() {
        val expected = checkpoint("t" to "exit", "visitDurationSeconds" to null, "hasVisitId" to false, "hasEnteredAt" to false)
        val exit = dwell.copy(
            transition = Event.GeofenceTransition.EXIT,
            visitId = null,
            enteredAt = null,
            dwellThresholdSeconds = null,
            dwellDurationSeconds = null
        )
        assertNull(ReplayDeliveryAssertions.compare(expected, listOf(exit), epoch))
        assertNotNull(ReplayDeliveryAssertions.compare(expected, listOf(exit.copy(visitDurationSeconds = 0)), epoch))
        assertNull(ReplayDeliveryAssertions.compare(checkpoint("t" to "exit", "visitDurationSeconds" to 0), listOf(exit.copy(visitDurationSeconds = 0)), epoch))
    }

    @Test
    fun compare_whenAssertionMisspelledOrWrongType_thenRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ReplayDeliveryAssertions.compare(checkpoint("dwellDurationSecond" to 60), listOf(dwell), epoch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReplayDeliveryAssertions.compare(checkpoint("hasVisitId" to "true"), listOf(dwell), epoch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReplayDeliveryAssertions.compare(checkpoint("count" to -1), listOf(dwell), epoch)
        }
    }

    private fun checkpoint(vararg fields: Pair<String, Any?>) = ScenarioRecord(
        Scenario.Kind.THEN,
        90.0,
        ReplayDeliveryAssertions.EVENT,
        mapOf("id" to "B", "t" to "dwell", "count" to 1) + fields
    )
}
