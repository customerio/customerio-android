package io.customer.messagingpush.processor

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.di.backgroundPushDeliveryMetrics
import io.customer.messagingpush.di.heldPushDeliveryStore
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.messagingpush.store.PushSettingsStore
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.data.store.PendingDeliveryStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundPushDeliveryMetricsTest : IntegrationTest() {
    private val mockPushLogger = mockk<PushNotificationLogger>(relaxed = true)
    private val mockScheduler = mockk<PushDeliveryMetricsBackgroundScheduler>(relaxed = true)
    private val mockPendingStore = mockk<PendingDeliveryStore<PendingPushDeliveryMetric>>(relaxed = true)
    private var installedAppVersion = "1"
    private lateinit var pushSettingsStore: PushSettingsStore

    private val metrics: BackgroundPushDeliveryMetrics
        get() = SDKComponent.backgroundPushDeliveryMetrics
    private val heldStore: PendingDeliveryStore<PendingPushDeliveryMetric>
        get() = SDKComponent.heldPushDeliveryStore

    override fun setup(testConfig: TestConfig) {
        pushSettingsStore = PushSettingsStore(applicationMock) { installedAppVersion }
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<PushNotificationLogger>(mockPushLogger)
                        overrideDependency<PushDeliveryMetricsBackgroundScheduler>(mockScheduler)
                        overrideDependency<PendingDeliveryStore<PendingPushDeliveryMetric>>(mockPendingStore)
                        overrideDependency<PushSettingsStore>(pushSettingsStore)
                    }
                }
            }
        )
        every { mockPendingStore.append(any<PendingPushDeliveryMetric>()) } returns true
    }

    private fun registerPushModule(autoTrackPushEvents: Boolean) {
        ModuleMessagingPushFCM(MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(autoTrackPushEvents).build())
            .attachToSDKComponent()
    }

    private fun metric() = PendingPushDeliveryMetric(deliveryId = String.random, token = String.random)

    private fun assertTracked(metric: PendingPushDeliveryMetric) {
        verify(exactly = 1) { mockPendingStore.append(metric) }
        verify(exactly = 1) { mockScheduler.scheduleDeliveredPushMetricsReceipt(metric.deliveryId, metric.token) }
    }

    private fun assertNothingTracked() {
        verify(exactly = 0) { mockPendingStore.append(any<PendingPushDeliveryMetric>()) }
        verify(exactly = 0) { mockScheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }
    }

    @Test
    fun trackDelivered_givenPushModuleNotRegistered_expectHoldUntilSettingsAreKnown() {
        val metric = metric()

        metrics.trackDelivered(metric.deliveryId, metric.token)

        heldStore.loadAll() shouldBeEqualTo listOf(metric)
        assertNothingTracked()
    }

    @Test
    fun trackDelivered_givenOnlyOtherModulesRegistered_expectHold() {
        // CustomerIO.initialize registers DataPipelines before the configured modules.
        SDKComponent.modules["DataPipelines"] = mockk<CustomerIOModule<CustomerIOModuleConfig>>(relaxed = true)
        val metric = metric()

        metrics.trackDelivered(metric.deliveryId, metric.token)

        heldStore.loadAll() shouldBeEqualTo listOf(metric)
        assertNothingTracked()
    }

    @Test
    fun trackDelivered_givenTrackingSavedOnBeforeSetUp_expectTrackRightAway() {
        val metric = metric()
        pushSettingsStore.saveAutoTrackPushEvents(true)

        metrics.trackDelivered(metric.deliveryId, metric.token)

        assertTracked(metric)
        heldStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun trackDelivered_givenTrackingSavedOffBeforeSetUp_expectDrop() {
        pushSettingsStore.saveAutoTrackPushEvents(false)

        metrics.trackDelivered(String.random, String.random)

        assertNothingTracked()
        heldStore.loadAll() shouldBeEqualTo emptyList()
        assertCalledOnce { mockPushLogger.logPushMetricsAutoTrackingDisabled() }
    }

    @Test
    fun trackDelivered_givenSettingSavedByOlderAppVersion_expectHold() {
        val metric = metric()
        pushSettingsStore.saveAutoTrackPushEvents(true)
        installedAppVersion = "2"

        metrics.trackDelivered(metric.deliveryId, metric.token)

        assertNothingTracked()
        heldStore.loadAll() shouldBeEqualTo listOf(metric)
    }

    @Test
    fun trackDelivered_givenHoldIsFull_expectDropOldest() {
        val deliveryIds = List(PendingPushDeliveryMetric.HELD_MAX_ENTRIES + 1) { String.random }

        deliveryIds.forEach { deliveryId -> metrics.trackDelivered(deliveryId, String.random) }

        heldStore.loadAll().map { it.deliveryId } shouldBeEqualTo deliveryIds.drop(1)
    }

    @Test
    fun trackDelivered_givenSameDeliveryTwice_expectTrackOnce() {
        val metric = metric()
        registerPushModule(autoTrackPushEvents = true)

        metrics.trackDelivered(metric.deliveryId, metric.token)
        metrics.trackDelivered(metric.deliveryId, metric.token)

        assertTracked(metric)
    }

    @Test
    fun trackDelivered_givenPendingSaveFails_expectHoldInsteadOfScheduling() {
        val metric = metric()
        registerPushModule(autoTrackPushEvents = true)
        every { mockPendingStore.append(any<PendingPushDeliveryMetric>()) } returns false

        metrics.trackDelivered(metric.deliveryId, metric.token)

        verify(exactly = 0) { mockScheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }
        heldStore.loadAll() shouldBeEqualTo listOf(metric)
    }

    @Test
    fun trackDelivered_givenSetUpWhileHolding_expectTrackRightAway() {
        val metric = metric()
        // The app finishes registering the push module right after the first config read.
        var registered = false
        val racingMetrics = BackgroundPushDeliveryMetrics(
            pushLogger = mockPushLogger,
            registeredModuleConfig = { MessagingPushModuleConfig.default().takeIf { registered }.also { registered = true } },
            pushSettingsStore = pushSettingsStore,
            heldStore = heldStore,
            pendingStore = mockPendingStore,
            deliveryMetricsScheduler = mockScheduler
        )

        racingMetrics.trackDelivered(metric.deliveryId, metric.token)

        assertTracked(metric)
        heldStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun trackDelivered_givenCreatedBeforeSetUp_expectSettingsFromLaterSetUpApply() {
        val metrics = metrics
        metrics.trackDelivered(String.random, String.random)
        registerPushModule(autoTrackPushEvents = false)

        metrics.trackDelivered(String.random, String.random)

        assertNothingTracked()
        heldStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun trackDelivered_givenSetUpSkippedRelease_expectReleaseMetricsHeldBeforeIt() {
        // An app without a silent push callback skips the release at setup.
        val heldBeforeSetUp = metric()
        metrics.trackDelivered(heldBeforeSetUp.deliveryId, heldBeforeSetUp.token)
        registerPushModule(autoTrackPushEvents = true)
        val afterSetUp = metric()

        metrics.trackDelivered(afterSetUp.deliveryId, afterSetUp.token)

        assertTracked(heldBeforeSetUp)
        assertTracked(afterSetUp)
        heldStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun releaseHeld_givenAutoTracking_expectTrackHeldMetrics() {
        val metric = metric()
        metrics.trackDelivered(metric.deliveryId, metric.token)
        registerPushModule(autoTrackPushEvents = true)

        metrics.releaseHeld()

        assertTracked(metric)
        heldStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun releaseHeld_givenAutoTrackingOff_expectDropHeldMetrics() {
        metrics.trackDelivered(String.random, String.random)
        registerPushModule(autoTrackPushEvents = false)

        metrics.releaseHeld()

        assertNothingTracked()
        heldStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun releaseHeld_givenPushModuleNotRegistered_expectKeepHolding() {
        metrics.trackDelivered(String.random, String.random)

        metrics.releaseHeld()

        assertNothingTracked()
        heldStore.loadAll().size shouldBeEqualTo 1
    }

    @Test
    fun releaseHeld_givenPendingSaveFails_expectKeepHeldAndSendOnNextRelease() {
        val metric = metric()
        metrics.trackDelivered(metric.deliveryId, metric.token)
        registerPushModule(autoTrackPushEvents = true)
        every { mockPendingStore.append(any<PendingPushDeliveryMetric>()) } returnsMany listOf(false, true)

        metrics.releaseHeld()

        heldStore.loadAll() shouldBeEqualTo listOf(metric)
        verify(exactly = 0) { mockScheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }

        metrics.releaseHeld()

        heldStore.loadAll() shouldBeEqualTo emptyList()
        verify(exactly = 2) { mockPendingStore.append(metric) }
        verify(exactly = 1) { mockScheduler.scheduleDeliveredPushMetricsReceipt(metric.deliveryId, metric.token) }
    }

    @Test
    fun releaseHeld_givenEntryClaimedByAnotherRelease_expectSkipIt() {
        val metric = metric()
        val claimedElsewhere = mockk<PendingDeliveryStore<PendingPushDeliveryMetric>>(relaxed = true)
        every { claimedElsewhere.loadAll() } returns listOf(metric)
        every { claimedElsewhere.claim(metric.key) } returns false
        val config = MessagingPushModuleConfig.default()
        val metrics = BackgroundPushDeliveryMetrics(mockPushLogger, { config }, pushSettingsStore, claimedElsewhere, mockPendingStore, mockScheduler)

        metrics.releaseHeld()

        assertNothingTracked()
    }

    @Test
    fun regularPush_givenTrackingSavedOffBeforeSetUp_expectTrackRightAwayAsOnMain() {
        val metric = metric()
        pushSettingsStore.saveAutoTrackPushEvents(false)

        SDKComponent.pushMessageProcessor.processRemoteMessageDeliveredMetrics(metric.deliveryId, metric.token)

        assertTracked(metric)
        heldStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun silentPushes_expectRegularDedupeQueueUntouched() {
        val deliveryIds = List(PushMessageProcessor.RECENT_MESSAGES_MAX_SIZE) { String.random }
        registerPushModule(autoTrackPushEvents = true)

        deliveryIds.forEach { deliveryId -> metrics.trackDelivered(deliveryId, String.random) }

        PushMessageProcessor.recentMessagesQueue.none { it in deliveryIds } shouldBeEqualTo true
    }
}
