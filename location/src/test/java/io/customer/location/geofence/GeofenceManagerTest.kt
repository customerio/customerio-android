package io.customer.location.geofence

import android.Manifest
import android.app.PendingIntent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.di.geofenceLogger
import io.customer.sdk.core.di.SDKComponent
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GeofenceManagerTest : RobolectricTest() {

    private val client: GeofencingClient = mockk(relaxed = true)
    private val receiverToggle: GeofenceReceiverToggle = mockk(relaxUnitFun = true)

    private lateinit var manager: GeofenceManager

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
            }
        )
        manager = GeofenceManager(applicationMock, client, receiverToggle, SDKComponent.geofenceLogger)
    }

    @Test
    fun addGeofences_givenEmptyList_expectSuccessWithoutCallingClient() = runTest {
        val result = manager.addGeofences(emptyList())

        result.isSuccess.shouldBeTrue()
        verify(exactly = 0) { client.addGeofences(any<GeofencingRequest>(), any()) }
    }

    @Test
    fun addGeofences_givenNoFineLocationPermission_expectFailure() = runTest {
        denyPermission(Manifest.permission.ACCESS_FINE_LOCATION)

        val result = manager.addGeofences(listOf(buildRegion()))

        result.isFailure.shouldBeTrue()
        verify(exactly = 0) { client.addGeofences(any<GeofencingRequest>(), any()) }
    }

    @Test
    fun addGeofences_givenFineLocationGrantedNoBackgroundOnQ_expectFailure() = runTest {
        grantPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        denyPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        val result = manager.addGeofences(listOf(buildRegion()))

        result.isFailure.shouldBeTrue()
    }

    @Test
    fun addGeofences_givenAllPermissionsGranted_expectClientCalled() = runTest {
        grantAllPermissions()
        stubClientAddSuccess()

        val result = manager.addGeofences(listOf(buildRegion()))

        result.isSuccess.shouldBeTrue()
        verify { client.addGeofences(any<GeofencingRequest>(), any()) }
    }

    @Test
    fun addGeofences_givenBusinessGeofences_expectInitialTriggerEnter() = runTest {
        grantAllPermissions()

        val requestSlot = slot<GeofencingRequest>()
        stubClientAddSuccess(requestSlot)

        manager.addGeofences(listOf(buildRegion(id = "business-1")))

        requestSlot.captured.initialTrigger shouldContainFlag GeofencingRequest.INITIAL_TRIGGER_ENTER
    }

    @Test
    fun addGeofences_givenMovementTrigger_expectNoInitialTrigger() = runTest {
        grantAllPermissions()

        val requestSlot = slot<GeofencingRequest>()
        stubClientAddSuccess(requestSlot)

        manager.addGeofences(
            listOf(buildRegion(id = GeofenceConstants.MOVEMENT_TRIGGER_ID))
        )

        requestSlot.captured.initialTrigger shouldNotContainFlag GeofencingRequest.INITIAL_TRIGGER_ENTER
    }

    @Test
    fun addGeofences_givenMixedBatch_expectSeparateRequestsWithCorrectTriggers() = runTest {
        grantAllPermissions()

        val requests = mutableListOf<GeofencingRequest>()
        val task = immediateSuccessTask<Void>()
        every { client.addGeofences(capture(requests), any()) } returns task

        manager.addGeofences(
            listOf(
                buildRegion(id = GeofenceConstants.MOVEMENT_TRIGGER_ID),
                buildRegion(id = "business-1")
            )
        )

        requests.size shouldBeEqualTo 2
        // First request: movement trigger with no initial trigger
        requests[0].initialTrigger shouldNotContainFlag GeofencingRequest.INITIAL_TRIGGER_ENTER
        requests[0].geofences.first().requestId shouldBeEqualTo GeofenceConstants.MOVEMENT_TRIGGER_ID
        // Second request: business geofence with INITIAL_TRIGGER_ENTER
        requests[1].initialTrigger shouldContainFlag GeofencingRequest.INITIAL_TRIGGER_ENTER
        requests[1].geofences.first().requestId shouldBeEqualTo "business-1"
    }

    @Test
    fun addGeofences_givenMixedBatchWithBusinessFailure_expectMovementTriggerCleanedUp() = runTest {
        grantAllPermissions()

        var callCount = 0
        val successTask = immediateSuccessTask<Void>()
        val failureTask = immediateFailureTask<Void>(RuntimeException("GMS error"))

        // First call (movement trigger) succeeds, second call (business) fails
        every { client.addGeofences(any<GeofencingRequest>(), any()) } answers {
            callCount++
            if (callCount == 1) successTask else failureTask
        }
        val removeTask = immediateSuccessTask<Void>()
        every { client.removeGeofences(any<List<String>>()) } returns removeTask

        val result = manager.addGeofences(
            listOf(
                buildRegion(id = GeofenceConstants.MOVEMENT_TRIGGER_ID),
                buildRegion(id = "business-1")
            )
        )

        result.isFailure.shouldBeTrue()
        // Movement trigger should be cleaned up after business batch failure
        verify { client.removeGeofences(listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID)) }
        verify(exactly = 0) { receiverToggle.setEnabled(any()) }
    }

    @Test
    fun addGeofences_givenRegion_expectGmsGeofenceBuiltCorrectly() = runTest {
        grantAllPermissions()

        val requestSlot = slot<GeofencingRequest>()
        stubClientAddSuccess(requestSlot)

        val region = buildRegion(
            id = "geo-123",
            latitude = 40.7128,
            longitude = -74.0060,
            radiusMeters = 250f,
            transitionTypes = listOf(GeofenceTransitionType.ENTER)
        )
        manager.addGeofences(listOf(region))

        val gmsGeofence = requestSlot.captured.geofences.first()
        gmsGeofence.requestId shouldBeEqualTo "geo-123"
        gmsGeofence.transitionTypes shouldBeEqualTo Geofence.GEOFENCE_TRANSITION_ENTER
        gmsGeofence.expirationTime shouldBeEqualTo GeofenceConstants.GEOFENCE_EXPIRATION_NEVER
    }

    @Test
    fun addGeofences_givenSuccess_expectReceiversEnabled() = runTest {
        grantAllPermissions()
        stubClientAddSuccess()

        manager.addGeofences(listOf(buildRegion()))

        verify { receiverToggle.setEnabled(true) }
    }

    @Test
    fun addGeofences_givenFailure_expectReceiversNotToggled() = runTest {
        grantAllPermissions()
        stubClientAddFailure(RuntimeException("GMS error"))

        manager.addGeofences(listOf(buildRegion()))

        verify(exactly = 0) { receiverToggle.setEnabled(any()) }
    }

    @Test
    fun addGeofences_givenClientFails_expectFailureResult() = runTest {
        grantAllPermissions()
        stubClientAddFailure(RuntimeException("GMS error"))

        val result = manager.addGeofences(listOf(buildRegion()))

        result.isFailure.shouldBeTrue()
    }

    @Test
    fun removeGeofencesByIds_givenEmptyIds_expectSuccessWithoutCallingClient() = runTest {
        val result = manager.removeGeofencesByIds(emptyList())

        result.isSuccess.shouldBeTrue()
        verify(exactly = 0) { client.removeGeofences(any<List<String>>()) }
    }

    @Test
    fun removeGeofencesByIds_givenIds_expectClientCalledWithIds() = runTest {
        val idsSlot = slot<List<String>>()
        stubClientRemoveByIdsSuccess(idsSlot)

        manager.removeGeofencesByIds(listOf("geo-1", "geo-2"))

        idsSlot.captured shouldContainAll listOf("geo-1", "geo-2")
    }

    @Test
    fun clearAll_givenSuccess_expectClientCalledWithPendingIntent() = runTest {
        stubClientRemoveByPendingIntentSuccess()

        val result = manager.clearAll()

        result.isSuccess.shouldBeTrue()
        verify { client.removeGeofences(any<PendingIntent>()) }
    }

    @Test
    fun clearAll_givenSuccess_expectReceiversDisabled() = runTest {
        stubClientRemoveByPendingIntentSuccess()

        manager.clearAll()

        verify { receiverToggle.setEnabled(false) }
    }

    // -- Helpers: permission --

    private fun grantPermission(permission: String) {
        shadowOf(applicationMock).grantPermissions(permission)
    }

    private fun denyPermission(permission: String) {
        shadowOf(applicationMock).denyPermissions(permission)
    }

    private fun grantAllPermissions() {
        grantPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        grantPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    // -- Helpers: GMS Task stubs --

    private fun stubClientAddSuccess(requestSlot: CapturingSlot<GeofencingRequest>? = null) {
        val task = immediateSuccessTask<Void>()
        if (requestSlot != null) {
            every { client.addGeofences(capture(requestSlot), any()) } returns task
        } else {
            every { client.addGeofences(any<GeofencingRequest>(), any()) } returns task
        }
    }

    private fun stubClientAddFailure(exception: Exception) {
        val task = immediateFailureTask<Void>(exception)
        every { client.addGeofences(any<GeofencingRequest>(), any()) } returns task
    }

    private fun stubClientRemoveByIdsSuccess(idsSlot: CapturingSlot<List<String>>? = null) {
        val task = immediateSuccessTask<Void>()
        if (idsSlot != null) {
            every { client.removeGeofences(capture(idsSlot)) } returns task
        } else {
            every { client.removeGeofences(any<List<String>>()) } returns task
        }
    }

    private fun stubClientRemoveByPendingIntentSuccess() {
        val task = immediateSuccessTask<Void>()
        every { client.removeGeofences(any<PendingIntent>()) } returns task
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> immediateSuccessTask(): Task<T> {
        val task = mockk<Task<T>>()
        every { task.addOnSuccessListener(any()) } answers {
            firstArg<OnSuccessListener<T>>().onSuccess(null as T)
            task
        }
        every { task.addOnFailureListener(any()) } returns task
        return task
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> immediateFailureTask(exception: Exception): Task<T> {
        val task = mockk<Task<T>>()
        every { task.addOnSuccessListener(any()) } returns task
        every { task.addOnFailureListener(any()) } answers {
            firstArg<OnFailureListener>().onFailure(exception)
            task
        }
        return task
    }

    // -- Helpers: region builder + assertions --

    private fun buildRegion(
        id: String = "test-geofence",
        latitude: Double = 37.7749,
        longitude: Double = -122.4194,
        radiusMeters: Float = 100f,
        transitionTypes: List<GeofenceTransitionType> = listOf(
            GeofenceTransitionType.ENTER,
            GeofenceTransitionType.EXIT
        )
    ) = GeofenceRegion(
        id = id,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        transitionTypes = transitionTypes
    )

    private infix fun Int.shouldContainFlag(flag: Int) {
        (this and flag == flag).shouldBeTrue()
    }

    private infix fun Int.shouldNotContainFlag(flag: Int) {
        (this and flag == flag).shouldBeFalse()
    }

    private infix fun <T> List<T>.shouldContainAll(expected: List<T>) {
        expected.forEach { item ->
            if (!this.contains(item)) throw AssertionError("Expected list to contain $item but it didn't: $this")
        }
    }
}
