// Contract: cio-sdk-tools `verify push` reads these push log lines by their key tails (its
// docs/verify-contract.md). Renaming any `ev` or key, or changing a value's meaning, breaks it.
package io.customer.messagingpush

import android.content.Intent
import android.os.Bundle
import com.google.firebase.messaging.RemoteMessage
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.processor.PushDeliveryMetricsBackgroundScheduler
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.NotificationChannelCreator
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.LogTail
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.PendingDeliveryStore
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the real push code paths through the real logger implementation and asserts each line
 * the way the tool reads it: split on the last ` || `, each pair at its first `=`, then decode.
 */
@RunWith(RobolectricTestRunner::class)
class PushLogContractTest : IntegrationTest() {
    private val mockDeepLinkUtil = mockk<DeepLinkUtil>()
    private val lines = mutableListOf<String>()

    // Delivery ids can contain `=`; the title exercises the encoding
    private val givenDeliveryId = "dgS_${String.random}=="
    private val givenTitle = "50% off | today"

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        // The real logger implementation (UnitTest swaps in UnitTestLogger), so each
                        // asserted line is exactly what reaches logcat or a wrapper's dispatcher
                        overrides.remove(dependencyKey<Logger>(null))
                        overrideDependency<DeepLinkUtil>(mockDeepLinkUtil)
                        overrideDependency<EventBus>(mockk(relaxed = true))
                        overrideDependency<PushDeliveryMetricsBackgroundScheduler>(mockk(relaxed = true))
                        overrideDependency<PendingDeliveryStore<PendingPushDeliveryMetric>>(mockk(relaxed = true))
                    }
                }
            }
        )

        SDKComponent.logger.logLevel = CioLogLevel.DEBUG
        SDKComponent.logger.setLogDispatcher { _, line -> lines.add(line) }
    }

    @Test
    fun gcmBroadcast_expectReceivedThenDeliveredThenDuplicate() {
        val intent = Intent().putExtras(cioPushBundle(dataTitle = givenTitle))

        SDKComponent.pushMessageProcessor.processGCMMessageIntent(intent)
        SDKComponent.pushMessageProcessor.processGCMMessageIntent(intent)

        line("push.received") shouldBeEqualTo Parsed(
            sentence = "[Push] Received new message with deliveryId: $givenDeliveryId",
            fields = listOf("id" to givenDeliveryId, "title" to givenTitle)
        )
        line("push.delivered") shouldBeEqualTo Parsed(
            sentence = "[Push] Tracking push message delivered with deliveryId: $givenDeliveryId",
            fields = listOf("id" to givenDeliveryId)
        )
        line("push.duplicate") shouldBeEqualTo Parsed(
            sentence = "[Push] Received duplicate message with deliveryId: $givenDeliveryId",
            fields = listOf("id" to givenDeliveryId)
        )
    }

    @Test
    fun gcmBroadcast_givenNotificationPayloadTitle_expectReceivedCarriesIt() {
        val bundle = cioPushBundle(dataTitle = null).apply {
            putString("gcm.n.e", "1")
            putString("gcm.n.title", givenTitle)
        }

        SDKComponent.pushMessageProcessor.processGCMMessageIntent(Intent().putExtras(bundle))

        line("push.received").fields shouldBeEqualTo listOf("id" to givenDeliveryId, "title" to givenTitle)
    }

    @Test
    fun firebaseMessage_expectPermissionReceivedDeliveredAndShown() {
        val handler = CustomerIOPushNotificationHandler(
            pushMessageProcessor = SDKComponent.pushMessageProcessor,
            remoteMessage = RemoteMessage(cioPushBundle(dataTitle = givenTitle)),
            notificationChannelCreator = NotificationChannelCreator()
        )

        handler.handleMessage(contextMock, handleNotificationTrigger = true)

        line("push.permission") shouldBeEqualTo Parsed(
            sentence = "[Push] Notification permission: granted",
            fields = listOf("status" to "granted")
        )
        line("push.received").fields shouldBeEqualTo listOf("id" to givenDeliveryId, "title" to givenTitle)
        line("push.delivered").fields shouldBeEqualTo listOf("id" to givenDeliveryId)
        line("push.shown").fields shouldBeEqualTo listOf("id" to givenDeliveryId, "title" to givenTitle)
    }

    @Test
    fun firebaseMessage_givenNoTitle_expectTitleKeyOmitted() {
        val handler = CustomerIOPushNotificationHandler(
            pushMessageProcessor = SDKComponent.pushMessageProcessor,
            remoteMessage = RemoteMessage(cioPushBundle(dataTitle = null)),
            notificationChannelCreator = NotificationChannelCreator()
        )

        handler.handleMessage(contextMock, handleNotificationTrigger = true)

        line("push.received").fields shouldBeEqualTo listOf("id" to givenDeliveryId)
        line("push.shown").fields shouldBeEqualTo listOf("id" to givenDeliveryId)
    }

    @Test
    fun click_givenCioPush_expectOpenedAndDeepLinkWithBehavior() {
        val givenLink = "https://cio.example.com/sale?a=b c"
        val payload = clickPayload(deepLink = givenLink)
        stubDeepLink(givenLink, defaultHostAppIntent = Intent())

        click(payload)

        line("push.opened") shouldBeEqualTo Parsed(
            sentence = "[Push] Tracking push message opened with payload: $payload",
            fields = listOf("id" to givenDeliveryId, "title" to givenTitle)
        )
        line("push.deep_link") shouldBeEqualTo Parsed(
            sentence = "[Push] Handling push notification deep link with payload: $payload - " +
                "pushClickBehavior: ${PushClickBehavior.ACTIVITY_PREVENT_RESTART}",
            fields = listOf("url" to givenLink, "behavior" to "ACTIVITY_PREVENT_RESTART")
        )
    }

    @Test
    fun click_givenLocallyStartedNotification_expectOpenSkipped() {
        val payload = clickPayload(deliveryId = "", deliveryToken = "")
        stubDeepLink(null, defaultHostAppIntent = Intent())

        click(payload)

        line("push.open_skipped").fields shouldBeEqualTo listOf("reason" to "no_delivery_id")
        lines.none { it.contains("ev=push.opened") } shouldBeEqualTo true
    }

    @Test
    fun click_givenAppCallback_expectOutcomeAppCallback() {
        val callback = mockk<CustomerIOPushNotificationCallback>(relaxed = true)
        every { callback.onNotificationClicked(any(), any()) } answers { }
        moduleConfig(notificationCallback = callback)

        click(clickPayload())

        assertOutcome(sentence = "[Push] Deep link handled by host app callback implementation", outcome = "app_callback")
    }

    @Test
    fun click_givenLinkTheAppHandles_expectOutcomeAppNavigation() {
        val givenLink = "app://sale"
        stubDeepLink(givenLink, linkHostAppIntent = Intent())

        click(clickPayload(deepLink = givenLink))

        assertOutcome(sentence = "[Push] Deep link handled by internal host app navigation", outcome = "app_navigation")
    }

    @Test
    fun click_givenLinkAnotherAppHandles_expectOutcomeExternalApp() {
        val givenLink = "https://cio.example.com/"
        stubDeepLink(givenLink, linkExternalIntent = Intent())

        click(clickPayload(deepLink = givenLink))

        assertOutcome(sentence = "[Push] Deep link handled by external app", outcome = "external_app")
    }

    @Test
    fun click_givenNoLink_expectOutcomeDefaultApp() {
        stubDeepLink(null, defaultHostAppIntent = Intent())

        click(clickPayload())

        assertOutcome(sentence = "[Push] Deep link handled by opening default host app", outcome = "default_app")
    }

    @Test
    fun click_givenNothingHandlesIt_expectOutcomeNotHandled() {
        stubDeepLink(null)

        click(clickPayload())

        assertOutcome(sentence = "[Push] Deep link was not handled", outcome = "not_handled")
    }

    private data class Parsed(val sentence: String, val fields: List<Pair<String, String>>)

    private fun cioPushBundle(dataTitle: String?) = Bundle().apply {
        putString(PushTrackingUtil.DELIVERY_ID_KEY, givenDeliveryId)
        putString(PushTrackingUtil.DELIVERY_TOKEN_KEY, String.random)
        dataTitle?.let { putString(CustomerIOPushNotificationHandler.TITLE_KEY, it) }
    }

    private fun clickPayload(
        deepLink: String? = null,
        deliveryId: String = givenDeliveryId,
        deliveryToken: String = String.random
    ) = CustomerIOParsedPushPayload(
        extras = Bundle.EMPTY,
        deepLink = deepLink,
        cioDeliveryId = deliveryId,
        cioDeliveryToken = deliveryToken,
        title = givenTitle,
        body = String.random
    )

    private fun moduleConfig(notificationCallback: CustomerIOPushNotificationCallback?) = ModuleMessagingPushFCM(
        moduleConfig = MessagingPushModuleConfig.Builder().apply {
            notificationCallback?.let { setNotificationCallback(it) }
        }.build()
    ).attachToSDKComponent()

    private fun stubDeepLink(
        deepLink: String?,
        defaultHostAppIntent: Intent? = null,
        linkHostAppIntent: Intent? = null,
        linkExternalIntent: Intent? = null
    ) {
        every { mockDeepLinkUtil.createDefaultHostAppIntent(any()) } returns defaultHostAppIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (deepLink == null) return
        every { mockDeepLinkUtil.createDeepLinkHostAppIntent(any(), deepLink) } returns linkHostAppIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        every { mockDeepLinkUtil.createDeepLinkExternalIntent(any(), deepLink) } returns linkExternalIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun click(payload: CustomerIOParsedPushPayload) {
        val intent = Intent().putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, payload)
        SDKComponent.pushMessageProcessor.processNotificationClick(contextMock, intent)
    }

    private fun assertOutcome(sentence: String, outcome: String) {
        line("push.deep_link_outcome") shouldBeEqualTo Parsed(sentence = sentence, fields = listOf("outcome" to outcome))
    }

    // The single line carrying this event, parsed the way the tool parses it
    private fun line(ev: String): Parsed {
        val parsed = lines.map(::parse).filter { (event, _) -> event == ev }
        parsed.size shouldBeEqualTo 1
        return parsed.single().second
    }

    private fun parse(line: String): Pair<String?, Parsed> {
        if (!line.contains(LogTail.DELIMITER)) return null to Parsed(line, emptyList())
        val pairs = line.substringAfterLast(LogTail.DELIMITER).split(' ').map { pair ->
            pair.substringBefore('=') to percentDecode(pair.substringAfter('='))
        }
        val (evKey, ev) = pairs.first()
        evKey shouldBeEqualTo "ev"
        return ev to Parsed(sentence = line.substringBeforeLast(LogTail.DELIMITER), fields = pairs.drop(1))
    }

    private fun percentDecode(value: String): String {
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                bytes.write(value.substring(index + 1, index + 3).toInt(16))
                index += 3
            } else {
                bytes.write(value[index].toString().toByteArray(Charsets.UTF_8))
                index += 1
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
