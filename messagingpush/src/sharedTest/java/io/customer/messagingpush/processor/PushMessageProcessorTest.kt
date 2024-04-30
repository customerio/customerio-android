package io.customer.messagingpush.processor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.TaskStackBuilder
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.extensions.random
import io.customer.sdk.repository.TrackRepository
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBe
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
class PushMessageProcessorTest : BaseTest() {
    private val modules = hashMapOf<String, CustomerIOModule<*>>()
    private val customerIOMock: CustomerIOInstance = mock()
    private val deepLinkUtilMock: DeepLinkUtil = mock()
    private val trackRepositoryMock: TrackRepository = mock()

    override fun setupConfig(): CustomerIOConfig = createConfig(
        modules = modules
    )

    @Before
    override fun setup() {
        super.setup()

        di.overrideDependency(DeepLinkUtil::class.java, deepLinkUtilMock)
        di.overrideDependency(TrackRepository::class.java, trackRepositoryMock)
    }

    private fun pushMessageProcessor(): PushMessageProcessorImpl {
        return di.pushMessageProcessor as PushMessageProcessorImpl
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
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di,
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
        val gcmIntent: Intent = mock()
        whenever(gcmIntent.extras).thenReturn(givenBundle)

        processor.processGCMMessageIntent(gcmIntent)

        verifyNoInteractions(trackRepositoryMock)
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
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di,
            moduleConfig = MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(false).build()
        )
        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val processor = pushMessageProcessor()
        val gcmIntent: Intent = mock()
        whenever(gcmIntent.extras).thenReturn(givenBundle)

        processor.processGCMMessageIntent(gcmIntent)

