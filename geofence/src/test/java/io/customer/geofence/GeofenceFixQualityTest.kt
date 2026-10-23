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

    @Test
    fun provesOutside_givenClearanceAroundAccuracyPlusMargin_expectBoundaryRespected() {
        val fix = GeofenceFixQuality(fixElapsedRealtimeMillis = NOW, horizontalAccuracyMeters = 30f)
        val needed = 30f + GeofenceConstants.OUTSIDE_PROOF_MARGIN_METERS
        fix.provesOutside(metersBeyondEdge = needed + 1f, nowElapsedRealtimeMillis = NOW).shouldBeTrue()
        fix.provesOutside(metersBeyondEdge = needed, nowElapsedRealtimeMillis = NOW).shouldBeFalse()
        fix.provesOutside(metersBeyondEdge = -5f, nowElapsedRealtimeMillis = NOW).shouldBeFalse()
    }

    @Test
    fun provesOutside_givenMissingAccuracyOrTime_expectNoProof() {
        // Unlike freshness, an unknown here fails closed: nothing then rules out a device inside.
        GeofenceFixQuality(fixElapsedRealtimeMillis = NOW)
            .provesOutside(metersBeyondEdge = 10_000f, nowElapsedRealtimeMillis = NOW).shouldBeFalse()
        GeofenceFixQuality(horizontalAccuracyMeters = 5f)
            .provesOutside(metersBeyondEdge = 10_000f, nowElapsedRealtimeMillis = NOW).shouldBeFalse()
        GeofenceFixQuality(fixElapsedRealtimeMillis = NOW, horizontalAccuracyMeters = Float.NaN)
            .provesOutside(metersBeyondEdge = 10_000f, nowElapsedRealtimeMillis = NOW).shouldBeFalse()
    }

    @Test
    fun provesOutside_givenAgeWithinAndBeyondTheProofWindow_expectBoundaryRespected() {
        val limit = GeofenceConstants.MAX_OUTSIDE_PROOF_AGE_MS
        fun proves(takenAt: Long) = GeofenceFixQuality(fixElapsedRealtimeMillis = takenAt, horizontalAccuracyMeters = 5f)
            .provesOutside(metersBeyondEdge = 10_000f, nowElapsedRealtimeMillis = NOW)
        proves(NOW - limit).shouldBeTrue()
        proves(NOW - limit - 1).shouldBeFalse()
        proves(NOW + 1).shouldBeFalse()
    }

    @Test
    fun clearsEdge_givenOldFix_expectOnlyClearanceJudged() {
        // No age bound: it serves fences GMS was already watching when the fix was taken.
        val fix = GeofenceFixQuality(fixElapsedRealtimeMillis = 0L, horizontalAccuracyMeters = 30f)
        fix.clearsEdge(metersBeyondEdge = 51f).shouldBeTrue()
        fix.clearsEdge(metersBeyondEdge = 50f).shouldBeFalse()
        GeofenceFixQuality(fixElapsedRealtimeMillis = 0L).clearsEdge(metersBeyondEdge = 10_000f).shouldBeFalse()
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
