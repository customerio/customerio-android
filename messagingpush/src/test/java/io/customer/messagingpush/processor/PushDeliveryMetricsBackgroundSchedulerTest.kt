package io.customer.messagingpush.processor

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.customer.commontest.extensions.random
import io.customer.messagingpush.AsyncPushDeliveryTracker
import io.customer.messagingpush.testutils.core.JUnitTest
import io.customer.messagingpush.util.WorkManagerProvider
import io.customer.sdk.events.Metric
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.jupiter.api.Test

class PushDeliveryMetricsBackgroundSchedulerTest : JUnitTest() {

    private val mockWorkManagerProvider = mockk<WorkManagerProvider>(relaxed = true)
    private val mockWorkManager = mockk<WorkManager>(relaxed = true)
    private val mockAsyncPushDeliveryTracker = mockk<AsyncPushDeliveryTracker>(relaxed = true)
    private val scheduler = PushDeliveryMetricsBackgroundScheduler(mockWorkManagerProvider, mockAsyncPushDeliveryTracker)

    @Test
    fun scheduleDeliveredPushMetricsReceipt_givenValidInputs_expectWorkRequestEnqueued() {
        val deliveryId = String.random
        val deliveryToken = String.random
        val workRequestSlot = slot<OneTimeWorkRequest>()

        every { mockWorkManagerProvider.getWorkManager() } returns mockWorkManager

        scheduler.scheduleDeliveredPushMetricsReceipt(deliveryId, deliveryToken)

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(deliveryId),
                eq(ExistingWorkPolicy.KEEP),
                capture(workRequestSlot)
            )
        }

        val inputData = workRequestSlot.captured.workSpec.input
        inputData.getString("delivery-id") shouldBeEqualTo deliveryId
        inputData.getString("delivery-token") shouldBeEqualTo deliveryToken

        val constraints = workRequestSlot.captured.workSpec.constraints
        constraints shouldNotBe null
        constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
    }

    @Test
    fun scheduleDeliveredPushMetricsReceipt_givenWorkManagerProviderReturnsNull_expectFallbackToAsyncTracker() {
        val deliveryId = String.random
        val deliveryToken = String.random

        every { mockWorkManagerProvider.getWorkManager() } returns null

        scheduler.scheduleDeliveredPushMetricsReceipt(deliveryId, deliveryToken)

        verify(exactly = 0) {
            mockWorkManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }

        verify(exactly = 1) {
            mockAsyncPushDeliveryTracker.trackMetric(deliveryToken, Metric.Delivered.name, deliveryId)
        }
    }

    @Test
    fun scheduleDeliveredPushMetricsReceipt_givenWorkManagerProviderReturnsNull_expectNoWorkManagerInteraction() {
        val deliveryId = String.random
        val deliveryToken = String.random

        every { mockWorkManagerProvider.getWorkManager() } returns null

        scheduler.scheduleDeliveredPushMetricsReceipt(deliveryId, deliveryToken)

        // Verify WorkManager is not called when null
        verify(exactly = 0) {
            mockWorkManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
        // Verify async tracker is called as fallback
        verify(exactly = 1) {
            mockAsyncPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun scheduleDeliveredPushMetricsReceipt_givenWorkManagerAvailable_expectNoAsyncTrackerCall() {
        val deliveryId = String.random
        val deliveryToken = String.random

        every { mockWorkManagerProvider.getWorkManager() } returns mockWorkManager

        scheduler.scheduleDeliveredPushMetricsReceipt(deliveryId, deliveryToken)

        // Verify WorkManager is used when available
        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
        // Verify async tracker is NOT called when WorkManager is available
        verify(exactly = 0) {
            mockAsyncPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }
}
