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
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.deepLinkUtil
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.events.Metric
import io.customer.sdk.events.serializedName
import io.mockk.every
import io.mockk.mockk
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

    private fun pushMessageProcessor(): PushMessageProcessorImpl {
        return SDKComponent.pushMessageProcessor as PushMessageProcessorImpl
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<DeepLinkUtil>(mockk())
                        overrideDependency<EventBus>(mockk(relaxed = true))
                    }
                }
            }
        )

        deepLinkUtilMock = SDKComponent.deepLinkUtil
        eventBus = SDKComponent.eventBus
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
    fun processMessage_givenNewMessageReceived_expectProcessPush() {
        val givenDeliveryId = String.random
        val processor = pushMessageProcessor()

        val result = processor.getOrUpdateMessageAlreadyProcessed(givenDeliveryId)

        result.shouldBeFalse()
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

        assertCalledOnce {
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Delivered.serializedName,
                    deliveryId = givenDeliveryId,
                    deviceToken = givenDeviceToken
                )
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

        assertCalledOnce {
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Delivered.serializedName,
                    deliveryId = givenDeliveryId,
                    deviceToken = givenDeviceToken
                )
            )
        }
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
                    event = Metric.Opened.serializedName,
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
            pushClickBehavior = PushClickBehavior.ACTIVITY_PREVENT_RESTART
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
