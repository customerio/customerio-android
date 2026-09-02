package io.customer.geofence

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

class GeofenceFixQualityTest {
    @Test
    fun containmentMargin_givenReportedAccuracy_expectItUsedAsTheMargin() {
        GeofenceFixQuality(accuracyMeters = 25.0).containmentMarginMeters shouldBeEqualTo 25.0
    }

    @Test
    fun containmentMargin_givenUnreportedAccuracy_expectNoMargin() {
        GeofenceFixQuality.UNKNOWN.containmentMarginMeters shouldBeEqualTo 0.0
    }

    @Test
    fun containmentMargin_givenUnusableAccuracy_expectNoMarginRatherThanAnUndecidableOne() {
        // NaN would make both the inside and the outside comparison false, so every fence would
        // land in the ambiguous band and the pass would silently decide nothing.
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0).forEach { accuracy ->
            GeofenceFixQuality(accuracyMeters = accuracy).containmentMarginMeters shouldBeEqualTo 0.0
        }
    }

    @Test
    fun isFresh_givenUnreportedTime_expectTreatedAsCurrent() {
        GeofenceFixQuality.UNKNOWN.isFresh(NOW).shouldBeTrue()
    }

    @Test
    fun isFresh_givenAgeWithinAndBeyondTheLimit_expectBoundaryRespected() {
        val limit = GeofenceConstants.MAX_LIVE_FIX_AGE_MS
        GeofenceFixQuality(fixElapsedRealtimeMillis = NOW - limit).isFresh(NOW).shouldBeTrue()
        GeofenceFixQuality(fixElapsedRealtimeMillis = NOW - limit - 1).isFresh(NOW).shouldBeFalse()
    }

    @Test
    fun isFresh_givenFixStampedAfterNow_expectNotTreatedAsFresh() {
        // Monotonic time cannot run backwards, so this only arises from a bogus supplied stamp.
        GeofenceFixQuality(fixElapsedRealtimeMillis = NOW + 1).isFresh(NOW).shouldBeFalse()
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
