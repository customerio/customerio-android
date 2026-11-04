package io.customer.messagingpush

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.firebase.messaging.RemoteMessage
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messagingpush.data.model.CustomerIOBackgroundPushPayload
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.processor.PushDeliveryMetricsBackgroundScheduler
import io.customer.messagingpush.processor.PushMessageProcessor
import io.customer.messagingpush.processor.PushMessageProcessorImpl
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.messagingpush.util.NotificationChannelCreator
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.data.store.PendingDeliveryStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.RejectedExecutionException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class BackgroundPushHandlerTest : IntegrationTest() {
    private fun data(): Map<String, String> = mapOf(
        "CIO-Background-Version" to "1",
        "CIO-Background-Delivery-ID" to "dlv_123",
        "CIO-Background-Delivery-Token" to "tok_abc",
        "title" to "Order shipped",
        "body" to "",
        "order_id" to "42"
    )

    private fun assertNoNotification() {
        val manager = contextMock.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(Shadows.shadowOf(manager).allNotifications.isEmpty())
        assertTrue(manager.notificationChannels.isEmpty())
    }

    @Test
    fun malformedMarkedMessages_neverFallBackToDisplayOrCallHost() {
        var calls = 0
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setBackgroundPushCallback { _, _ -> calls++ }.build()
        ).attachToSDKComponent()
        val cases = listOf(
            data() - "CIO-Background-Version",
            data() - "CIO-Background-Delivery-Token",
            data() + ("CIO-Background-Delivery-ID" to " "),
            data() + ("CIO-Background-Version" to "2"),
            data() + ("CIO-Delivery-ID" to "legacy"),
            data() + ("CIO-Background-Unknown" to "future"),
            data() + ("notification" to "display")
        )
        cases.forEach { payload ->
            val processor = mockk<PushMessageProcessor>(relaxed = true)
            val handler = CustomerIOPushNotificationHandler(
                processor,
                RemoteMessage.Builder("destination").setData(payload).build(),
                NotificationChannelCreator()
            )
            assertTrue(handler.handleMessage(contextMock))
            verify(exactly = 0) { processor.processRemoteMessageDeliveredMetrics(any(), any()) }
        }
        assertEquals(0, calls)
        assertNoNotification()
    }

    @Test
    fun mixedNotificationAndBackgroundData_whenHandlerRuns_isConsumedWithoutDisplay() {
        var calls = 0
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setBackgroundPushCallback { _, _ -> calls++ }.build()
        ).attachToSDKComponent()
        val processor = mockk<PushMessageProcessor>(relaxed = true)
        val message = RemoteMessage(
            Bundle().apply {
                data().forEach { (key, value) -> putString(key, value) }
                putString("gcm.n.e", "1")
                putString("gcm.n.title", "Visible notification")
                putString("gcm.n.body", "Conflicting body")
            }
        )
        assertNotNull(message.notification)

        assertTrue(CustomerIOPushNotificationHandler(processor, message, NotificationChannelCreator()).handleMessage(contextMock))

        assertEquals(0, calls)
        verify(exactly = 0) { processor.processRemoteMessageDeliveredMetrics(any(), any()) }
        assertNoNotification()
    }

    @Test
    fun missingCallback_stillConsumesBackgroundPushWithoutDisplay() {
        val processor = mockk<PushMessageProcessor>(relaxed = true)
        val handler = CustomerIOPushNotificationHandler(
            processor,
            RemoteMessage.Builder("destination").setData(data()).build(),
            NotificationChannelCreator()
        )
        assertTrue(handler.handleMessage(contextMock))
        verify(exactly = 1) { processor.processRemoteMessageDeliveredMetrics("dlv_123", "tok_abc") }
        assertNoNotification()
    }

    @Test
    fun throwingCallback_stillConsumesBackgroundPushWithoutDisplay() {
        var calls = 0
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setBackgroundPushCallback { _, _ -> calls++; throw IllegalStateException("host failed") }.build()
        ).attachToSDKComponent()
        val handler = CustomerIOPushNotificationHandler(
            mockk(relaxed = true),
            RemoteMessage.Builder("destination").setData(data()).build(),
            NotificationChannelCreator()
        )
        assertTrue(handler.handleMessage(contextMock, false))
        assertEquals(1, calls)
        assertNoNotification()
    }

    @Test
    fun receiptSchedulingFailure_stillDeliversBusinessDataWithoutNotification() {
        var received: CustomerIOBackgroundPushPayload? = null
        val config = MessagingPushModuleConfig.Builder()
            .setBackgroundPushCallback { _, payload -> received = payload }.build()
        ModuleMessagingPushFCM(moduleConfig = config).attachToSDKComponent()
        val workManager = mockk<WorkManager>()
        every { workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) } throws
            RejectedExecutionException("injected rejected receipt scheduling")
        val provider = mockk<CustomerIOWorkManagerProvider>()
        every { provider.getWorkManager() } returns workManager
        val tracker = mockk<AsyncPushDeliveryTracker>(relaxed = true)
        val store = mockk<PendingDeliveryStore<PendingPushDeliveryMetric>>(relaxed = true)
        val processor = PushMessageProcessorImpl(
            mockk<PushNotificationLogger>(relaxed = true),
            config,
            mockk(relaxed = true),
            PushDeliveryMetricsBackgroundScheduler(provider, tracker),
            store
        )
        val id = "receipt-failure-${java.util.UUID.randomUUID()}"
        val handler = CustomerIOPushNotificationHandler(
            processor,
            RemoteMessage.Builder("destination")
                .setData(data() + ("CIO-Background-Delivery-ID" to id)).build(),
            NotificationChannelCreator()
        )

        assertTrue(handler.handleMessage(contextMock))

        assertEquals(id, received?.deliveryId)
        assertEquals(mapOf("title" to "Order shipped", "body" to "", "order_id" to "42"), received?.data)
        verify(exactly = 1) { store.append(PendingPushDeliveryMetric(id, "tok_abc")) }
        verify(exactly = 0) { tracker.trackMetric(any(), any(), any()) }
        assertNoNotification()
    }

    @Test
    fun legacyEmptyDisplayFields_stillPostNotification() {
        val handler = CustomerIOPushNotificationHandler(
            mockk(relaxed = true),
            RemoteMessage.Builder("destination")
                .setData(mapOf("CIO-Delivery-ID" to "old", "CIO-Delivery-Token" to "old", "title" to "", "body" to ""))
                .build(),
            NotificationChannelCreator()
        )

        assertTrue(handler.handleMessage(contextMock))

        val manager = contextMock.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(1, Shadows.shadowOf(manager).allNotifications.size)
    }

    @Test
    fun legacyBusinessKeys_includingBackgroundMetadata_stillPostNotification() {
        val manager = contextMock.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val legacy = mapOf(
            "CIO-Delivery-ID" to "old",
            "CIO-Delivery-Token" to "old",
            "title" to "Existing title",
            "body" to "Existing body"
        )
        listOf(
            "CIO-Background-Version",
            "CIO-Background-Delivery-ID",
            "CIO-Background-Delivery-Token",
            "CIO-Background-sync",
            "cio-background-version",
            "CIO-Background-Delivery-ID-extra"
        ).forEach { key ->
            manager.cancelAll()
            val handler = CustomerIOPushNotificationHandler(
                mockk(relaxed = true),
                RemoteMessage.Builder("destination")
                    .setData(legacy + (key to "existing business data")).build(),
                NotificationChannelCreator()
            )

            assertTrue(handler.handleMessage(contextMock))

            val notifications = Shadows.shadowOf(manager).allNotifications
            assertEquals("Legacy message with customer key $key must still render", 1, notifications.size)
            assertEquals("Existing title", notifications.single().extras.getString(android.app.Notification.EXTRA_TITLE))
        }
    }

    @Test
    fun repeatedDelivery_reachesHostTwiceButPersistsOnlyOneReceipt() {
        val id = "background-${java.util.UUID.randomUUID()}"
        var calls = 0
        val config = MessagingPushModuleConfig.Builder().setBackgroundPushCallback { _, _ -> calls++ }.build()
        ModuleMessagingPushFCM(moduleConfig = config).attachToSDKComponent()
        val store = mockk<PendingDeliveryStore<PendingPushDeliveryMetric>>(relaxed = true)
        val scheduler = mockk<PushDeliveryMetricsBackgroundScheduler>(relaxed = true)
        val processor = PushMessageProcessorImpl(
            mockk<PushNotificationLogger>(relaxed = true),
            config,
            mockk(relaxed = true),
            scheduler,
            store
        )
        val handler = CustomerIOPushNotificationHandler(
            processor,
            RemoteMessage.Builder("destination")
                .setData(data() + ("CIO-Background-Delivery-ID" to id)).build(),
            NotificationChannelCreator()
        )

        assertTrue(handler.handleMessage(contextMock))
        assertTrue(handler.handleMessage(contextMock, false))

        val entry = slot<PendingPushDeliveryMetric>()
        verify(exactly = 1) { store.append(capture(entry)) }
        assertEquals(id, entry.captured.deliveryId)
        assertEquals("tok_abc", entry.captured.token)
        verify(exactly = 1) { scheduler.scheduleDeliveredPushMetricsReceipt(id, "tok_abc") }
        assertEquals(2, calls)
        assertNoNotification()
    }

    @Test
    fun autoTrackingDisabled_deliversToHostWithoutPersistingReceipt() {
        var calls = 0
        val config = MessagingPushModuleConfig.Builder().setAutoTrackPushEvents(false)
            .setBackgroundPushCallback { _, _ -> calls++ }.build()
        ModuleMessagingPushFCM(moduleConfig = config).attachToSDKComponent()
        val store = mockk<PendingDeliveryStore<PendingPushDeliveryMetric>>(relaxed = true)
        val scheduler = mockk<PushDeliveryMetricsBackgroundScheduler>(relaxed = true)
        val processor = PushMessageProcessorImpl(
            mockk<PushNotificationLogger>(relaxed = true),
            config,
            mockk(relaxed = true),
            scheduler,
            store
        )
        val handler = CustomerIOPushNotificationHandler(
            processor,
            RemoteMessage.Builder("destination")
                .setData(data() + ("CIO-Background-Delivery-ID" to "disabled-${java.util.UUID.randomUUID()}")).build(),
            NotificationChannelCreator()
        )

        assertTrue(handler.handleMessage(contextMock))

        assertEquals(1, calls)
        verify(exactly = 0) { store.append(any()) }
        verify(exactly = 0) { scheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }
        assertNoNotification()
    }

    @Test
    fun validBackgroundMessage_deliversBusinessDataAndReceiptWithoutNotification() {
        var received: CustomerIOBackgroundPushPayload? = null
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setBackgroundPushCallback { _, payload -> received = payload }
                .build()
        ).attachToSDKComponent()
        val processor = mockk<PushMessageProcessor>(relaxed = true)
        val message = RemoteMessage.Builder("destination").setData(
            mapOf(
                "CIO-Background-Version" to "1",
                "CIO-Background-Delivery-ID" to "dlv_123",
                "CIO-Background-Delivery-Token" to "tok_abc",
                "title" to "Order shipped",
                "body" to "",
                "order_id" to "42"
            )
        ).build()

        assertTrue(CustomerIOPushNotificationHandler(processor, message, NotificationChannelCreator()).handleMessage(contextMock))

        assertEquals("dlv_123", received?.deliveryId)
        assertEquals("tok_abc", received?.deliveryToken)
        assertEquals(mapOf("title" to "Order shipped", "body" to "", "order_id" to "42"), received?.data)
        verify(exactly = 1) { processor.processRemoteMessageDeliveredMetrics("dlv_123", "tok_abc") }
        val manager = contextMock.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(Shadows.shadowOf(manager).allNotifications.isEmpty())
        assertTrue(manager.notificationChannels.isEmpty())
    }

    @Test
    fun goEncodedProviderFixture_reachesHostUnchangedWithoutDisplay() {
        val bytes = javaClass.getResourceAsStream("/background_push/android_fcm.json")!!.use { it.readBytes() }
        val provider = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("message")
        val fields = provider.getJSONObject("data")
        val data = fields.keys().asSequence().associateWith { fields.getString(it) }
        var received: CustomerIOBackgroundPushPayload? = null
        ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder()
                .setBackgroundPushCallback { _, payload -> received = payload }.build()
        ).attachToSDKComponent()
        val processor = mockk<PushMessageProcessor>(relaxed = true)

        assertTrue(
            CustomerIOPushNotificationHandler(
                processor,
                RemoteMessage.Builder("destination").setData(data).build(),
                NotificationChannelCreator()
            ).handleMessage(contextMock)
        )

        assertEquals("fixture-delivery-1", received?.deliveryId)
        assertEquals(mapOf("title" to "Order shipped", "order_id" to "A-1001", "note" to "line one\nsays \"hi\" <b>&</b> ✓", "cıo-x" to "dotless"), received?.data)
        verify(exactly = 1) { processor.processRemoteMessageDeliveredMetrics("fixture-delivery-1", "fixture-fcm-token-1") }
        assertNoNotification()
    }

    @Test
    fun legacyTrackingKeys_withUnknownBackgroundVersion_keepLegacyNotificationHandling() {
        val processor = mockk<PushMessageProcessor>(relaxed = true)
        val message = RemoteMessage.Builder("destination").setData(
            mapOf(
                "CIO-Background-Version" to "2",
                "CIO-Delivery-ID" to "legacy-delivery",
                "CIO-Delivery-Token" to "legacy-token"
            )
        ).build()
        val handler = CustomerIOPushNotificationHandler(processor, message, NotificationChannelCreator())

        assertTrue(handler.handleMessage(contextMock))

        val manager = contextMock.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(1, Shadows.shadowOf(manager).allNotifications.size)
        verify(exactly = 1) { processor.processRemoteMessageDeliveredMetrics("legacy-delivery", "legacy-token") }
    }
}
