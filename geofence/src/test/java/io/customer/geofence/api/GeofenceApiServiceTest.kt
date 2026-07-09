package io.customer.geofence.api

import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLocation
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpMethod
import io.customer.sdk.core.network.HttpRequestParams
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.junit.Test

class GeofenceApiServiceTest {

    private val httpClient: CustomerIOHttpClient = mockk(relaxed = true)
    private val service = GeofenceApiServiceImpl(httpClient, GeofenceJsonSerializer())

    @Test
    fun fetchGeofences_givenAnyRequest_expectPostToNearest() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("{}")

        service.fetchGeofences(GeofenceLocation(latitude = 1.0, longitude = 2.0))

        capturedParams.captured.method shouldBeEqualTo HttpMethod.POST
        capturedParams.captured.path shouldBeEqualTo "/geofences/nearest"
    }

    @Test
    fun fetchGeofences_givenNoLocation_expectNoBody() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("{}")

        service.fetchGeofences(location = null)

        capturedParams.captured.body.shouldBeNull()
    }

    @Test
    fun fetchGeofences_givenLocation_expectCoordinatesInJsonBody() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("{}")

        service.fetchGeofences(
            GeofenceLocation(latitude = 37.7749295, longitude = -122.4194155),
            radiusMeters = 20000.0
        )

        val body = capturedParams.captured.body!!
        body shouldContain "\"latitude\":37.7749295"
        body shouldContain "\"longitude\":-122.4194155"
        body shouldContain "\"radius\":20000.0"
        capturedParams.captured.headers["Content-Type"] shouldBeEqualTo "application/json"
    }
}
