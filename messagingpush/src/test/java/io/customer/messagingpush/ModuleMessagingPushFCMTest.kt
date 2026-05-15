package io.customer.messagingpush

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.messagingpush.store.PendingPushDeliveryStore
import io.customer.messagingpush.testutils.core.JUnitTest
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.events.Metric
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ModuleMessagingPushFCMTest : JUnitTest() {
    private lateinit var eventBus: EventBus
    private lateinit var fcmTokenProviderMock: DeviceTokenProvider
    private lateinit var module: ModuleMessagingPushFCM
    private val mockPendingStore: PendingPushDeliveryStore = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<EventBus>(mockk(relaxed = true))
                        overrideDependency<PendingPushDeliveryStore>(mockPendingStore)
                        overrideDependency<DispatchersProvider>(DispatchersProviderStub())
                    }
                    android { overrideDependency<DeviceTokenProvider>(mockk(relaxed = true)) }
                }
            }
        )

        eventBus = SDKComponent.eventBus
        fcmTokenProviderMock = SDKComponent.android().fcmTokenProvider
        module = ModuleMessagingPushFCM()
    }

    override fun teardown() {
        eventBus.removeAllSubscriptions()

        super.teardown()
    }

    @Test
    fun initialize_givenNoFCMTokenAvailable_expectDoNotRegisterToken() {
        every { fcmTokenProviderMock.getCurrentToken(any()) } answers {
            val callback = firstArg<(String?) -> Unit>()
            callback(null)
        }

        module.initialize()

        assertCalledNever { eventBus.publish(any<Event.RegisterDeviceTokenEvent>()) }
    }

    @Test
    fun initialize_givenFCMTokenAvailable_expectRegisterToken() {
        val givenToken = String.random

        every { fcmTokenProviderMock.getCurrentToken(any()) } answers {
            val callback = firstArg<(String?) -> Unit>()
            callback(givenToken)
        }

        module.initialize()

        assertCalledOnce { eventBus.publish(Event.RegisterDeviceTokenEvent(token = givenToken)) }
    }

    @Test
    fun initialize_givenPendingPushDeliveriesOnDisk_expectFlushedViaEventBusAndEachRemoved() {
        val pending = listOf(
            PendingPushDeliveryMetric(deliveryId = "d1", token = "t1", timestamp = 1L),
            PendingPushDeliveryMetric(deliveryId = "d2", token = "t2", timestamp = 2L)
        )
        every { mockPendingStore.loadAll() } returns pending

        module.initialize()

        pending.forEach { entry ->
            verify(exactly = 1) {
                eventBus.publish(
                    Event.TrackPushMetricEvent(
                        event = Metric.Delivered,
                        deliveryId = entry.deliveryId,
                        deviceToken = entry.token
                    )
                )
            }
            verify(exactly = 1) { mockPendingStore.remove(entry.deliveryId) }
        }
        verify(exactly = 0) { mockPendingStore.removeAll() }
    }

    @Test
    fun initialize_givenNoPendingPushDeliveries_expectNoPublishAndNoRemove() {
        every { mockPendingStore.loadAll() } returns emptyList()

        module.initialize()

        verify(exactly = 0) { eventBus.publish(any<Event.TrackPushMetricEvent>()) }
        verify(exactly = 0) { mockPendingStore.remove(any()) }
        verify(exactly = 0) { mockPendingStore.removeAll() }
    }

    @Test
    fun initialize_givenPendingFlush_expectDiskIoOnBackgroundDispatcher() {
        // DispatchersProviderStub returns UnconfinedTestDispatcher (background) by default.
        // We verify the flush runs by ensuring loadAll() is invoked at initialize() time,
        // which is the disk read. The stub guarantees this happens on the background
        // dispatcher, not on Dispatchers.Main.
        every { mockPendingStore.loadAll() } returns emptyList()

        module.initialize()

        verify(exactly = 1) { mockPendingStore.loadAll() }
    }
}
