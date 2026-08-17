package io.customer.geofence.api

import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLocation
import io.customer.geofence.polygon.PolygonSupport
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpMethod
import io.customer.sdk.core.network.HttpRequestParams
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeNull
import org.amshove.kluent.shouldNotContain
import org.junit.Test

class GeofenceApiServiceTest {

    private val httpClient: CustomerIOHttpClient = mockk(relaxed = true)
    private val service = GeofenceApiServiceImpl(
        httpClient,
        GeofenceJsonSerializer(),
        PolygonSupport.Enabled
    )

    @Test
    fun fetchGeofences_givenAnyRequest_expectPostToNearest() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("{}")

        service.fetchGeofences(GeofenceLocation(latitude = 1.0, longitude = 2.0))

        capturedParams.captured.method shouldBeEqualTo HttpMethod.POST
        capturedParams.captured.path shouldBeEqualTo "/geofences/nearest"
    }

    @Test
    fun fetchGeofences_givenLocation_expectCoordinatesInJsonBodyWithoutRadius() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("{}")

        service.fetchGeofences(GeofenceLocation(latitude = 37.7749295, longitude = -122.4194155))

        val body = capturedParams.captured.body.shouldNotBeNull()
        body shouldContain "\"latitude\":37.7749295"
        body shouldContain "\"longitude\":-122.4194155"
        body shouldContain "\"capabilities\":[\"polygon-v1\"]"
        // radius is optional on the endpoint and no longer sent.
        body shouldNotContain "radius"
        capturedParams.captured.headers["Content-Type"] shouldBeEqualTo "application/json"
    }

    @Test
    fun fetchGeofences_givenPolygonMonitoringDisabled_expectPolygonCapabilityNotRequested() = runTest {
        // The request and the mapper read the same opt-in, so a build that would drop every polygon
        // never asks the backend to spend response slots returning them.
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("{}")
        val disabled = GeofenceApiServiceImpl(
            httpClient,
            GeofenceJsonSerializer(),
            PolygonSupport.Disabled
        )

        disabled.fetchGeofences(GeofenceLocation(latitude = 1.0, longitude = 2.0))

        val body = capturedParams.captured.body.shouldNotBeNull()
        body shouldContain "\"capabilities\":[]"
        body shouldNotContain "polygon-v1"
    }

    @Test
    fun requestedCapabilities_givenEitherOptIn_expectDerivedFromMonitoringSupport() {
        // Guards the coupling itself: nothing may hand out the capability independently of the
        // capability to monitor it.
        PolygonSupport.Enabled.requestedCapabilities shouldBeEqualTo listOf(PolygonSupport.POLYGON_V1_CAPABILITY)
        PolygonSupport.Disabled.requestedCapabilities shouldBeEqualTo emptyList()
    }
}
