package io.customer.messagingpush

import androidx.work.Operation
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.events.Metric
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModuleMessagingPushFCMTest : IntegrationTest() {
    private lateinit var eventBus: EventBus
    private lateinit var fcmTokenProviderMock: DeviceTokenProvider
    private lateinit var module: ModuleMessagingPushFCM
    private val mockPendingStore: PendingDeliveryStore<PendingPushDeliveryMetric> = mockk(relaxed = true)
    private val mockWorkManager: WorkManager = mockk(relaxed = true)
    private val mockWorkManagerProvider: CustomerIOWorkManagerProvider = mockk(relaxed = true)

    private fun immediateSuccessfulOperation(): Operation = mockk(relaxed = true) {
        every { result } returns Futures.immediateFuture(Operation.SUCCESS)
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<EventBus>(mockk(relaxed = true))
                        overrideDependency<PendingDeliveryStore<PendingPushDeliveryMetric>>(mockPendingStore)
                        overrideDependency<CustomerIOWorkManagerProvider>(mockWorkManagerProvider)
                        overrideDependency<DispatchersProvider>(DispatchersProviderStub())
                    }
                    android { overrideDependency<DeviceTokenProvider>(mockk(relaxed = true)) }
                }
            }
        )

        every { mockWorkManagerProvider.getWorkManager() } returns mockWorkManager
        every { mockWorkManager.cancelUniqueWork(any()) } returns immediateSuccessfulOperation()

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
    fun initialize_doesNotFlushPendingDeliveryFromInit() {
        // The handoff is triggered by ProcessLifecycleOwner.ON_START, not by module init —
        // an FCM-woken background process that initializes the SDK must not flush
        // because background-process network conditions make WorkManager the only
        // credible channel.
        every { mockPendingStore.loadAll() } returns listOf(
            PendingPushDeliveryMetric(deliveryId = "d1", token = "t1", timestamp = 1L)
        )

        module.initialize()

        verify(exactly = 0) { eventBus.publish(any<Event.TrackPushMetricEvent>()) }
        verify(exactly = 0) { mockPendingStore.removeAll(any<Collection<String>>()) }
        verify(exactly = 0) { mockWorkManager.cancelUniqueWork(any()) }
    }

    @Test
    fun handoff_givenPendingEntries_expectCancelThenPublishThenRemoveAll() = runTest {
        val entries = listOf(
            PendingPushDeliveryMetric(deliveryId = "d1", token = "t1", timestamp = 1L),
            PendingPushDeliveryMetric(deliveryId = "d2", token = "t2", timestamp = 2L)
        )
        every { mockPendingStore.loadAll() } returns entries

        module.handoffPendingPushDeliveryToAnalyticsPipeline()

        // For each entry: cancel happens before publish. Reversing the order
        // would widen the double-delivery window.
        verifyOrder {
            mockWorkManager.cancelUniqueWork("d1")
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Delivered,
                    deliveryId = "d1",
                    deviceToken = "t1"
                )
            )
            mockWorkManager.cancelUniqueWork("d2")
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Delivered,
                    deliveryId = "d2",
                    deviceToken = "t2"
                )
            )
        }
        // Only snapshotted keys are removed at the end — entries appended mid-handoff survive.
        verify(exactly = 1) { mockPendingStore.removeAll(listOf("d1", "d2")) }
        verify(exactly = 0) { mockPendingStore.remove(any()) }
    }

    @Test
    fun handoff_givenEmptyStore_expectNoCancelNoPublishNoRemove() = runTest {
        every { mockPendingStore.loadAll() } returns emptyList()

        module.handoffPendingPushDeliveryToAnalyticsPipeline()

        verify(exactly = 0) { mockWorkManager.cancelUniqueWork(any()) }
        verify(exactly = 0) { eventBus.publish(any<Event.TrackPushMetricEvent>()) }
        verify(exactly = 0) { mockPendingStore.removeAll(any<Collection<String>>()) }
    }

    @Test
    fun handoff_givenWorkManagerUnavailable_expectPublishWithoutCancelAndStillRemoveAll() = runTest {
        // Simulates the async-tracker schedule path (WM not initialized at enqueue
        // time): there is no work for cancelUniqueWork to act on, the handoff
        // still publishes and clears the store.
        every { mockWorkManagerProvider.getWorkManager() } returns null
        val entries = listOf(
            PendingPushDeliveryMetric(deliveryId = "d1", token = "t1", timestamp = 1L)
        )
        every { mockPendingStore.loadAll() } returns entries

        module.handoffPendingPushDeliveryToAnalyticsPipeline()

        verify(exactly = 0) { mockWorkManager.cancelUniqueWork(any()) }
        verify(exactly = 1) {
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Delivered,
                    deliveryId = "d1",
                    deviceToken = "t1"
                )
            )
        }
        verify(exactly = 1) { mockPendingStore.removeAll(listOf("d1")) }
    }
}
