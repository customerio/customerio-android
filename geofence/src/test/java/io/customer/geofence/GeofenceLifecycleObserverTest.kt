package io.customer.geofence

import androidx.lifecycle.LifecycleOwner
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.data.store.PendingDeliveryFlusher
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.serialization.json.JsonPrimitive
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class GeofenceLifecycleObserverTest {

    private val owner: LifecycleOwner = mockk(relaxed = true)
    private val mockDeliveryFlusher: PendingDeliveryFlusher<PendingGeofenceDelivery> = mockk(relaxed = true)
    private val mockEventBus: EventBus = mockk(relaxed = true)
    private val mockRegionStore: GeofenceRegionStore = mockk(relaxed = true)
    private val mockLogger: GeofenceLogger = mockk(relaxed = true)

    // Granted-and-always by default; the two tier tests set their own.
    private val mockPermissionChecker: GeofencePermissionChecker = mockk(relaxed = true) {
        every { hasFineLocationPermission() } returns true
        every { isBackgroundDeliveryAvailable() } returns true
    }

    private var foregroundHookRuns = 0

    /**
     * The real reporter, not a double: the dedup these tests are about lives in it, and it is now
     * shared with module init rather than owned by the observer.
     */
    private val permissionReporter = GeofencePermissionReporter(
        permissionChecker = mockPermissionChecker,
        logger = mockLogger
    )

    private val observer = GeofenceLifecycleObserver(
        deliveryFlusher = mockDeliveryFlusher,
        eventBus = mockEventBus,
        regionStore = mockRegionStore,
        permissionReporter = permissionReporter,
        logger = mockLogger,
        onForeground = { foregroundHookRuns++ }
    )

    @Test
    fun reportIfChanged_givenAProcessThatNeverForegrounds_expectTheTierStillReported() {
        // The cold background wake: a geofence broadcast starts the process, module init runs, and
        // `onStart` never fires. Every capture of that session used to say nothing about the
        // permission the SDK was operating under — and that is the session a drive records.
        permissionReporter.reportIfChanged()

        verify(exactly = 1) { mockLogger.logPermissionTier(GeofenceLogger.PERMISSION_ALWAYS) }
    }

    @Test
    fun reportIfChanged_givenInitReportedThenTheAppForegrounds_expectNoSecondReport() {
        permissionReporter.reportIfChanged()

        observer.onStart(owner)

        verify(exactly = 1) { mockLogger.logPermissionTier(GeofenceLogger.PERMISSION_ALWAYS) }
    }

    @Test
    fun onStart_givenPermissionUnchanged_expectTierReportedOnceNotPerForeground() {
        observer.onStart(owner)
        observer.onStart(owner)
        observer.onStart(owner)

        verify(exactly = 1) { mockLogger.logPermissionTier(GeofenceLogger.PERMISSION_ALWAYS) }
    }

    @Test
    fun onStart_givenPermissionDowngradedBetweenForegrounds_expectBothTiersReported() {
        observer.onStart(owner)
        every { mockPermissionChecker.isBackgroundDeliveryAvailable() } returns false
        observer.onStart(owner)
        every { mockPermissionChecker.hasFineLocationPermission() } returns false
        observer.onStart(owner)

        verifyOrder {
            mockLogger.logPermissionTier(GeofenceLogger.PERMISSION_ALWAYS)
            mockLogger.logPermissionTier(GeofenceLogger.PERMISSION_WHEN_IN_USE)
            mockLogger.logPermissionTier(GeofenceLogger.PERMISSION_DENIED)
        }
    }

    @Test
    fun onStart_expectPendingDeliveriesFlushedOncePerForegroundEntry() {
        observer.onStart(owner)
        observer.onStart(owner)

        // At-least-once so a crash between publish and row-removal re-delivers rather than drops.
        verify(exactly = 2) {
            mockDeliveryFlusher.flush(any(), PendingDeliveryFlusher.DeliveryGuarantee.AT_LEAST_ONCE, any())
        }
    }

    @Test
    fun onStart_expectForegroundHookRunOncePerEntry() {
        observer.onStart(owner)
        observer.onStart(owner)

        foregroundHookRuns shouldBeEqualTo 2
    }

    @Test
    fun onStart_expectPublishedEventUsesFreshCachedNameAndMetadata() {
        // The flush path must apply the same cache-preferred hybrid as the worker path, so a fence
        // still in cache publishes its current name/metadata over the row's crossing-time snapshot.
        every { mockRegionStore.getCachedRegion("g1") } returns GeofenceRegion(
            id = "g1",
            latitude = 1.0,
            longitude = 2.0,
            radius = 100f,
            name = "Fresh",
            metadata = mapOf("k" to JsonPrimitive("fresh"))
        )
        val publishSlot = slot<(PendingGeofenceDelivery) -> Unit>()
        every { mockDeliveryFlusher.flush(any(), any(), capture(publishSlot)) } returns Unit
        val eventSlot = slot<Event>()
        every { mockEventBus.publish(capture(eventSlot)) } returns Unit

        observer.onStart(owner)
        publishSlot.captured.invoke(
            PendingGeofenceDelivery(
                "g1",
                Event.GeofenceTransition.ENTER,
                50L,
                "user-A",
                transitionId = "tid-1",
                geofenceName = "Stale",
                metadata = mapOf("k" to JsonPrimitive("stale"))
            )
        )

        val published = eventSlot.captured as Event.GeofenceTransitionEvent
        published.properties["geofenceName"] shouldBeEqualTo "Fresh"

        @Suppress("UNCHECKED_CAST")
        val metadata = published.properties["metadata"] as Map<String, Any>
        metadata["k"] shouldBeEqualTo "fresh"
    }
}
