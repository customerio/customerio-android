package io.customer.location.provider

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

/**
 * The mapping the geofence freshness gate depends on. A dropped or mis-scaled age reads as
 * "current" downstream, which opens the gate rather than closing it, so the conversion is pinned
 * here instead of being left to `any()` at the call sites.
 */
class FusedLocationProviderTest {

    @Test
    fun toSnapshot_givenElapsedRealtimeNanos_expectMilliseconds() {
        location(elapsedNanos = 90_000_000_000L).toSnapshot()
            .fixElapsedRealtimeMillis shouldBeEqualTo 90_000L
    }

    @Test
    fun toSnapshot_givenSubMillisecondNanos_expectTruncatedNotRounded() {
        location(elapsedNanos = 1_999_999L).toSnapshot()
            .fixElapsedRealtimeMillis shouldBeEqualTo 1L
    }

    @Test
    fun toSnapshot_givenUnreportedElapsedRealtime_expectNull() {
        // Zero is the platform's "not set". Reporting 0 ms would age the fix from boot instead.
        location(elapsedNanos = 0L).toSnapshot().fixElapsedRealtimeMillis.shouldBeNull()
    }

    @Test
    fun toSnapshot_expectCoordinatesAndAccuracyCarried() {
        val snapshot = location(elapsedNanos = 5_000_000L).toSnapshot()

        snapshot.latitude shouldBeEqualTo 37.7749
        snapshot.longitude shouldBeEqualTo -122.4194
        snapshot.horizontalAccuracy shouldBeEqualTo 12.0
    }

    @Test
    fun toSnapshot_givenUnreportedAccuracy_expectNullNotZero() {
        // The platform reads 0 when unset; carried as 0, any fix would look precise enough to prove outside.
        location(elapsedNanos = 5_000_000L, accuracyMeters = 0f, reportsAccuracy = false).toSnapshot()
            .horizontalAccuracy.shouldBeNull()
    }

    @Test
    fun toSnapshot_givenReportedZeroAccuracy_expectZeroKept() {
        location(elapsedNanos = 5_000_000L, accuracyMeters = 0f).toSnapshot()
            .horizontalAccuracy shouldBeEqualTo 0.0
    }

    @Test
    fun toSnapshot_givenNonFiniteOrNegativeAccuracy_expectNull() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach { reported ->
            location(elapsedNanos = 5_000_000L, accuracyMeters = reported).toSnapshot()
                .horizontalAccuracy.shouldBeNull()
        }
    }

    @Test
    fun toSnapshot_givenUnreportedAccuracy_expectTimeAndCoordinatesStillCarried() {
        val snapshot = location(elapsedNanos = 90_000_000_000L, accuracyMeters = 0f, reportsAccuracy = false)
            .toSnapshot()

        snapshot.latitude shouldBeEqualTo 37.7749
        snapshot.longitude shouldBeEqualTo -122.4194
        snapshot.fixElapsedRealtimeMillis shouldBeEqualTo 90_000L
    }

    private fun location(
        elapsedNanos: Long,
        accuracyMeters: Float = 12f,
        reportsAccuracy: Boolean = true
    ): android.location.Location = mockk {
        every { latitude } returns 37.7749
        every { longitude } returns -122.4194
        every { time } returns 1_700_000_000_000L
        every { accuracy } returns accuracyMeters
        every { hasAccuracy() } returns reportsAccuracy
        every { hasAltitude() } returns false
        every { elapsedRealtimeNanos } returns elapsedNanos
    }
}
