package io.customer.geofence

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
import io.customer.geofence.di.geofenceLogger
import io.customer.sdk.core.di.SDKComponent
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
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
        // Use real GeofencePermissionChecker so Robolectric's grantPermission/denyPermission
        // helpers continue to control the manager's permission-check path.
        manager = GeofenceManager(
            context = applicationMock,
            client = client,
            receiverToggle = receiverToggle,
            permissionChecker = GeofencePermissionChecker(applicationMock),
            logger = SDKComponent.geofenceLogger
        )
    }

    @Test
    fun replaceGeofences_givenEmptyList_expectReceiverDisabledAndNoClientCall() = runTest {
        // Account with no geofences => receiver must be disabled so the SDK doesn't
        // burn resources listening for events that can't fire. Covers both fresh
        // accounts and accounts that transitioned to 0 after a refresh.
        val result = manager.replaceGeofences(emptyList())

        result.isSuccess.shouldBeTrue()
        verify(exactly = 0) { client.addGeofences(any<GeofencingRequest>(), any()) }
        verify { receiverToggle.setEnabled(false) }
    }

    @Test
    fun replaceGeofences_givenNoFineLocationPermission_expectFailure() = runTest {
        denyPermission(Manifest.permission.ACCESS_FINE_LOCATION)

        val result = manager.replaceGeofences(listOf(buildRegion()))

        result.isFailure.shouldBeTrue()
        verify(exactly = 0) { client.addGeofences(any<GeofencingRequest>(), any()) }
    }

    @Test
    fun replaceGeofences_givenFineLocationGrantedNoBackground_expectRegistrationProceeds() = runTest {
        // GMS only requires FINE to register; BACKGROUND only gates whether
        // transitions are delivered while the app is backgrounded. The host gets a
        // foreground-only degraded mode rather than silent zero-registration.
        grantPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        denyPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        stubClientAddSuccess()

        val result = manager.replaceGeofences(listOf(buildRegion()))

        result.isSuccess.shouldBeTrue()
        verify { client.addGeofences(any<GeofencingRequest>(), any()) }
    }

    @Test
    fun replaceGeofences_givenAllPermissionsGranted_expectClientCalled() = runTest {
        grantAllPermissions()
        stubClientAddSuccess()

        val result = manager.replaceGeofences(listOf(buildRegion()))

        result.isSuccess.shouldBeTrue()
        verify { client.addGeofences(any<GeofencingRequest>(), any()) }
    }

    @Test
    fun replaceGeofences_givenBusinessGeofences_expectInitialTriggerEnter() = runTest {
        grantAllPermissions()

        val requestSlot = slot<GeofencingRequest>()
        stubClientAddSuccess(requestSlot)

        manager.replaceGeofences(listOf(buildRegion(id = "business-1")))

        requestSlot.captured.initialTrigger shouldContainFlag GeofencingRequest.INITIAL_TRIGGER_ENTER
    }

    @Test
    fun replaceGeofences_givenMovementTrigger_expectInitialTriggerEnter() = runTest {
        // Movement trigger registered with INITIAL_TRIGGER_ENTER so GMS records
        // state as INSIDE at the new center — without this, prior OUTSIDE state
        // from the previous EXIT would block future EXITs from firing.
        grantAllPermissions()

        val requestSlot = slot<GeofencingRequest>()
        stubClientAddSuccess(requestSlot)
        stubClientRemoveByIdsSuccess()

        manager.replaceGeofences(
            listOf(buildRegion(id = GeofenceConstants.MOVEMENT_TRIGGER_ID))
        )

        requestSlot.captured.initialTrigger shouldContainFlag GeofencingRequest.INITIAL_TRIGGER_ENTER
    }

    @Test
    fun replaceGeofences_givenMovementTrigger_expectPriorRegistrationRemovedFirst() = runTest {
        // GMS caches per-ID transition state across same-ID addGeofences. Removing
        // the prior registration first forces a truly fresh state machine so the
        // next EXIT will fire.
        grantAllPermissions()
        stubClientAddSuccess()
        stubClientRemoveByIdsSuccess()

        manager.replaceGeofences(
            listOf(buildRegion(id = GeofenceConstants.MOVEMENT_TRIGGER_ID))
        )

        verify { client.removeGeofences(listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID)) }
    }

    @Test
    fun replaceGeofencesForBootRestore_givenMovementTrigger_expectInitialTriggerExit() = runTest {
        grantAllPermissions()

        val requestSlot = slot<GeofencingRequest>()
        stubClientAddSuccess(requestSlot)
        stubClientRemoveByIdsSuccess()

        manager.replaceGeofencesForBootRestore(
            listOf(buildRegion(id = GeofenceConstants.MOVEMENT_TRIGGER_ID))
        )

        requestSlot.captured.initialTrigger shouldContainFlag GeofencingRequest.INITIAL_TRIGGER_EXIT
    }

    @Test
    fun replaceGeofencesForBootRestore_givenBusinessGeofences_expectInitialTriggerEnterUnchanged() = runTest {
        // Only the movement trigger differs between variants; business batch
        // still uses INITIAL_TRIGGER_ENTER.
        grantAllPermissions()

        val requestSlot = slot<GeofencingRequest>()
        stubClientAddSuccess(requestSlot)

        manager.replaceGeofencesForBootRestore(listOf(buildRegion(id = "business-1")))

        requestSlot.captured.initialTrigger shouldContainFlag GeofencingRequest.INITIAL_TRIGGER_ENTER
    }

    @Test
    fun replaceGeofences_givenMixedBatch_expectSeparateRequestsWithCorrectTriggers() = runTest {
        grantAllPermissions()

        val requests = mutableListOf<GeofencingRequest>()
        val task = immediateSuccessTask<Void>()
        every { client.addGeofences(capture(requests), any()) } returns task
        stubClientRemoveByIdsSuccess()

        manager.replaceGeofences(
            listOf(
                buildRegion(id = GeofenceConstants.MOVEMENT_TRIGGER_ID),
                buildRegion(id = "business-1")
            )
        )

        requests.size shouldBeEqualTo 2
        requests[0].initialTrigger shouldContainFlag GeofencingRequest.INITIAL_TRIGGER_ENTER
        requests[0].geofences.first().requestId shouldBeEqualTo GeofenceConstants.MOVEMENT_TRIGGER_ID
        requests[1].initialTrigger shouldContainFlag GeofencingRequest.INITIAL_TRIGGER_ENTER
        requests[1].geofences.first().requestId shouldBeEqualTo "business-1"
    }

    @Test
    fun replaceGeofences_givenMixedBatchWithBusinessFailure_expectMovementTriggerRolledBack() = runTest {
        // Business-batch failure is a rare edge case (transient OS / GMS issue).
        // Rather than leave a stand-alone movement trigger in the OS, we roll it
        // back so we don't carry safety-net state we can't act on. Recovery
        // happens on the next identify/app-launch trigger past the freshness
        // threshold — see `GeofenceConstants.STALE_THRESHOLD_MS`.
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

        val result = manager.replaceGeofences(
            listOf(
                buildRegion(id = GeofenceConstants.MOVEMENT_TRIGGER_ID),
                buildRegion(id = "business-1")
            )
        )

        result.isFailure.shouldBeTrue()
        verify { client.removeGeofences(listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID)) }
        verify(exactly = 0) { receiverToggle.setEnabled(true) }
    }

    @Test
    fun replaceGeofences_givenRegion_expectGmsGeofenceBuiltCorrectly() = runTest {
        grantAllPermissions()

        val requestSlot = slot<GeofencingRequest>()
        stubClientAddSuccess(requestSlot)

        val region = buildRegion(
            id = "geo-123",
            latitude = 40.7128,
            longitude = -74.0060,
            radius = 250f,
            transitionTypes = listOf(GeofenceTransitionType.ENTER)
        )
        manager.replaceGeofences(listOf(region))

        val gmsGeofence = requestSlot.captured.geofences.first()
        gmsGeofence.requestId shouldBeEqualTo "geo-123"
        gmsGeofence.transitionTypes shouldBeEqualTo Geofence.GEOFENCE_TRANSITION_ENTER
        gmsGeofence.expirationTime shouldBeEqualTo GeofenceConstants.GEOFENCE_EXPIRATION_NEVER
    }

    @Test
    fun replaceGeofences_givenSuccess_expectReceiversEnabled() = runTest {
        grantAllPermissions()
        stubClientAddSuccess()

        manager.replaceGeofences(listOf(buildRegion()))

        verify { receiverToggle.setEnabled(true) }
    }

    @Test
    fun replaceGeofences_givenFailure_expectReceiversNotToggled() = runTest {
        grantAllPermissions()
        stubClientAddFailure(RuntimeException("GMS error"))

        manager.replaceGeofences(listOf(buildRegion()))

        verify(exactly = 0) { receiverToggle.setEnabled(any()) }
    }

    @Test
    fun replaceGeofences_givenClientFails_expectFailureResult() = runTest {
        grantAllPermissions()
        stubClientAddFailure(RuntimeException("GMS error"))

        val result = manager.replaceGeofences(listOf(buildRegion()))

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
    fun removeGeofencesByIds_givenClientThrowsSecurityException_expectFailureResultNotPropagated() = runTest {
        // Synchronous SecurityException (e.g., permission revoked) must be converted
        // to Result.failure — matches the registerBatch contract so callers can rely
        // on "manager methods always return Result, never throw."
        val securityException = SecurityException("permission revoked")
        every { client.removeGeofences(any<List<String>>()) } throws securityException

        val result = manager.removeGeofencesByIds(listOf("geo-1"))

        result.isFailure.shouldBeTrue()
        result.exceptionOrNull() shouldBeEqualTo securityException
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
        radius: Float = 100f,
        transitionTypes: List<GeofenceTransitionType> = listOf(
            GeofenceTransitionType.ENTER,
            GeofenceTransitionType.EXIT
        )
    ) = GeofenceRegion(
        id = id,
        latitude = latitude,
        longitude = longitude,
        radius = radius,
        transitionTypes = transitionTypes
    )

    private infix fun Int.shouldContainFlag(flag: Int) {
        (this and flag == flag).shouldBeTrue()
    }

    private infix fun <T> List<T>.shouldContainAll(expected: List<T>) {
        expected.forEach { item ->
            if (!this.contains(item)) throw AssertionError("Expected list to contain $item but it didn't: $this")
        }
    }
}
