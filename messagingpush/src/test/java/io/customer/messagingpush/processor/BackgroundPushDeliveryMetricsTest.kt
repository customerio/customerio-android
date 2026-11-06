package io.customer.messagingpush.processor

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.di.backgroundPushDeliveryMetrics
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.PendingDeliveryStore
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundPushDeliveryMetricsTest : IntegrationTest() {
    private val mockScheduler = mockk<PushDeliveryMetricsBackgroundScheduler>(relaxed = true)
    private val mockPendingStore = mockk<PendingDeliveryStore<PendingPushDeliveryMetric>>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<PushDeliveryMetricsBackgroundScheduler>(mockScheduler)
                        overrideDependency<PendingDeliveryStore<PendingPushDeliveryMetric>>(mockPendingStore)
                    }
                }
            }
        )
    }

    @Test
    fun trackDelivered_givenPushModuleNotRegistered_expectTrackedWithDefaultConfig() {
        val metric = PendingPushDeliveryMetric(deliveryId = String.random, token = String.random)

        SDKComponent.backgroundPushDeliveryMetrics.trackDelivered(metric.deliveryId, metric.token)

        verify(exactly = 1) { mockPendingStore.append(metric) }
        verify(exactly = 1) { mockScheduler.scheduleDeliveredPushMetricsReceipt(metric.deliveryId, metric.token) }
    }

    @Test
    fun trackDelivered_givenSetUpTurnsTrackingOffAfterSilentPush_expectLaterSilentAndRegularPushesNotTracked() {
        SDKComponent.backgroundPushDeliveryMetrics.trackDelivered(String.random, String.random)
        ModuleMessagingPushFCM(MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(false).build()).attachToSDKComponent()

        SDKComponent.backgroundPushDeliveryMetrics.trackDelivered(String.random, String.random)
        SDKComponent.pushMessageProcessor.processRemoteMessageDeliveredMetrics(String.random, String.random)

        verify(exactly = 1) { mockPendingStore.append(any<PendingPushDeliveryMetric>()) }
        verify(exactly = 1) { mockScheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }
    }
}
