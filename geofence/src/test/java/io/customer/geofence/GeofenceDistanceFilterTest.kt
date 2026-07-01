package io.customer.geofence

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceDistanceFilterTest : RobolectricTest() {

    private val filter = GeofenceDistanceFilter()
    private val noDistanceCap = GeofenceConstants.NO_MONITORING_DISTANCE_CAP_METERS

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
    }

    @Test
    fun nearest_givenEmptyList_expectEmpty() {
        filter.nearest(emptyList(), latitude = 0.0, longitude = 0.0, max = 5, maxDistanceMeters = noDistanceCap).shouldBeEmpty()
    }

    @Test
    fun nearest_givenMaxZero_expectEmpty() {
        val regions = listOf(region("biz-1", 0.0, 0.0))
        filter.nearest(regions, latitude = 0.0, longitude = 0.0, max = 0, maxDistanceMeters = noDistanceCap).shouldBeEmpty()
    }

    @Test
    fun nearest_givenFewerRegionsThanMax_expectAllReturnedSortedByDistance() {
        val reference = 0.0 to 0.0
        val far = region("biz-far", 5.0, 0.0)
        val close = region("biz-close", 0.01, 0.0)
        val mid = region("biz-mid", 1.0, 0.0)

        val result = filter.nearest(listOf(far, close, mid), reference.first, reference.second, max = 5, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-close", "biz-mid", "biz-far")
    }

    @Test
    fun nearest_givenMoreRegionsThanMax_expectNearestMaxReturned() {
        val reference = 0.0 to 0.0
        val regions = listOf(
            region("biz-far", 5.0, 0.0),
            region("biz-close", 0.01, 0.0),
            region("biz-mid", 1.0, 0.0),
            region("biz-farther", 10.0, 0.0)
        )

        val result = filter.nearest(regions, reference.first, reference.second, max = 2, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-close", "biz-mid")
    }

    @Test
    fun nearest_givenMaxDistance_expectRegionsBeyondCapExcluded() {
        val close = region("biz-close", 0.01, 0.0) // ~1.1 km
        val mid = region("biz-mid", 1.0, 0.0) // ~111 km
        val far = region("biz-far", 5.0, 0.0) // ~555 km

        // 50 km cap: only the ~1.1 km region qualifies; count budget is irrelevant here.
        val result = filter.nearest(
            listOf(far, close, mid),
            latitude = 0.0,
            longitude = 0.0,
            max = 5,
            maxDistanceMeters = 50_000f
        )

        result.map { it.id } shouldBeEqualTo listOf("biz-close")
    }

    @Test
    fun nearest_givenNoMaxDistance_expectFarRegionsStillIncluded() {
        val close = region("biz-close", 0.01, 0.0)
        val far = region("biz-far", 5.0, 0.0) // ~555 km

        // Default (no cap): distance doesn't exclude, only the count budget does.
        val result = filter.nearest(listOf(far, close), latitude = 0.0, longitude = 0.0, max = 5, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-close", "biz-far")
    }

    @Test
    fun nearest_givenEquallyDistantRegions_expectStableInputOrder() {
        // Two regions equally distant from origin — Kotlin's sortedBy is stable
        val first = region("biz-first", 1.0, 0.0)
        val second = region("biz-second", -1.0, 0.0)

        val result = filter.nearest(listOf(first, second), latitude = 0.0, longitude = 0.0, max = 2, maxDistanceMeters = noDistanceCap)

        result.map { it.id } shouldBeEqualTo listOf("biz-first", "biz-second")
    }

    private fun region(
        id: String,
        latitude: Double,
        longitude: Double
    ) = GeofenceRegion(id = id, latitude = latitude, longitude = longitude, radius = 100f)
}
