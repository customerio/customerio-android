package io.customer.geofence

import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

class GeofenceFixQualityTest {
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
        // A bogus future stamp would otherwise read as fresh forever.
        GeofenceFixQuality(fixElapsedRealtimeMillis = NOW + 1).isFresh(NOW).shouldBeFalse()
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
