package io.customer.geofence

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

    @Test
    fun edgeDistanceTo_givenCoordinatesInsideRegion_expectZero() {
        val region = buildRegion(radius = 5_000f)

        // ~2.2 km from center, well within the radius.
        region.edgeDistanceTo(0.02, 0.0) shouldBeEqualTo 0f
    }

    @Test
    fun edgeDistanceTo_givenCoordinatesOnBoundary_expectZero() {
        val centerDistance = buildRegion().distanceTo(0.01, 0.0)
        val region = buildRegion(radius = centerDistance)

        region.edgeDistanceTo(0.01, 0.0) shouldBeEqualTo 0f
    }

    @Test
    fun edgeDistanceTo_givenCoordinatesOutsideRegion_expectDistanceLessRadius() {
        val region = buildRegion(radius = 1_000f)
        val centerDistance = region.distanceTo(0.05, 0.0)

        region.edgeDistanceTo(0.05, 0.0) shouldBeEqualTo centerDistance - 1_000f
    }

    private fun buildRegion(
        transitionTypes: List<GeofenceTransitionType> = listOf(
            GeofenceTransitionType.ENTER,
            GeofenceTransitionType.EXIT
        ),
        radius: Float = 100f
    ) = GeofenceRegion(
        id = "test-geofence",
        latitude = 0.0,
        longitude = 0.0,
        radius = radius,
        transitionTypes = transitionTypes
    )
}
