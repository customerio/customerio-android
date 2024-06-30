package io.customer.messagingpush.processor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.TaskStackBuilder
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.overrideDependency
import io.customer.commontest.extensions.random
import io.customer.commontest.extensions.verifyNever
import io.customer.commontest.extensions.verifyNoInteractions
import io.customer.commontest.extensions.verifyOnce
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
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.events.Metric
import io.customer.sdk.events.serializedName
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBe
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PushMessageProcessorTest : IntegrationTest() {
    private lateinit var deepLinkUtilMock: DeepLinkUtil
    private lateinit var eventBus: EventBus
    private lateinit var modules: MutableMap<String, CustomerIOModule<*>>

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
        modules = SDKComponent.modules
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

    private fun setupModuleConfig(
        pushClickBehavior: PushClickBehavior? = null,
        autoTrackPushEvents: Boolean? = null,
        notificationCallback: CustomerIOPushNotificationCallback? = null
    ) {
        modules[ModuleMessagingPushFCM.MODULE_NAME] = ModuleMessagingPushFCM(
            moduleConfig = with(MessagingPushModuleConfig.Builder()) {
                autoTrackPushEvents?.let { setAutoTrackPushEvents(it) }
                notificationCallback?.let { setNotificationCallback(it) }
                pushClickBehavior?.let { setPushClickBehavior(it) }
                build()
            }
        )
    }

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

        verifyNoInteractions(eventBus)
    }

    @Test
    fun processGCMMessageIntent_givenAutoTrackPushEventsDisabled_expectDoNoTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        val givenBundle = Bundle().apply {
            putString(PushTrackingUtil.DELIVERY_ID_KEY, givenDeliveryId)
            putString(PushTrackingUtil.DELIVERY_TOKEN_KEY, givenDeviceToken)
        }
        val module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(false).build()
        )

        SDKComponent.modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val processor = pushMessageProcessor()
        val gcmIntent: Intent = mockk(relaxed = true)
        every { gcmIntent.extras } returns givenBundle

        processor.processGCMMessageIntent(gcmIntent)

        verifyNoInteractions(eventBus)
    }

    @Test
    fun processGCMMessageIntent_givenAutoTrackPushEventsEnabled_expectTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        val givenBundle = Bundle().apply {
            putString(PushTrackingUtil.DELIVERY_ID_KEY, givenDeliveryId)
            putString(PushTrackingUtil.DELIVERY_TOKEN_KEY, givenDeviceToken)
        }
        val module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(true).build()
        )
        SDKComponent.modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val processor = pushMessageProcessor()
        val gcmIntent: Intent = mockk(relaxed = true)
        every { gcmIntent.extras } returns givenBundle

        processor.processGCMMessageIntent(gcmIntent)

        verifyOnce {
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
        val module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(false).build()
        )
        SDKComponent.modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val processor = pushMessageProcessor()

        processor.processRemoteMessageDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        verifyNoInteractions(eventBus)
    }

    @Test
    fun processRemoteMessageDeliveredMetrics_givenAutoTrackPushEventsEnabled_expectTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        val module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(true).build()
        )
        SDKComponent.modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val processor = pushMessageProcessor()

        processor.processRemoteMessageDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        verifyOnce {
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

        processor.processNotificationClick(contextMock, intent)

        verifyOnce {
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Delivered.serializedName,
                    deliveryId = givenPayload.cioDeliveryId,
                    deviceToken = givenPayload.cioDeliveryToken
                )
            )
        }
        verifyOnce {
            deepLinkUtilMock.createDeepLinkHostAppIntent(contextMock, givenDeepLink)
            deepLinkUtilMock.createDeepLinkExternalIntent(contextMock, givenDeepLink)
            deepLinkUtilMock.createDefaultHostAppIntent(contextMock)
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

        processor.processNotificationClick(contextMock, intent)

        verifyNoInteractions(eventBus)
    }

    @Test
    fun processNotificationClick_givenNoDeepLink_expectOpenLauncherIntent() {
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }

        processor.processNotificationClick(contextMock, intent)

        verifyNever {
            deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())
            deepLinkUtilMock.createDeepLinkExternalIntent(any(), any())
        }
        verifyOnce {
            deepLinkUtilMock.createDefaultHostAppIntent(any())
        }
    }

    @Test
    fun processNotificationClick_givenCallbackWithDeepLink_expectOpenCallbackIntent() {
        val notificationCallback: CustomerIOPushNotificationCallback = mockk(relaxed = true)
        every {
            notificationCallback.createTaskStackFromPayload(any(), any())
        } returns TaskStackBuilder.create(contextMock)
        val givenPayload = pushMessagePayload(deepLink = "https://cio.example.com/")
        every {
            notificationCallback.createTaskStackFromPayload(contextMock, givenPayload)
        } returns TaskStackBuilder.create(contextMock)

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

            verifyNoInteractions(deepLinkUtilMock)
        }
    }

    @Test
    fun processNotificationClick_givenCallbackWithoutDeepLink_expectOpenCallbackIntent() {
        val notificationCallback: CustomerIOPushNotificationCallback = mockk(relaxed = true)
        val givenPayload = pushMessagePayload()
        every {
            notificationCallback.createTaskStackFromPayload(contextMock, givenPayload)
        } returns TaskStackBuilder.create(contextMock)

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

            verifyNoInteractions(deepLinkUtilMock)
        }
    }

    @Test
    fun processNotificationClick_givenExternalLink_expectOpenExternalIntent() {
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload(deepLink = "https://cio.example.com/")
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        every { deepLinkUtilMock.createDeepLinkExternalIntent(any(), any()) } returns Intent()

        processor.processNotificationClick(contextMock, intent)

        verifyOnce {
            deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())
            deepLinkUtilMock.createDeepLinkExternalIntent(any(), any())
        }
        verifyNever {
            deepLinkUtilMock.createDefaultHostAppIntent(any())
        }
    }

    @Test
    fun processNotificationClick_givenInternalLink_expectOpenInternalIntent() {
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload(deepLink = "https://cio.example.com/")
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        every { deepLinkUtilMock.createDeepLinkExternalIntent(any(), any()) } returns Intent()
        every { deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any()) } returns Intent()

        processor.processNotificationClick(contextMock, intent)

        verifyOnce {
            deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())
            deepLinkUtilMock.createDefaultHostAppIntent(any())
        }
        verifyNever {
            deepLinkUtilMock.createDeepLinkExternalIntent(any(), any())
        }
    }

    @Test
    fun processNotificationClick_givenPushBehavior_expectResetTaskStack() {
        setupModuleConfig(
            autoTrackPushEvents = false,
            pushClickBehavior = PushClickBehavior.RESET_TASK_STACK
        )
        val givenPackageName = "io.customer.example"
        val givenDeepLink = "https://cio.example.com/"
        every {
            deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())
        } returns Intent(Intent.ACTION_VIEW, Uri.parse(givenDeepLink)).apply {
            setPackage(givenPackageName)
        }
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val processor = pushMessageProcessor()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }

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
        val givenPackageName = "io.customer.example"
        val givenDeepLink = "https://cio.example.com/"
        every {
            deepLinkUtilMock.createDefaultHostAppIntent(any())
        } returns Intent(Intent.ACTION_VIEW, Uri.parse(givenDeepLink)).apply {
            setPackage(givenPackageName)
        }
        every {
            deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())
        } returns Intent(Intent.ACTION_VIEW, Uri.parse(givenDeepLink)).apply {
            setPackage(givenPackageName)
        }
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

    @Ignore(
        "Current testing framework does not support verifying the flags. " +
            "We'll have to rely on manual testing for this for now." +
            "In future, we can use more advanced testing frameworks to verify this"
    )
    @Test
    fun processNotificationClick_givenPushBehavior_expectNoFlags() {
        setupModuleConfig(
            autoTrackPushEvents = false,
            pushClickBehavior = PushClickBehavior.ACTIVITY_NO_FLAGS
        )
        val givenPackageName = "io.customer.example"
        val givenDeepLink = "https://cio.example.com/"
        every {
            deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())
        } returns Intent(Intent.ACTION_VIEW, Uri.parse(givenDeepLink)).apply {
            setPackage(givenPackageName)
        }
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

        verifyNoInteractions(eventBus, deepLinkUtilMock)
    }
}