        verifyNoInteractions(trackRepositoryMock)
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
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di,
            moduleConfig = MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(true).build()
        )
        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val processor = pushMessageProcessor()
        val gcmIntent: Intent = mock()
        whenever(gcmIntent.extras).thenReturn(givenBundle)

        processor.processGCMMessageIntent(gcmIntent)

        verify(trackRepositoryMock).trackMetric(
            givenDeliveryId,
            MetricEvent.delivered,
            givenDeviceToken
        )
    }

    @Test
    fun processRemoteMessageDeliveredMetrics_givenAutoTrackPushEventsDisabled_expectDoNoTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        val module = ModuleMessagingPushFCM(
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di,
            moduleConfig = MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(false).build()
        )
        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val processor = pushMessageProcessor()

        processor.processRemoteMessageDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        verifyNoInteractions(trackRepositoryMock)
    }

    @Test
    fun processRemoteMessageDeliveredMetrics_givenAutoTrackPushEventsEnabled_expectTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        val module = ModuleMessagingPushFCM(
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di,
            moduleConfig = MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(true).build()
        )
        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val processor = pushMessageProcessor()

        processor.processRemoteMessageDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        verify(trackRepositoryMock).trackMetric(
            givenDeliveryId,
            MetricEvent.delivered,
            givenDeviceToken
        )
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

        processor.processNotificationClick(context, intent)

        verify(trackRepositoryMock).trackMetric(
            givenPayload.cioDeliveryId,
            MetricEvent.opened,
            givenPayload.cioDeliveryToken
        )
        verify(deepLinkUtilMock).createDeepLinkHostAppIntent(context, givenDeepLink)
        verify(deepLinkUtilMock).createDeepLinkExternalIntent(context, givenDeepLink)
        verify(deepLinkUtilMock).createDefaultHostAppIntent(context)
    }

    @Test
    fun processNotificationClick_givenAutoTrackingDisabled_expectDoNotTrackOpened() {
        setupModuleConfig(autoTrackPushEvents = false)
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }

        processor.processNotificationClick(context, intent)

        verifyNoInteractions(trackRepositoryMock)
    }

    @Test
    fun processNotificationClick_givenNoDeepLink_expectOpenLauncherIntent() {
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }

        processor.processNotificationClick(context, intent)

        verify(deepLinkUtilMock, never()).createDeepLinkHostAppIntent(any(), any())
        verify(deepLinkUtilMock, never()).createDeepLinkExternalIntent(any(), any())
        verify(deepLinkUtilMock).createDefaultHostAppIntent(any())
    }

    @Test
    fun processNotificationClick_givenCallbackWithDeepLink_expectOpenCallbackIntent() {
        val notificationCallback: CustomerIOPushNotificationCallback = mock()
        whenever(notificationCallback.createTaskStackFromPayload(any(), any())).thenReturn(
            TaskStackBuilder.create(context)
        )
        val givenPayload = pushMessagePayload(deepLink = "https://cio.example.com/")

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

            processor.processNotificationClick(context, intent)

            verifyNoInteractions(deepLinkUtilMock)
        }
    }

    @Test
    fun processNotificationClick_givenCallbackWithoutDeepLink_expectOpenCallbackIntent() {
        val notificationCallback: CustomerIOPushNotificationCallback = mock()
        whenever(notificationCallback.createTaskStackFromPayload(any(), any())).thenReturn(
            TaskStackBuilder.create(context)
        )
        val givenPayload = pushMessagePayload()

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

            processor.processNotificationClick(context, intent)

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
        whenever(deepLinkUtilMock.createDeepLinkExternalIntent(any(), any())).thenReturn(Intent())

        processor.processNotificationClick(context, intent)

        verify(deepLinkUtilMock).createDeepLinkHostAppIntent(any(), any())
        verify(deepLinkUtilMock).createDeepLinkExternalIntent(any(), any())
        verify(deepLinkUtilMock, never()).createDefaultHostAppIntent(any())
    }

    @Test
    fun processNotificationClick_givenInternalLink_expectOpenInternalIntent() {
        val processor = pushMessageProcessor()
        val givenPayload = pushMessagePayload(deepLink = "https://cio.example.com/")
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }
        whenever(deepLinkUtilMock.createDeepLinkExternalIntent(any(), any())).thenReturn(Intent())
        whenever(deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())).thenReturn(Intent())

        processor.processNotificationClick(context, intent)

        verify(deepLinkUtilMock).createDeepLinkHostAppIntent(any(), any())
        verify(deepLinkUtilMock, never()).createDeepLinkExternalIntent(any(), any())
        verify(deepLinkUtilMock).createDefaultHostAppIntent(any())
    }

    @Test
    fun processNotificationClick_givenPushBehavior_expectResetTaskStack() {
        setupModuleConfig(
            autoTrackPushEvents = false,
            pushClickBehavior = PushClickBehavior.RESET_TASK_STACK
        )
        val givenPackageName = "io.customer.example"
        val givenDeepLink = "https://cio.example.com/"
        whenever(deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())).thenReturn(
            Intent(Intent.ACTION_VIEW, Uri.parse(givenDeepLink)).apply {
                setPackage(givenPackageName)
            }
        )
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val processor = pushMessageProcessor()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }

        processor.processNotificationClick(context, intent)

        // The intent will be started with the default flags based on the activity launch mode
        // Also, we cannot verify the back stack as it is not exposed by the testing framework
        val expectedIntentFlags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME
        val nextStartedActivity = Shadows.shadowOf(application).nextStartedActivity
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
        whenever(deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())).thenReturn(
            Intent(Intent.ACTION_VIEW, Uri.parse(givenDeepLink)).apply {
                setPackage(givenPackageName)
            }
        )
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val processor = pushMessageProcessor()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }

        processor.processNotificationClick(context, intent)

        val expectedIntentFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val nextStartedActivity = Shadows.shadowOf(application).nextStartedActivity
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
        whenever(deepLinkUtilMock.createDeepLinkHostAppIntent(any(), any())).thenReturn(
            Intent(Intent.ACTION_VIEW, Uri.parse(givenDeepLink)).apply {
                setPackage(givenPackageName)
            }
        )
        val givenPayload = pushMessagePayload(deepLink = givenDeepLink)
        val processor = pushMessageProcessor()
        val intent = Intent().apply {
            putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, givenPayload)
        }

        processor.processNotificationClick(context, intent)

        // The intent will be started with the default flags based on the activity launch mode
        val nextStartedActivity = Shadows.shadowOf(application).nextStartedActivity
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

        processor.processNotificationClick(context, intent)

        verifyNoInteractions(trackRepositoryMock)
        verifyNoInteractions(deepLinkUtilMock)
    }
}
