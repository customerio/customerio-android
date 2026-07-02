package io.customer.geofence.api

import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLocation
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpRequestParams
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class GeofenceApiServiceTest {

    private val httpClient: CustomerIOHttpClient = mockk(relaxed = true)
    private val service = GeofenceApiServiceImpl(httpClient, GeofenceJsonSerializer())

    @Test
    fun fetchGeofences_givenNoLocation_expectNoCoordinatesOnTheWire() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("{}")

        service.fetchGeofences(location = null)

        capturedParams.captured.queryParams.shouldBeEmpty()
    }

    @Test
    fun fetchGeofences_givenLocation_expectCoordinatesOnTheWire() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("{}")

        service.fetchGeofences(GeofenceLocation(latitude = 37.7749295, longitude = -122.4194155))

        capturedParams.captured.queryParams["latitude"] shouldBeEqualTo "37.7749295"
        capturedParams.captured.queryParams["longitude"] shouldBeEqualTo "-122.4194155"
    }
}
