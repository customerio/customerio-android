package io.customer.geofence.di

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceLocation
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonSupport
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpRequestParams
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The seams default to [PolygonSupport.Disabled], which is only safe if the production graph actually
 * supplies the opt-in — and supplies it *everywhere*. A build that asked the backend for polygons and
 * then dropped them at the ranker, or ranked polygons it never asked for, would pass every per-class
 * test in this module while being broken in production. These assertions read the real graph.
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
        SDKComponent.polygonSupport.requestedCapabilities shouldBeEqualTo
            listOf(PolygonSupport.POLYGON_V1_CAPABILITY)
    }

    @Test
    fun geofenceApiService_givenProductionGraph_expectPolygonCapabilityRequested() = runTest {
        val params = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(params)) } returns Result.success("{}")

        SDKComponent.geofenceApiService.fetchGeofences(GeofenceLocation(1.0, 2.0))

        params.captured.body.shouldNotBeNull() shouldContain PolygonSupport.POLYGON_V1_CAPABILITY
    }

    @Test
    fun geofenceDistanceFilter_givenProductionGraph_expectPolygonRanked() {
        // Behavioural check on the other half of the coupling: the ranker the graph builds must accept
        // the shape the request above asks for, or every returned polygon is fetched and then dropped.
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
