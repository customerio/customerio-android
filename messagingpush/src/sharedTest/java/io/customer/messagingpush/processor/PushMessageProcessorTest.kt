package io.customer.messagingpush.processor

import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.di.moduleConfig
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.extensions.random
import io.customer.sdk.module.CustomerIOModule
import io.customer.sdk.repository.TrackRepository
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class PushMessageProcessorTest : BaseTest() {
    private val modules = hashMapOf<String, CustomerIOModule<*>>()
    private val customerIOMock: CustomerIOInstance = mock()
    private val trackRepositoryMock: TrackRepository = mock()

    override fun setupConfig(): CustomerIOConfig = createConfig(
        modules = modules
    )

    private fun pushMessageProcessor(): PushMessageProcessorImpl {
        return PushMessageProcessorImpl(di.logger, di.moduleConfig, trackRepositoryMock)
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
}
