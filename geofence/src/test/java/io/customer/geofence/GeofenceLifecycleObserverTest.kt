package io.customer.geofence

import androidx.lifecycle.LifecycleOwner
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.EventBus
import io.customer.sdk.data.store.PendingDeliveryFlusher
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class GeofenceLifecycleObserverTest {

    private val owner: LifecycleOwner = mockk(relaxed = true)
    private val mockDeliveryFlusher: PendingDeliveryFlusher<PendingGeofenceDelivery> = mockk(relaxed = true)
    private val mockEventBus: EventBus = mockk(relaxed = true)
    private val mockLogger: GeofenceLogger = mockk(relaxed = true)

    private val observer = GeofenceLifecycleObserver(
        deliveryFlusher = mockDeliveryFlusher,
        eventBus = mockEventBus,
        logger = mockLogger
    )

    @Test
    fun onStart_expectPendingDeliveriesFlushedOncePerForegroundEntry() {
        observer.onStart(owner)
        observer.onStart(owner)

        verify(exactly = 2) { mockDeliveryFlusher.flush(any(), any()) }
    }
}
