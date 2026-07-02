package io.customer.geofence

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class GeofenceSyncModeTest {

    private val config = GeofenceConfig.fallback().copy(remoteFetchRefreshTriggerRadius = 5_000f)

    @Test
    fun active_expectNearby() {
        // The shipped default. Changing this changes whether the SDK transmits device location.
        GeofenceSyncMode.active shouldBeEqualTo GeofenceSyncMode.NEARBY
    }

    @Test
    fun fetchAll_givenMovedFarPastRadius_expectNoRemoteFetch() {
        // Holds the full set, so even moving far past the radius never re-fetches.
        GeofenceSyncMode.FETCH_ALL.movementRequiresRemoteFetch(10_000f, config) shouldBeEqualTo false
    }

    @Test
    fun nearby_givenMovedAtOrBeyondFetchRadius_expectRemoteFetch() {
        GeofenceSyncMode.NEARBY.movementRequiresRemoteFetch(5_000f, config) shouldBeEqualTo true
        GeofenceSyncMode.NEARBY.movementRequiresRemoteFetch(5_001f, config) shouldBeEqualTo true
    }

    @Test
    fun nearby_givenWithinFetchRadius_expectNoRemoteFetch() {
        GeofenceSyncMode.NEARBY.movementRequiresRemoteFetch(4_999f, config) shouldBeEqualTo false
    }
}
