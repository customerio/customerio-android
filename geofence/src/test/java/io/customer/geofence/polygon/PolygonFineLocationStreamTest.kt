package io.customer.geofence.polygon

import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.store.GeofenceRegionStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The lifecycle rules that keep continuous mode from outliving the session it belongs to: multi-fix
 * evidence only from an accepted registration, and no path by which a late Play services callback
 * can resume fine sampling after teardown.
 */
@RunWith(RobolectricTestRunner::class)
class PolygonFineLocationStreamTest : RobolectricTest() {
    private val client: FusedLocationProviderClient = mockk(relaxed = true)
    private val engine: PolygonLocationEngine = mockk(relaxed = true)
    private val store: GeofenceRegionStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)

    @Before
    fun setUpSession() {
        every { store.getActivePolygonIds() } returns setOf("campus")
        every { store.userStateGeneration() } returns 4L
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
    }

    @Test
    fun start_givenNoActivePolygon_expectNoRegistration() {
        every { store.getActivePolygonIds() } returns emptySet()

        stream().start {} shouldBeEqualTo null

        verify(exactly = 0) {
            client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any())
        }
    }

    @Test
    fun start_expectHighAccuracyStreamThatIsReadyOnlyOncePlayServicesAcceptsIt() {
        val request = slot<LocationRequest>()
        every {
            client.requestLocationUpdates(capture(request), any<LocationCallback>(), any())
        } returns Tasks.forResult(null)
        val stream = stream()

        val generation = stream.start {}

        generation shouldBeEqualTo 1L
        // Registered but not yet accepted: nothing may treat this as a live multi-fix source.
        stream.isReady() shouldBeEqualTo false
        shadowOf(Looper.getMainLooper()).idle()
        stream.isReady() shouldBeEqualTo true
        request.captured.priority shouldBeEqualTo Priority.PRIORITY_HIGH_ACCURACY
        request.captured.intervalMillis shouldBeEqualTo 2_000L
        verify { logger.logPolygonContinuousMonitoringStarted() }
    }

    @Test
    fun start_givenAlreadyRegistered_expectSameGenerationWithoutSecondRequest() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any())
        } returns Tasks.forResult(null)
        val stream = stream()

        val first = stream.start {}
        val second = stream.start {}

        second shouldBeEqualTo first
        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any())
        }
    }

    @Test
    fun locationBatch_expectConfirmedEvaluationForTheCurrentUserGeneration() {
        val callback = registerAndCaptureCallback()

        callback.onLocationResult(LocationResult.create(listOf(location())))

        coVerify { engine.processContinuousLocations(any(), 4L) }
    }

    @Test
    fun stop_expectRemovesUpdatesAndRefusesLateBatches() {
        val callback = registerAndCaptureCallback()
        val stream = lastStream

        stream.stop()
        shadowOf(Looper.getMainLooper()).idle()
        callback.onLocationResult(LocationResult.create(listOf(location())))

        stream.isReady() shouldBeEqualTo false
        verify { client.removeLocationUpdates(callback) }
        coVerify(exactly = 0) { engine.processContinuousLocations(any(), any()) }
    }

    @Test
    fun stop_givenRegistrationAcceptedAfterTeardown_expectAcceptanceIsRemovedNotHonoured() {
        val callback = slot<LocationCallback>()
        every {
            client.requestLocationUpdates(any<LocationRequest>(), capture(callback), any())
        } returns Tasks.forResult(null)
        val stream = stream()

        stream.start {}
        // Sign-out lands while Play services is still accepting the request.
        stream.stop()
        shadowOf(Looper.getMainLooper()).idle()

        stream.isReady() shouldBeEqualTo false
        verify(atLeast = 1) { client.removeLocationUpdates(callback.captured) }
        verify(exactly = 0) { logger.logPolygonContinuousMonitoringStarted() }
    }

    @Test
    fun stopIfCurrent_givenSupersededGeneration_expectCurrentRegistrationSurvives() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any())
        } returns Tasks.forResult(null)
        val stream = stream()
        val generation = requireNotNull(stream.start {})
        stream.stop()
        shadowOf(Looper.getMainLooper()).idle()
        stream.start {}
        shadowOf(Looper.getMainLooper()).idle()

        // The destroyed service reports its own, older registration.
        stream.stopIfCurrent(generation)

        stream.isReady() shouldBeEqualTo true
    }

    @Test
    fun start_givenPermissionRevoked_expectFailsClosedAndSignalsUnavailable() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any())
        } returns Tasks.forException(SecurityException("location permission revoked"))
        val stream = stream()
        var unavailable = false

        stream.start { unavailable = true }
        shadowOf(Looper.getMainLooper()).idle()

        unavailable shouldBeEqualTo true
        stream.isReady() shouldBeEqualTo false
        verify { logger.logPolygonMonitoringFailed("location permission revoked") }
    }

    private fun registerAndCaptureCallback(): LocationCallback {
        val callback = slot<LocationCallback>()
        every {
            client.requestLocationUpdates(any<LocationRequest>(), capture(callback), any())
        } returns Tasks.forResult(null)
        lastStream = stream()
        lastStream.start {}
        shadowOf(Looper.getMainLooper()).idle()
        return callback.captured
    }

    private lateinit var lastStream: PolygonFineLocationStream

    private fun stream() = PolygonFineLocationStream(
        client = client,
        engine = engine,
        store = store,
        logger = logger,
        backgroundContext = Dispatchers.Unconfined
    )

    private fun location() = Location("test").apply {
        latitude = 37.775
        longitude = -122.4194
        accuracy = 5f
        elapsedRealtimeNanos = 1_000L
    }
}
