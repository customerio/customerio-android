package io.customer.geofence.api

import io.customer.geofence.GeofenceJsonSerializer
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
    fun fetchGeofences_givenPreciseLocation_expectCoarseCoordinatesOnTheWire() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("{}")

        service.fetchGeofences(latitude = 37.774929, longitude = -122.419416)

        capturedParams.captured.queryParams["latitude"] shouldBeEqualTo "37.77"
        capturedParams.captured.queryParams["longitude"] shouldBeEqualTo "-122.42"
    }

    @Test
    fun fetchGeofences_givenNoLocation_expectNoCoordinatesOnTheWire() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("{}")

        service.fetchGeofences()

        capturedParams.captured.queryParams.shouldBeEmpty()
    }
}
