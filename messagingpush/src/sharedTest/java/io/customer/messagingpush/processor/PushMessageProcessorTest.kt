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
import io.customer.sdk.module.CustomerIOModuleConfig
import io.customer.sdk.repository.TrackRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class PushMessageProcessorTest : BaseTest() {
    private val configurations = hashMapOf<String, CustomerIOModuleConfig>()
    private val customerIOMock: CustomerIOInstance = mock()
    private val trackRepositoryMock: TrackRepository = mock()

    override fun setupConfig(): CustomerIOConfig = createConfig(
        configurations = configurations
    )

    @Test
    fun processGCMMessageIntent_givenBundleWithoutDeliveryData_expectDoNoTrackPush() {
        val givenBundle = Bundle().apply {
            putString("message_id", String.random)
        }
        val module = ModuleMessagingPushFCM(
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di,
            moduleConfig = MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(true).build()
        )
        configurations[ModuleMessagingPushFCM.MODULE_NAME] = module.moduleConfig
        val processor: PushMessageProcessor =
            PushMessageProcessorImpl(di.moduleConfig, trackRepositoryMock)
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
        configurations[ModuleMessagingPushFCM.MODULE_NAME] = module.moduleConfig
        val processor: PushMessageProcessor =
            PushMessageProcessorImpl(di.moduleConfig, trackRepositoryMock)
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
        configurations[ModuleMessagingPushFCM.MODULE_NAME] = module.moduleConfig
        val processor: PushMessageProcessor =
            PushMessageProcessorImpl(di.moduleConfig, trackRepositoryMock)
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
        configurations[ModuleMessagingPushFCM.MODULE_NAME] = module.moduleConfig
        val processor: PushMessageProcessor =
            PushMessageProcessorImpl(di.moduleConfig, trackRepositoryMock)

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
        configurations[ModuleMessagingPushFCM.MODULE_NAME] = module.moduleConfig
        val processor: PushMessageProcessor =
            PushMessageProcessorImpl(di.moduleConfig, trackRepositoryMock)

        processor.processRemoteMessageDeliveredMetrics(givenDeliveryId, givenDeviceToken)

        verify(trackRepositoryMock).trackMetric(
            givenDeliveryId,
            MetricEvent.delivered,
            givenDeviceToken
        )
    }
}
