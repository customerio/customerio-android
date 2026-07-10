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
import kotlinx.serialization.json.JsonPrimitive
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class GeofenceLifecycleObserverTest {

    private val owner: LifecycleOwner = mockk(relaxed = true)
    private val mockDeliveryFlusher: PendingDeliveryFlusher<PendingGeofenceDelivery> = mockk(relaxed = true)
    private val mockEventBus: EventBus = mockk(relaxed = true)
    private val mockRegionStore: GeofenceRegionStore = mockk(relaxed = true)
    private val mockLogger: GeofenceLogger = mockk(relaxed = true)

    private val observer = GeofenceLifecycleObserver(
        deliveryFlusher = mockDeliveryFlusher,
        eventBus = mockEventBus,
        regionStore = mockRegionStore,
        logger = mockLogger
    )

    @Test
    fun onStart_expectPendingDeliveriesFlushedOncePerForegroundEntry() {
        observer.onStart(owner)
        observer.onStart(owner)

        verify(exactly = 2) { mockDeliveryFlusher.flush(any(), any()) }
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
        every { mockDeliveryFlusher.flush(any(), capture(publishSlot)) } returns Unit
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
