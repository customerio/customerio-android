package io.customer.geofence.di

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonSupport
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The seams default to [PolygonSupport.Disabled], so these read the real graph to prove it supplies
 * the opt-in everywhere. Per-class tests would miss a seam left disabled.
 */
@RunWith(RobolectricTestRunner::class)
class GeofencePolygonSupportWiringTest : RobolectricTest() {

    private val httpClient: CustomerIOHttpClient = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph { sdk { overrideDependency<CustomerIOHttpClient>(httpClient) } }
            }
        )
    }

    @Test
    fun polygonSupport_givenProductionGraph_expectEnabledOptIn() {
        SDKComponent.polygonSupport.isPolygonMonitoringEnabled shouldBeEqualTo true
    }

    @Test
    fun geofenceDistanceFilter_givenProductionGraph_expectPolygonRanked() {
        val ranked = SDKComponent.geofenceDistanceFilter.nearest(
            regions = listOf(polygonRegion()),
            latitude = 37.7750,
            longitude = -122.4194,
            max = 5,
            maxDistanceMeters = Float.MAX_VALUE
        )

        ranked.map(GeofenceRegion::id) shouldBeEqualTo listOf("campus")
    }

    private fun polygonRegion() = GeofenceRegion(
        id = "campus",
        latitude = 37.7750,
        longitude = -122.4194,
        radius = 1_200f,
        polygonVertices = listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        )
    )
}
