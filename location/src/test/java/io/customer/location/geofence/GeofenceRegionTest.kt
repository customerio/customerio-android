package io.customer.location.geofence

import com.google.android.gms.location.Geofence
import io.customer.commontest.core.RobolectricTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceRegionTest : RobolectricTest() {

    @Test
    fun toGmsTransitionTypes_givenEnterOnly_expectEnterBitmask() {
        val region = buildRegion(transitionTypes = listOf(GeofenceTransitionType.ENTER))

        region.toGmsTransitionTypes() shouldBeEqualTo Geofence.GEOFENCE_TRANSITION_ENTER
    }

    @Test
    fun toGmsTransitionTypes_givenExitOnly_expectExitBitmask() {
        val region = buildRegion(transitionTypes = listOf(GeofenceTransitionType.EXIT))

        region.toGmsTransitionTypes() shouldBeEqualTo Geofence.GEOFENCE_TRANSITION_EXIT
    }

    @Test
    fun toGmsTransitionTypes_givenBothTransitions_expectCombinedBitmask() {
        val region = buildRegion(
            transitionTypes = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT)
        )

        region.toGmsTransitionTypes() shouldBeEqualTo
            (Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
    }

    @Test
    fun toGmsTransitionTypes_givenDefaultTransitions_expectBothEnterAndExit() {
        val region = buildRegion()

        region.toGmsTransitionTypes() shouldBeEqualTo
            (Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
    }

    private fun buildRegion(
        transitionTypes: List<GeofenceTransitionType> = listOf(
            GeofenceTransitionType.ENTER,
            GeofenceTransitionType.EXIT
        )
    ) = GeofenceRegion(
        id = "test-geofence",
        latitude = 0.0,
        longitude = 0.0,
        radiusMeters = 100f,
        transitionTypes = transitionTypes
    )
}
