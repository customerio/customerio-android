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

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
    }

    @Test
    fun nearest_givenEmptyList_expectEmpty() {
        filter.nearest(emptyList(), latitude = 0.0, longitude = 0.0, max = 5).shouldBeEmpty()
    }

    @Test
    fun nearest_givenMaxZero_expectEmpty() {
        val regions = listOf(region("biz-1", 0.0, 0.0))
        filter.nearest(regions, latitude = 0.0, longitude = 0.0, max = 0).shouldBeEmpty()
    }

    @Test
    fun nearest_givenFewerRegionsThanMax_expectAllReturnedSortedByDistance() {
        val reference = 0.0 to 0.0
        val far = region("biz-far", 5.0, 0.0)
        val close = region("biz-close", 0.01, 0.0)
        val mid = region("biz-mid", 1.0, 0.0)

        val result = filter.nearest(listOf(far, close, mid), reference.first, reference.second, max = 5)

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

        val result = filter.nearest(regions, reference.first, reference.second, max = 2)

        result.map { it.id } shouldBeEqualTo listOf("biz-close", "biz-mid")
    }

    @Test
    fun nearest_givenEquallyDistantRegions_expectStableInputOrder() {
        // Two regions equally distant from origin — Kotlin's sortedBy is stable
        val first = region("biz-first", 1.0, 0.0)
        val second = region("biz-second", -1.0, 0.0)

        val result = filter.nearest(listOf(first, second), latitude = 0.0, longitude = 0.0, max = 2)

        result.map { it.id } shouldBeEqualTo listOf("biz-first", "biz-second")
    }

    private fun region(
        id: String,
        latitude: Double,
        longitude: Double
    ) = GeofenceRegion(id = id, latitude = latitude, longitude = longitude, radius = 100f)
}
