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
import io.customer.messagingpush.logger.PushNotificationLogger
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
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
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
    private val mockPushLogger: PushNotificationLogger = mockk(relaxed = true)

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
                        overrideDependency<PushNotificationLogger>(mockPushLogger)
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
        // Clear the static observer ref so tests don't see leftovers across runs.
        // The observer itself is still attached to the process singleton's
        // LifecycleRegistry; we don't manipulate that because LifecycleRegistry
        // cannot be wound backward from DESTROYED, and ProcessLifecycleOwner is
        // not test-owned.
        ModuleMessagingPushFCM.foregroundObserver = null
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
    fun handoff_givenWorkManagerUnavailable_expectPublishWithoutCancelLogAndStillRemoveAll() = runTest {
        // Simulates the async-tracker schedule path (WM not initialized at enqueue
        // time): there is no work for cancelUniqueWork to act on, the handoff
        // still publishes and clears the store. The "cancelled WM" log must not
        // fire either — emitting it would mislead anyone debugging handoff via logs.
        every { mockWorkManagerProvider.getWorkManager() } returns null
        val entries = listOf(
            PendingPushDeliveryMetric(deliveryId = "d1", token = "t1", timestamp = 1L)
        )
        every { mockPendingStore.loadAll() } returns entries

        module.handoffPendingPushDeliveryToAnalyticsPipeline()

        verify(exactly = 0) { mockWorkManager.cancelUniqueWork(any()) }
        verify(exactly = 0) { mockPushLogger.logHandoffCancelledWorkManager(any()) }
        verify(exactly = 1) { mockPushLogger.logHandoffPublishedToEventBus("d1") }
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

    @Test
    fun handoff_givenCancelThrowsOnOneEntry_expectOtherEntriesStillProcessed() = runTest {
        // workManager.cancelUniqueWork(...).await() can throw. A failure on one
        // entry must not short-circuit the rest of the batch, and the failed
        // entry must stay in the store so the next foreground retries it
        // (publishing now without a successful cancel would risk double delivery).
        val entries = listOf(
            PendingPushDeliveryMetric(deliveryId = "boom", token = "t1", timestamp = 1L),
            PendingPushDeliveryMetric(deliveryId = "ok", token = "t2", timestamp = 2L)
        )
        every { mockPendingStore.loadAll() } returns entries
        val failingOperation: Operation = mockk(relaxed = true) {
            every { result } returns
                com.google.common.util.concurrent.Futures.immediateFailedFuture<Operation.State.SUCCESS>(
                    RuntimeException("cancel failed")
                )
        }
        every { mockWorkManager.cancelUniqueWork("boom") } returns failingOperation
        every { mockWorkManager.cancelUniqueWork("ok") } returns immediateSuccessfulOperation()

        module.handoffPendingPushDeliveryToAnalyticsPipeline()

        // First entry's publish was skipped because cancel threw.
        verify(exactly = 0) {
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Delivered,
                    deliveryId = "boom",
                    deviceToken = "t1"
                )
            )
        }
        // Second entry was still processed normally.
        verify(exactly = 1) {
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Delivered,
                    deliveryId = "ok",
                    deviceToken = "t2"
                )
            )
        }
        // Only the successfully-processed key is removed; "boom" stays for the next retry.
        verify(exactly = 1) { mockPendingStore.removeAll(listOf("ok")) }
        verify(exactly = 1) { mockPushLogger.logHandoffEntryFailed("boom", any()) }
    }

    @Test
    fun initialize_calledTwice_expectObserverReplacedNotAccumulated() = runTest {
        // ProcessLifecycleOwner is process-scoped — naively accumulating observers
        // across repeat-init calls would make every ON_START fire the handoff
        // once per initialize() ever called. The implementation must replace
        // the prior observer; we verify that bookkeeping here.
        module.initialize()
        val firstObserver = ModuleMessagingPushFCM.foregroundObserver
        firstObserver.shouldNotBeNull()

        ModuleMessagingPushFCM().initialize()
        val secondObserver = ModuleMessagingPushFCM.foregroundObserver
        secondObserver.shouldNotBeNull()

        // The static ref must point at the latest observer — the prior one was
        // removed from the process lifecycle before the new one was added.
        (secondObserver !== firstObserver).shouldBeTrue()
    }
}
