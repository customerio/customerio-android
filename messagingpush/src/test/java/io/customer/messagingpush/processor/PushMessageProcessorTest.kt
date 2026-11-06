package io.customer.messagingpush.processor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.config.PushClickBehavior.ACTIVITY_PREVENT_RESTART
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.deepLinkUtil
import io.customer.messagingpush.di.heldPushDeliveryStore
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.messagingpush.store.PushSettingsStore
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.events.Metric
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class PushMessageProcessorTest : IntegrationTest() {
    private lateinit var deepLinkUtilMock: DeepLinkUtil
    private lateinit var eventBus: EventBus
    private val mockPushLogger = mockk<PushNotificationLogger>(relaxed = true)
    private val mockDeliveryMetricsScheduler = mockk<PushDeliveryMetricsBackgroundScheduler>(relaxed = true)
    private val mockPendingStore = mockk<PendingDeliveryStore<PendingPushDeliveryMetric>>(relaxed = true)
    private var installedAppVersion = "1"
    private lateinit var pushSettingsStore: PushSettingsStore

    private fun pushMessageProcessor(): PushMessageProcessorImpl {
        return SDKComponent.pushMessageProcessor as PushMessageProcessorImpl
    }

    override fun setup(testConfig: TestConfig) {
        pushSettingsStore = PushSettingsStore(applicationMock) { installedAppVersion }
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<DeepLinkUtil>(mockk())
                        overrideDependency<EventBus>(mockk(relaxed = true))
                        overrideDependency<PushNotificationLogger>(mockPushLogger)
                        overrideDependency<PushDeliveryMetricsBackgroundScheduler>(mockDeliveryMetricsScheduler)
                        overrideDependency<PendingDeliveryStore<PendingPushDeliveryMetric>>(mockPendingStore)
                        overrideDependency<PushSettingsStore>(pushSettingsStore)
                    }
                }
            }
        )

        deepLinkUtilMock = SDKComponent.deepLinkUtil
        eventBus = SDKComponent.eventBus
        every { mockPendingStore.append(any<PendingPushDeliveryMetric>()) } returns true
    }

    private fun pushMessagePayload(deepLink: String? = null): CustomerIOParsedPushPayload {
        return CustomerIOParsedPushPayload(
            extras = Bundle.EMPTY,
            deepLink = deepLink,
            cioDeliveryId = String.random,
            cioDeliveryToken = String.random,
            title = String.random,
            body = String.random
        )
    }

    private fun Intent.withTestFlags(): Intent = apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun emptyIntent(): Intent = Intent()

    private fun setupDeepLinkResponse(
        deepLink: String?,
        defaultHostAppIntent: Intent? = null,
        linkHostAppIntent: Intent? = null,
        linkExternalIntent: Intent? = null
    ) {
        every { deepLinkUtilMock.createDefaultHostAppIntent(any()) } returns defaultHostAppIntent
        if (deepLink == null) {
            return
        }

        every { deepLinkUtilMock.createDeepLinkHostAppIntent(any(), deepLink) } returns linkHostAppIntent
        every { deepLinkUtilMock.createDeepLinkExternalIntent(any(), deepLink) } returns linkExternalIntent
    }

    private fun setupModuleConfig(
        pushClickBehavior: PushClickBehavior? = null,
        autoTrackPushEvents: Boolean? = null,
        notificationCallback: CustomerIOPushNotificationCallback? = null
    ) = ModuleMessagingPushFCM(
        moduleConfig = with(MessagingPushModuleConfig.Builder()) {
            autoTrackPushEvents?.let { setAutoTrackPushEvents(it) }
            notificationCallback?.let { setNotificationCallback(it) }
            pushClickBehavior?.let { setPushClickBehavior(it) }
            build()
        }
    ).attachToSDKComponent()

    @Test
    fun processMessage_givenDeliveryDataInvalid_expectDoNoProcessPush() {
        val givenDeliveryId = ""
        val processor = pushMessageProcessor()

        val result = processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryId)

        result.shouldBeTrue()
    }

    @Test
    fun processMessage_givenDeliveryDataInvalid_expectLogEmptyDeliveryId() {
        val givenDeliveryId = ""
        val processor = pushMessageProcessor()

        processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryId)

        assertCalledOnce { mockPushLogger.logReceivedPushMessageWithEmptyDeliveryId() }
    }

    @Test
    fun processMessage_givenMessageReceivedMultipleTimes_expectDoNoProcessPushMoreThanOnce() {
        val givenDeliveryId = String.random
        val processor = pushMessageProcessor()

        val resultFirst = processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryId)
        val resultSecond = processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryId)
        val resultThird = processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryId)

        resultFirst.shouldBeFalse()
        resultSecond.shouldBeTrue()
        resultThird.shouldBeTrue()
    }

    @Test
    fun processMessage_givenMessageReceivedMultipleTimes_expectLogDuplicateDeliveryId() {
        val givenDeliveryId = String.random
        val processor = pushMessageProcessor()

        processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryId)
        processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryId)

        assertCalledOnce { mockPushLogger.logReceivedDuplicatePushMessageDeliveryId(givenDeliveryId) }
    }

    @Test
    fun processMessage_givenNewMessageReceived_expectProcessPush() {
        val givenDeliveryId = String.random
        val processor = pushMessageProcessor()

        val result = processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryId)

        result.shouldBeFalse()
    }

    @Test
    fun processMessage_givenNewMessageReceived_expectLogNewMessageWithDeliveryId() {
        val givenDeliveryId = String.random
        val processor = pushMessageProcessor()

        processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryId)

        assertCalledOnce { mockPushLogger.logReceivedNewMessageWithDeliveryId(givenDeliveryId) }
    }

    @Test
    fun processMessage_givenQueueLimitReached_expectOldestToBeRemoved() {
        val processor = pushMessageProcessor()
        // Push first message and ignore result
        val givenDeliveryIdOldest = String.random
        processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryIdOldest)
        // Fill the queue with random messages and ignore results
        for (i in 1..PushMessageProcessor.RECENT_MESSAGES_MAX_SIZE) {
            processor.getOrUpdateMessageAlreadyProcessed(String.random)
        }
        // Push last message and ignore result
        // Pushing this message should remove first message from the queue
        val givenDeliveryIdRecent = String.random
        processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryIdRecent)

        val resultOldest = processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryIdOldest)
        val resultRecent = processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryIdRecent)

        resultOldest.shouldBeFalse()
        resultRecent.shouldBeTrue()
    }

    @Test
    fun processGCMMessageIntent_givenBundleWithoutDeliveryData_expectDoNoTrackPush() {
        val givenBundle = Bundle().apply {
            putString("message_id", String.random)
        }
        val processor = pushMessageProcessor()
        val gcmIntent: Intent = mockk(relaxed = true)
        every { gcmIntent.extras } returns givenBundle

        processor.processGCMMessageIntent(gcmIntent)

        assertNoInteractions(eventBus)
    }

    @Test
    fun processGCMMessageIntent_givenAutoTrackPushEventsDisabled_expectDoNoTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        val givenBundle = Bundle().apply {
            putString(PushTrackingUtil.DELIVERY_ID_KEY, givenDeliveryId)
            putString(PushTrackingUtil.DELIVERY_TOKEN_KEY, givenDeviceToken)
        }
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setAutoTrackPushEvents(false)
                .build()
        ).attachToSDKComponent()
        val processor = pushMessageProcessor()
        val gcmIntent: Intent = mockk(relaxed = true)
        every { gcmIntent.extras } returns givenBundle

        processor.processGCMMessageIntent(gcmIntent)

        assertNoInteractions(eventBus)
        verify(exactly = 0) {
            mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(any(), any())
        }
        verify(exactly = 0) {
            mockPendingStore.append(any<PendingPushDeliveryMetric>())
        }
    }

    @Test
    fun processGCMMessageIntent_givenAutoTrackPushEventsEnabled_expectTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        val givenBundle = Bundle().apply {
            putString(PushTrackingUtil.DELIVERY_ID_KEY, givenDeliveryId)
            putString(PushTrackingUtil.DELIVERY_TOKEN_KEY, givenDeviceToken)
        }
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setAutoTrackPushEvents(true)
                .build()
        )
        val processor = pushMessageProcessor()
        val gcmIntent: Intent = mockk(relaxed = true)
        every { gcmIntent.extras } returns givenBundle

        processor.processGCMMessageIntent(gcmIntent)

        // The initial delivery path no longer dual-emits via the event bus; the
        // pending store + WorkManager (or fallback) carry the metric to the
        // backend, and the analytics-pipeline emission happens at the next
        // foreground transition via the handoff.
        verify(exactly = 0) {
            eventBus.publish(any<Event.TrackPushMetricEvent>())
        }

        val appendedSlot = slot<PendingPushDeliveryMetric>()
        verify(exactly = 1) { mockPendingStore.append(capture(appendedSlot)) }
        appendedSlot.captured.deliveryId shouldBeEqualTo givenDeliveryId
        appendedSlot.captured.token shouldBeEqualTo givenDeviceToken

        verify(exactly = 1) {
            mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(
                deliveryId = givenDeliveryId,
                deliveryToken = givenDeviceToken
            )
        }
    }

    @Test
    fun processRemoteMessageDeliveredMetrics_givenAutoTrackPushEventsDisabled_expectDoNoTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setAutoTrackPushEvents(false)
                .build()
        ).attachToSDKComponent()
        val processor = pushMessageProcessor()

        processor.processRemoteMessageDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        assertNoInteractions(eventBus)
        verify(exactly = 0) {
            mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(any(), any())
        }
        verify(exactly = 0) {
            mockPendingStore.append(any<PendingPushDeliveryMetric>())
        }
    }

    @Test
    fun processRemoteMessageDeliveredMetrics_givenAutoTrackPushEventsDisabled_expectLogAutoTrackingDisabled() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setAutoTrackPushEvents(false)
                .build()
        ).attachToSDKComponent()
        val processor = pushMessageProcessor()

        processor.processRemoteMessageDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        assertCalledOnce { mockPushLogger.logPushMetricsAutoTrackingDisabled() }
        verify(exactly = 0) {
            mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(any(), any())
        }
    }

    @Test
    fun processRemoteMessageDeliveredMetrics_givenAutoTrackPushEventsEnabled_expectTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setAutoTrackPushEvents(true)
                .build()
        ).attachToSDKComponent()
        val processor = pushMessageProcessor()

        processor.processRemoteMessageDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        verify(exactly = 0) {
            eventBus.publish(any<Event.TrackPushMetricEvent>())
        }

        val appendedSlot = slot<PendingPushDeliveryMetric>()
        verify(exactly = 1) { mockPendingStore.append(capture(appendedSlot)) }
        appendedSlot.captured.deliveryId shouldBeEqualTo givenDeliveryId
        appendedSlot.captured.token shouldBeEqualTo givenDeviceToken

        verify(exactly = 1) {
            mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(
                deliveryId = givenDeliveryId,
                deliveryToken = givenDeviceToken
            )
        }
    }

    @Test
    fun processRemoteMessageDeliveredMetrics_givenAutoTrackPushEventsEnabled_expectLogTrackingPushDelivered() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setAutoTrackPushEvents(true)
                .build()
        ).attachToSDKComponent()
        val processor = pushMessageProcessor()

        processor.processRemoteMessageDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        assertCalledOnce { mockPushLogger.logTrackingPushMessageDelivered(givenDeliveryId) }
    }

    @Test
    fun processRemoteMessageDeliveredMetrics_givenSdkNotSetUpAndTrackingSavedOff_expectTrackRightAwayAsOnMain() {
        // Regular pushes ignore the silent push hold and saved setting.
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        pushSettingsStore.saveAutoTrackPushEvents(false)

        pushMessageProcessor().processRemoteMessageDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        verify(exactly = 1) { mockPendingStore.append(PendingPushDeliveryMetric(givenDeliveryId, givenDeviceToken)) }
        verify(exactly = 1) { mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(givenDeliveryId, givenDeviceToken) }
        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun processBackgroundPushDeliveredMetrics_givenSdkNotSetUp_expectHoldUntilSettingsAreKnown() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random

        pushMessageProcessor().processBackgroundPushDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo listOf(PendingPushDeliveryMetric(givenDeliveryId, givenDeviceToken))
        verify(exactly = 0) { mockPendingStore.append(any<PendingPushDeliveryMetric>()) }
        verify(exactly = 0) { mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }
    }

    @Test
    fun processBackgroundPushDeliveredMetrics_givenTrackingSavedOnBeforeSetUp_expectTrackRightAway() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        pushSettingsStore.saveAutoTrackPushEvents(true)

        pushMessageProcessor().processBackgroundPushDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        verify(exactly = 1) { mockPendingStore.append(PendingPushDeliveryMetric(givenDeliveryId, givenDeviceToken)) }
        verify(exactly = 1) { mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(givenDeliveryId, givenDeviceToken) }
        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun processBackgroundPushDeliveredMetrics_givenTrackingSavedOffBeforeSetUp_expectDrop() {
        pushSettingsStore.saveAutoTrackPushEvents(false)

        pushMessageProcessor().processBackgroundPushDeliveredMetrics(String.random, String.random)

        verify(exactly = 0) { mockPendingStore.append(any<PendingPushDeliveryMetric>()) }
        verify(exactly = 0) { mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }
        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo emptyList()
        assertCalledOnce { mockPushLogger.logPushMetricsAutoTrackingDisabled() }
    }

    @Test
    fun processBackgroundPushDeliveredMetrics_givenSettingSavedByOlderAppVersion_expectHold() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        pushSettingsStore.saveAutoTrackPushEvents(true)
        installedAppVersion = "2"

        pushMessageProcessor().processBackgroundPushDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        verify(exactly = 0) { mockPendingStore.append(any<PendingPushDeliveryMetric>()) }
        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo listOf(PendingPushDeliveryMetric(givenDeliveryId, givenDeviceToken))
    }

    @Test
    fun processBackgroundPushDeliveredMetrics_givenHoldIsFull_expectDropOldest() {
        val processor = pushMessageProcessor()
        val givenDeliveryIds = List(PendingPushDeliveryMetric.HELD_MAX_ENTRIES + 1) { String.random }

        givenDeliveryIds.forEach { deliveryId -> processor.processBackgroundPushDeliveredMetrics(deliveryId, String.random) }

        SDKComponent.heldPushDeliveryStore.loadAll().map { it.deliveryId } shouldBeEqualTo givenDeliveryIds.drop(1)
    }

    @Test
    fun processBackgroundPushDeliveredMetrics_givenSdkSetUpWhileHolding_expectTrackRightAway() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        // The app finishes setting up the SDK right after the processor finds no config.
        var setUp = false
        val processor = PushMessageProcessorImpl(
            pushLogger = mockPushLogger,
            moduleConfig = MessagingPushModuleConfig.default(),
            deepLinkUtil = deepLinkUtilMock,
            deliveryMetricsScheduler = mockDeliveryMetricsScheduler,
            pendingPushDeliveryStore = mockPendingStore,
            currentModuleConfig = { MessagingPushModuleConfig.default().takeIf { setUp }.also { setUp = true } },
            heldPushDeliveryStore = SDKComponent.heldPushDeliveryStore,
            pushSettingsStore = pushSettingsStore
        )

        processor.processBackgroundPushDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        verify(exactly = 1) { mockPendingStore.append(PendingPushDeliveryMetric(givenDeliveryId, givenDeviceToken)) }
        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun processBackgroundPushDeliveredMetrics_givenProcessorCreatedBeforeSetUp_expectSettingsFromLaterSetUpApply() {
        // A silent push that arrives before the app sets up the SDK creates the processor while only the defaults exist.
        val processor = pushMessageProcessor()
        processor.processBackgroundPushDeliveredMetrics(String.random, String.random)
        setupModuleConfig(autoTrackPushEvents = false)
        processor.processHeldDeliveredMetrics()

        processor.processBackgroundPushDeliveredMetrics(String.random, String.random)

        verify(exactly = 0) { mockPendingStore.append(any<PendingPushDeliveryMetric>()) }
        verify(exactly = 0) { mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }
        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun processHeldDeliveredMetrics_givenSdkSetUpWithAutoTracking_expectTrackHeldMetrics() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        val processor = pushMessageProcessor()
        processor.processBackgroundPushDeliveredMetrics(givenDeliveryId, givenDeviceToken)
        setupModuleConfig(autoTrackPushEvents = true)

        processor.processHeldDeliveredMetrics()

        verify(exactly = 1) { mockPendingStore.append(PendingPushDeliveryMetric(givenDeliveryId, givenDeviceToken)) }
        verify(exactly = 1) { mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(givenDeliveryId, givenDeviceToken) }
        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun processHeldDeliveredMetrics_givenSdkSetUpWithoutAutoTracking_expectDropHeldMetrics() {
        val processor = pushMessageProcessor()
        processor.processBackgroundPushDeliveredMetrics(String.random, String.random)
        setupModuleConfig(autoTrackPushEvents = false)

        processor.processHeldDeliveredMetrics()

        verify(exactly = 0) { mockPendingStore.append(any<PendingPushDeliveryMetric>()) }
        verify(exactly = 0) { mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }
        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun processHeldDeliveredMetrics_givenSdkNotSetUp_expectKeepHolding() {
        val processor = pushMessageProcessor()
        processor.processBackgroundPushDeliveredMetrics(String.random, String.random)

        processor.processHeldDeliveredMetrics()

        verify(exactly = 0) { mockPendingStore.append(any<PendingPushDeliveryMetric>()) }
        SDKComponent.heldPushDeliveryStore.loadAll().size shouldBeEqualTo 1
    }

    @Test
    fun processHeldDeliveredMetrics_givenPendingSaveFails_expectKeepHeldAndSendOnNextRelease() {
        val held = PendingPushDeliveryMetric(deliveryId = String.random, token = String.random)
        val processor = pushMessageProcessor()
        processor.processBackgroundPushDeliveredMetrics(held.deliveryId, held.token)
        setupModuleConfig(autoTrackPushEvents = true)
        every { mockPendingStore.append(any<PendingPushDeliveryMetric>()) } returnsMany listOf(false, true)

        processor.processHeldDeliveredMetrics()

        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo listOf(held)
        verify(exactly = 0) { mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }

        processor.processHeldDeliveredMetrics()

        SDKComponent.heldPushDeliveryStore.loadAll() shouldBeEqualTo emptyList()
        verify(exactly = 2) { mockPendingStore.append(held) }
        verify(exactly = 1) { mockDeliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(held.deliveryId, held.token) }
    }

    @Test
    fun processNotificationClick_givenValidIntent_expectSuccessfulProcessing() {
        setupModuleConfig(autoTrackPushEvents = true)
        val processor = pushMessageProcessor()
        val givenDeepLink = "https://cio.example.com/"
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = givenDeepLink,
            defaultHostAppIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Opened,
                    deliveryId = givenPayload.cioDeliveryId,
                    deviceToken = givenPayload.cioDeliveryToken
                )
            )

            deepLinkUtilMock.createDeepLinkHostAppIntent(any(), givenDeepLink)
            deepLinkUtilMock.createDeepLinkExternalIntent(any(), givenDeepLink)
            deepLinkUtilMock.createDefaultHostAppIntent(any())
        }
    }

    @Test
    fun processNotificationClick_givenValidIntent_expectLogDefaultHostAppLauncherHandling() {
        setupModuleConfig(autoTrackPushEvents = true)
        val processor = pushMessageProcessor()
        val givenDeepLink = "https://cio.example.com/"
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = givenDeepLink,
            defaultHostAppIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            mockPushLogger.logHandlingNotificationDeepLink(givenPayload, ACTIVITY_PREVENT_RESTART)
            mockPushLogger.logDeepLinkHandledDefaultHostAppLauncher()
        }
    }

    @Test
    fun processNotificationClick_givenLocallyStartedLiveNotification_expectDoNotTrackOpened() {
        // A locally started live notification is rendered by the SDK on the app's behalf and was
        // never delivered by Customer.io, so it carries no delivery id/token. Tracking `opened`
        // for it would emit a delivery metric with empty identifiers that cannot be attributed.
        setupModuleConfig(autoTrackPushEvents = true)
        val processor = pushMessageProcessor()
        val givenPayload = CustomerIOParsedPushPayload(
            extras = Bundle.EMPTY,
            deepLink = null,
            cioDeliveryId = "",
            cioDeliveryToken = "",
            title = String.random,
            body = String.random,
            activityId = String.random
        )
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = null,
            defaultHostAppIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        // No metric published, but the click itself is still handled (deep link / launcher).
        assertCalledNever {
            eventBus.publish(any<Event.TrackPushMetricEvent>())
        }
        assertCalledOnce {
            mockPushLogger.logNotificationClickMetricsSkippedForLocalNotification(givenPayload)
            deepLinkUtilMock.createDefaultHostAppIntent(any())
        }
    }

    @Test
    fun processNotificationClick_givenPushDeliveredNotification_expectTrackOpened() {
        // Complement of the guard above: a real Customer.io push has both identifiers, so the
        // `opened` metric must still be reported.
        setupModuleConfig(autoTrackPushEvents = true)
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = null,
            defaultHostAppIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Opened,
                    deliveryId = givenPayload.cioDeliveryId,
                    deviceToken = givenPayload.cioDeliveryToken
                )
            )
        }
    }

    @Test
    fun processNotificationClick_givenAutoTrackingDisabled_expectDoNotTrackOpened() {
        setupModuleConfig(autoTrackPushEvents = false)
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = null,
            defaultHostAppIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        assertNoInteractions(eventBus)
    }

    @Test
    fun processNotificationClick_givenNoDeepLink_expectOpenLauncherIntent() {
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = null,
            defaultHostAppIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        assertCalledNever {
            deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())
            deepLinkUtilMock.createDeepLinkExternalIntent(any(), any())
        }
        assertCalledOnce { deepLinkUtilMock.createDefaultHostAppIntent(any()) }
    }

    @Test
    fun processNotificationClick_givenNoDeepLink_expectLogHandledByHostApp() {
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = null,
            defaultHostAppIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            mockPushLogger.logHandlingNotificationDeepLink(givenPayload, ACTIVITY_PREVENT_RESTART)
            mockPushLogger.logDeepLinkHandledDefaultHostAppLauncher()
        }
    }

    @Test
    fun processNotificationClick_givenCallbackWithDeepLink_expectOpenCallbackIntent() {
        val givenPayload = pushMessagePayload(deepLink = "https://cio.example.com/")
        val notificationCallback: CustomerIOPushNotificationCallback = mockk(relaxed = true)
        every {
            notificationCallback.onNotificationClicked(givenPayload, any())
        } answers { }

        // Make sure that the callback as expected for all behaviors
        for (pushClickBehavior in PushClickBehavior.values()) {
            setupModuleConfig(
                notificationCallback = notificationCallback,
                pushClickBehavior = pushClickBehavior
            )
            val processor = pushMessageProcessor()
            val intent = Intent().apply {
                putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
            }

            processor.processNotificationClick(contextMock, intent)

            assertNoInteractions(deepLinkUtilMock)
        }
    }

    @Test
    fun processNotificationClick_givenCallbackWithDeepLink_expectLogHandledByCallback() {
        val givenPayload = pushMessagePayload(deepLink = "https://cio.example.com/")
        val notificationCallback: CustomerIOPushNotificationCallback = mockk(relaxed = true)
        every {
            notificationCallback.onNotificationClicked(givenPayload, any())
        } answers { }
        setupModuleConfig(
            notificationCallback = notificationCallback
        )
        val processor = pushMessageProcessor()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }

        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            mockPushLogger.logHandlingNotificationDeepLink(givenPayload, ACTIVITY_PREVENT_RESTART)
            mockPushLogger.logDeepLinkHandledByCallback()
        }
    }

    @Test
    fun processNotificationClick_givenCallbackWithoutDeepLink_expectOpenCallbackIntent() {
        val givenPayload = pushMessagePayload()
        val notificationCallback: CustomerIOPushNotificationCallback = mockk(relaxed = true)
        every {
            notificationCallback.onNotificationClicked(givenPayload, any())
        } answers { }

        // Make sure that the callback as expected for all behaviors
        for (pushClickBehavior in PushClickBehavior.values()) {
            setupModuleConfig(
                notificationCallback = notificationCallback,
                pushClickBehavior = pushClickBehavior
            )
            val processor = pushMessageProcessor()
            val intent = Intent().apply {
                putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
            }

            processor.processNotificationClick(contextMock, intent)

            assertNoInteractions(deepLinkUtilMock)
        }
    }

    @Test
    fun processNotificationClick_givenCallbackWithoutDeepLink_expectLogHandledByCallback() {
        val givenPayload = pushMessagePayload()
        val notificationCallback: CustomerIOPushNotificationCallback = mockk(relaxed = true)
        every {
            notificationCallback.onNotificationClicked(givenPayload, any())
        } answers { }
        setupModuleConfig(
            notificationCallback = notificationCallback
        )
        val processor = pushMessageProcessor()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            mockPushLogger.logHandlingNotificationDeepLink(givenPayload, ACTIVITY_PREVENT_RESTART)
            mockPushLogger.logDeepLinkHandledByCallback()
        }
    }

    @Test
    fun processNotificationClick_givenExternalLink_expectOpenExternalIntent() {
        val processor = pushMessageProcessor()
        val givenDeepLink = "https://cio.example.com/"
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = givenDeepLink,
            linkExternalIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            deepLinkUtilMock.createDeepLinkHostAppIntent(any(), givenDeepLink)
            deepLinkUtilMock.createDeepLinkExternalIntent(any(), givenDeepLink)
        }
        assertCalledNever { deepLinkUtilMock.createDefaultHostAppIntent(any()) }
    }

    @Test
    fun processNotificationClick_givenExternalLink_expectLogHandledByExternalApp() {
        val processor = pushMessageProcessor()
        val givenDeepLink = "https://cio.example.com/"
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = givenDeepLink,
            linkExternalIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            mockPushLogger.logHandlingNotificationDeepLink(givenPayload, ACTIVITY_PREVENT_RESTART)
            mockPushLogger.logDeepLinkHandledExternally()
        }
    }

    @Test
    fun processNotificationClick_givenInternalLink_expectOpenInternalIntent() {
        val processor = pushMessageProcessor()
        val givenDeepLink = "https://cio.example.com/"
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = givenDeepLink,
            linkHostAppIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            deepLinkUtilMock.createDeepLinkHostAppIntent(any(), givenDeepLink)
            deepLinkUtilMock.createDefaultHostAppIntent(any())
        }
        assertCalledNever { deepLinkUtilMock.createDeepLinkExternalIntent(any(), givenDeepLink) }
    }

    @Test
    fun processNotificationClick_givenInternalLink_expectLogHandledByHostApp() {
        val processor = pushMessageProcessor()
        val givenDeepLink = "https://cio.example.com/"
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = givenDeepLink,
            linkHostAppIntent = emptyIntent().withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            mockPushLogger.logHandlingNotificationDeepLink(givenPayload, ACTIVITY_PREVENT_RESTART)
            mockPushLogger.logDeepLinkHandledByHostApp()
        }
    }

    @Test
    fun processNotificationClick_givenUnresolvableLink_expectLogNotHandled() {
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(null)

        processor.processNotificationClick(contextMock, intent)

        assertCalledOnce {
            mockPushLogger.logHandlingNotificationDeepLink(givenPayload, ACTIVITY_PREVENT_RESTART)
            mockPushLogger.logDeepLinkWasNotHandled()
        }
    }

    @Test
    fun processNotificationClick_givenPushBehavior_expectResetTaskStack() {
        setupModuleConfig(
            autoTrackPushEvents = false,
            pushClickBehavior = PushClickBehavior.RESET_TASK_STACK
        )
        val givenPackageName = contextMock.packageName
        val givenDeepLink = "https://cio.example.com/"
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val processor = pushMessageProcessor()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        setupDeepLinkResponse(
            deepLink = givenDeepLink,
            linkHostAppIntent = Intent(Intent.ACTION_VIEW, Uri.parse(givenDeepLink)).apply {
                setPackage(givenPackageName)
                setClass(contextMock, Activity::class.java)
            }.withTestFlags()
        )

        processor.processNotificationClick(contextMock, intent)

        // The intent will be started with the default flags based on the activity launch mode
        // Also, we cannot verify the back stack as it is not exposed by the testing framework
        val expectedIntentFlags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME
        val nextStartedActivity = Shadows.shadowOf(applicationMock).nextStartedActivity
        nextStartedActivity shouldNotBe null
        nextStartedActivity.action shouldBeEqualTo Intent.ACTION_VIEW
        nextStartedActivity.dataString shouldBeEqualTo givenDeepLink
        nextStartedActivity.flags shouldBeEqualTo expectedIntentFlags
        nextStartedActivity.`package` shouldBeEqualTo givenPackageName
    }

    @Test
    fun processNotificationClick_givenPushBehavior_expectPreventRestart() {
        setupModuleConfig(
            autoTrackPushEvents = false,
            pushClickBehavior = ACTIVITY_PREVENT_RESTART
        )

        val givenPackageName = contextMock.packageName
        val givenDeepLink = "https://cio.example.com/"
        setupDeepLinkResponse(
            deepLink = givenDeepLink,
            linkHostAppIntent = Intent(Intent.ACTION_VIEW, Uri.parse(givenDeepLink)).apply {
                setPackage(givenPackageName)
            }.withTestFlags()
        )

        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val processor = pushMessageProcessor()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }

        processor.processNotificationClick(contextMock, intent)

        val expectedIntentFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val nextStartedActivity = Shadows.shadowOf(applicationMock).nextStartedActivity
        nextStartedActivity shouldNotBe null
        nextStartedActivity.action shouldBeEqualTo Intent.ACTION_VIEW
        nextStartedActivity.dataString shouldBeEqualTo givenDeepLink
        nextStartedActivity.flags shouldBeEqualTo expectedIntentFlags
        nextStartedActivity.`package` shouldBeEqualTo givenPackageName
    }

    @Test
    fun processNotificationClick_givenPushBehavior_expectNoFlags() {
        setupModuleConfig(
            autoTrackPushEvents = false,
            pushClickBehavior = PushClickBehavior.ACTIVITY_NO_FLAGS
        )

        val givenPackageName = contextMock.packageName
        val givenDeepLink = "https://cio.example.com/"
        setupDeepLinkResponse(
            deepLink = givenDeepLink,
            linkHostAppIntent = Intent(Intent.ACTION_VIEW, Uri.parse(givenDeepLink)).apply {
                setPackage(givenPackageName)
            }.withTestFlags()
        )

        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val processor = pushMessageProcessor()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }

        processor.processNotificationClick(contextMock, intent)

        // The intent will be started with the default flags based on the activity launch mode
        val nextStartedActivity = Shadows.shadowOf(applicationMock).nextStartedActivity
        nextStartedActivity shouldNotBe null
        nextStartedActivity.action shouldBeEqualTo Intent.ACTION_VIEW
        nextStartedActivity.dataString shouldBeEqualTo givenDeepLink
        nextStartedActivity.`package` shouldBeEqualTo givenPackageName
    }

    @Test
    fun processNotificationClick_givenEmptyIntent_expectNoProcessing() {
        setupModuleConfig(autoTrackPushEvents = true)
        val processor = pushMessageProcessor()
        val intent = Intent()

        processor.processNotificationClick(contextMock, intent)

        assertNoInteractions(eventBus, deepLinkUtilMock)
    }
}
