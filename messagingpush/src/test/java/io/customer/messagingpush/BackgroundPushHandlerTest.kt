package io.customer.messagingpush

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.firebase.messaging.RemoteMessage
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messagingpush.data.communication.CustomerIOBackgroundPushCallback
import io.customer.messagingpush.data.model.CustomerIOBackgroundPushPayload
import io.customer.messagingpush.di.backgroundPushCallbackFallback
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.processor.BackgroundPushDeliveryMetrics
import io.customer.messagingpush.processor.PushDeliveryMetricsBackgroundScheduler
import io.customer.messagingpush.processor.PushMessageProcessor
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.messagingpush.util.NotificationChannelCreator
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.data.store.PendingDeliveryStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class BackgroundPushHandlerTest : IntegrationTest() {
    private fun data(): Map<String, String> = mapOf(
        "CIO-Background-Delivery-ID" to "dlv_123",
        "CIO-Background-Delivery-Token" to "tok_abc",
        "title" to "Order shipped",
        "body" to "",
        "order_id" to "42"
    )

    private val legacy = mapOf(
        "CIO-Delivery-ID" to "old",
        "CIO-Delivery-Token" to "old",
        "title" to "Existing title",
        "body" to "Existing body"
    )

    private val notificationManager: NotificationManager
        get() = contextMock.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun postedNotifications(): List<Notification> = Shadows.shadowOf(notificationManager).allNotifications

    private fun assertNoNotification() {
        assertTrue(postedNotifications().isEmpty())
        assertTrue(notificationManager.notificationChannels.isEmpty())
    }

    private fun installCallback(
        autoTrackPushEvents: Boolean = true,
        callback: CustomerIOBackgroundPushCallback
    ): MessagingPushModuleConfig {
        val config = MessagingPushModuleConfig.Builder()
            .setAutoTrackPushEvents(autoTrackPushEvents)
            .setBackgroundPushCallback(callback)
            .build()
        ModuleMessagingPushFCM(moduleConfig = config).attachToSDKComponent()
        return config
    }

    private fun metrics(
        config: MessagingPushModuleConfig,
        scheduler: PushDeliveryMetricsBackgroundScheduler,
        store: PendingDeliveryStore<PendingPushDeliveryMetric>
    ) = BackgroundPushDeliveryMetrics(
        pushLogger = mockk<PushNotificationLogger>(relaxed = true),
        moduleConfig = { config },
        pendingStore = store,
        deliveryMetricsScheduler = scheduler
    )

    private fun handler(
        payload: Map<String, String>,
        processor: PushMessageProcessor = mockk(relaxed = true),
        metrics: BackgroundPushDeliveryMetrics = mockk(relaxed = true)
    ) = CustomerIOPushNotificationHandler(
        processor,
        RemoteMessage.Builder("destination").setData(payload).build(),
        NotificationChannelCreator(),
        lazyOf(metrics)
    )

    @Test
    fun validBackgroundMessage_reachesHostAsDataWithoutDisplayLinkOrOpened() {
        var received: CustomerIOBackgroundPushPayload? = null
        installCallback { _, payload -> received = payload }
        val processor = mockk<PushMessageProcessor>(relaxed = true)
        val metrics = mockk<BackgroundPushDeliveryMetrics>(relaxed = true)
        val displayLike = mapOf(
            "title" to "Order shipped",
            "body" to "Tap to track",
            "image" to "https://example.com/box.png",
            "link" to "https://example.com/orders/42"
        )

        assertTrue(handler(data() + displayLike, processor, metrics).handleMessage(contextMock))

        assertEquals("dlv_123", received?.deliveryId)
        assertEquals("tok_abc", received?.deliveryToken)
        assertEquals(displayLike + ("order_id" to "42"), received?.data)
        verify(exactly = 1) { metrics.trackDelivered("dlv_123", "tok_abc") }
        verify(exactly = 0) { processor.processRemoteMessageDeliveredMetrics(any(), any()) }
        verify(exactly = 0) { processor.processNotificationClick(any(), any()) }
        assertNull(Shadows.shadowOf(applicationMock).nextStartedActivity)
        assertNoNotification()
    }

    @Test
    fun malformedMarkedMessages_neverFallBackToDisplayOrCallHost() {
        var calls = 0
        installCallback { _, _ -> calls++ }
        val cases = listOf(
            data() - "CIO-Background-Delivery-Token",
            data() + ("CIO-Background-Delivery-Token" to " "),
            data() + ("CIO-Background-Delivery-ID" to " ")
        )
        cases.forEach { payload ->
            val processor = mockk<PushMessageProcessor>(relaxed = true)
            val metrics = mockk<BackgroundPushDeliveryMetrics>(relaxed = true)
            assertTrue(handler(payload, processor, metrics).handleMessage(contextMock))
            verify(exactly = 0) { metrics.trackDelivered(any(), any()) }
        }
        assertEquals(0, calls)
        assertNoNotification()
    }

    @Test
    fun backgroundKeysWithoutExactDeliveryId_areLeftForTheApp() {
        var calls = 0
        installCallback { _, _ -> calls++ }
        val cases = listOf(
            data() - "CIO-Background-Delivery-ID",
            data().mapKeys { (key, _) -> key.lowercase() }
        )
        cases.forEach { payload ->
            val processor = mockk<PushMessageProcessor>(relaxed = true)
            val metrics = mockk<BackgroundPushDeliveryMetrics>(relaxed = true)
            assertFalse(handler(payload, processor, metrics).handleMessage(contextMock))
            verify(exactly = 0) { metrics.trackDelivered(any(), any()) }
        }
        assertEquals(0, calls)
        assertNoNotification()
    }

    @Test
    fun missingCallback_stillConsumesBackgroundPushWithoutDisplay() {
        val processor = mockk<PushMessageProcessor>(relaxed = true)
        val metrics = mockk<BackgroundPushDeliveryMetrics>(relaxed = true)

        assertTrue(handler(data(), processor, metrics).handleMessage(contextMock))

        verify(exactly = 1) { metrics.trackDelivered("dlv_123", "tok_abc") }
        assertNoNotification()
    }

    @Test
    fun fallbackCallback_withoutConfigCallback_receivesBackgroundPush() {
        var received: CustomerIOBackgroundPushPayload? = null
        SDKComponent.backgroundPushCallbackFallback.callback = CustomerIOBackgroundPushCallback { _, payload -> received = payload }
        val processor = mockk<PushMessageProcessor>(relaxed = true)
        val metrics = mockk<BackgroundPushDeliveryMetrics>(relaxed = true)

        assertTrue(handler(data(), processor, metrics).handleMessage(contextMock))

        assertEquals("dlv_123", received?.deliveryId)
        verify(exactly = 1) { metrics.trackDelivered("dlv_123", "tok_abc") }
        assertNoNotification()
    }

    @Test
    fun configCallback_whenFallbackAlsoSet_winsOverFallback() {
        var configCalls = 0
        var fallbackCalls = 0
        installCallback { _, _ -> configCalls++ }
        SDKComponent.backgroundPushCallbackFallback.callback = CustomerIOBackgroundPushCallback { _, _ -> fallbackCalls++ }

        assertTrue(handler(data()).handleMessage(contextMock))

        assertEquals(1, configCalls)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun throwingCallback_stillConsumesBackgroundPushWithoutDisplay() {
        var calls = 0
        installCallback { _, _ -> calls++; throw IllegalStateException("host failed") }

        assertTrue(handler(data()).handleMessage(contextMock, false))

        assertEquals(1, calls)
        assertNoNotification()
    }

    @Test
    fun receiptSchedulingFailure_stillDeliversBusinessDataWithoutNotification() {
        var received: CustomerIOBackgroundPushPayload? = null
        val config = installCallback { _, payload -> received = payload }
        val workManager = mockk<WorkManager>()
        every { workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) } throws
            RejectedExecutionException("injected rejected receipt scheduling")
        val provider = mockk<CustomerIOWorkManagerProvider>()
        every { provider.getWorkManager() } returns workManager
        val tracker = mockk<AsyncPushDeliveryTracker>(relaxed = true)
        val store = mockk<PendingDeliveryStore<PendingPushDeliveryMetric>>(relaxed = true)
        every { store.append(any<PendingPushDeliveryMetric>()) } returns true
        val id = "receipt-failure-${UUID.randomUUID()}"

        assertTrue(
            handler(
                data() + ("CIO-Background-Delivery-ID" to id),
                metrics = metrics(config, PushDeliveryMetricsBackgroundScheduler(provider, tracker), store)
            ).handleMessage(contextMock)
        )

        assertEquals(id, received?.deliveryId)
        assertEquals(mapOf("title" to "Order shipped", "body" to "", "order_id" to "42"), received?.data)
        verify(exactly = 1) { store.append(PendingPushDeliveryMetric(id, "tok_abc")) }
        verify(exactly = 0) { tracker.trackMetric(any(), any(), any()) }
        assertNoNotification()
    }

    @Test
    fun repeatedDelivery_reachesHostTwiceButPersistsOnlyOneReceipt() {
        val id = "background-${UUID.randomUUID()}"
        var calls = 0
        val config = installCallback { _, _ -> calls++ }
        val store = mockk<PendingDeliveryStore<PendingPushDeliveryMetric>>(relaxed = true)
        every { store.append(any<PendingPushDeliveryMetric>()) } returns true
        val scheduler = mockk<PushDeliveryMetricsBackgroundScheduler>(relaxed = true)
        val handler = handler(data() + ("CIO-Background-Delivery-ID" to id), metrics = metrics(config, scheduler, store))

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
        val config = installCallback(autoTrackPushEvents = false) { _, _ -> calls++ }
        val store = mockk<PendingDeliveryStore<PendingPushDeliveryMetric>>(relaxed = true)
        every { store.append(any<PendingPushDeliveryMetric>()) } returns true
        val scheduler = mockk<PushDeliveryMetricsBackgroundScheduler>(relaxed = true)

        assertTrue(
            handler(
                data() + ("CIO-Background-Delivery-ID" to "disabled-${UUID.randomUUID()}"),
                metrics = metrics(config, scheduler, store)
            ).handleMessage(contextMock)
        )

        assertEquals(1, calls)
        verify(exactly = 0) { store.append(any()) }
        verify(exactly = 0) { scheduler.scheduleDeliveredPushMetricsReceipt(any(), any()) }
        assertNoNotification()
    }

    @Test
    fun goEncodedProviderFixture_reachesHostUnchangedWithoutDisplay() {
        val bytes = javaClass.getResourceAsStream("/background_push/android_fcm.json")!!.use { it.readBytes() }
        val fields = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("message").getJSONObject("data")
        val data = fields.keys().asSequence().associateWith { fields.getString(it) }
        var received: CustomerIOBackgroundPushPayload? = null
        installCallback { _, payload -> received = payload }
        val processor = mockk<PushMessageProcessor>(relaxed = true)
        val metrics = mockk<BackgroundPushDeliveryMetrics>(relaxed = true)

        assertTrue(handler(data, processor, metrics).handleMessage(contextMock))

        assertEquals("fixture-delivery-1", received?.deliveryId)
        assertEquals(mapOf("title" to "Order shipped", "order_id" to "A-1001", "note" to "line one\nsays \"hi\" <b>&</b> ✓", "cıo-x" to "dotless"), received?.data)
        verify(exactly = 1) { metrics.trackDelivered("fixture-delivery-1", "fixture-fcm-token-1") }
        assertNoNotification()
    }

    @Test
    fun legacyPair_withCompleteBackgroundMetadata_keepsLegacyHandling() {
        var calls = 0
        installCallback { _, _ -> calls++ }
        val processor = mockk<PushMessageProcessor>(relaxed = true)
        val metrics = mockk<BackgroundPushDeliveryMetrics>(relaxed = true)

        assertTrue(handler(data() + legacy, processor, metrics).handleMessage(contextMock))

        assertEquals(1, postedNotifications().size)
        verify(exactly = 1) { processor.processRemoteMessageDeliveredMetrics("old", "old") }
        verify(exactly = 0) { metrics.trackDelivered(any(), any()) }
        assertEquals(0, calls)
    }

    @Test
    fun regularPush_doesNotBuildSilentPushMetrics() {
        val message = RemoteMessage.Builder("destination").setData(legacy).build()

        assertTrue(CustomerIOPushNotificationHandler(mockk(relaxed = true), message, NotificationChannelCreator()).handleMessage(contextMock))

        assertNull(SDKComponent.getOrNull<BackgroundPushDeliveryMetrics>())
    }
}
