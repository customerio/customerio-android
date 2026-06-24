package io.customer.messaginginbox

import io.customer.jist.JistActionEvent
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.inbox.VisualInbox
import io.customer.messaginginapp.inbox.data.Branding
import io.customer.messaginginapp.inbox.data.InboxVisibility
import io.customer.messaginginapp.inbox.jist.JistInboxAdapter
import io.customer.messaginginapp.type.InboxActionMessage
import io.customer.messaginginapp.type.InboxEventListener
import io.mockk.mockk
import io.mockk.verify
import java.util.Date
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test

/**
 * Unit tests for inbox action mapping (items 12 + 13): [resolveInboxAction] (dismiss / openUrl /
 * deeplink / unknown) and [VisualInboxController.handleAction] (dismiss removes; non-dismiss tracks
 * clicked + offers host interception + returns the correct default navigation).
 */
class InboxActionTest {

    private fun message(queueId: String): InboxMessage = InboxMessage(
        queueId = queueId,
        deliveryId = "d-$queueId",
        expiry = null,
        sentAt = Date(0),
        topics = emptyList(),
        type = "basic",
        opened = false,
        priority = null,
        properties = emptyMap()
    )

    private fun visible(messages: List<InboxMessage>): InboxVisibility.Visible =
        InboxVisibility.Visible(templatesJson = "{}", branding = Branding(), messages = messages, fromCache = false)

    /** Builds a Jist action event whose `data` object carries optional `behavior` / `url`. */
    private fun event(name: String = "messageAction", behavior: String? = null, url: String? = null): JistActionEvent {
        val data = JsonObject(
            buildMap {
                if (behavior != null) put("behavior", JsonPrimitive(behavior))
                if (url != null) put("url", JsonPrimitive(url))
            }
        )
        return JistActionEvent(component = "button", name = name, data = data, meta = JsonObject(emptyMap()))
    }

    // --- resolveInboxAction mapping ---

    @Test
    fun resolve_givenDismissBehavior_expectDismiss() {
        resolveInboxAction(event(behavior = "dismiss")).shouldBeInstanceOf<InboxAction.Dismiss>()
    }

    @Test
    fun resolve_givenDismissSentinels_expectDismiss() {
        resolveInboxAction(event(name = "dismiss")).shouldBeInstanceOf<InboxAction.Dismiss>()
        resolveInboxAction(event(url = "#dismiss")).shouldBeInstanceOf<InboxAction.Dismiss>()
    }

    @Test
    fun resolve_givenOpenUrlBehavior_expectOpenUrl() {
        val action = resolveInboxAction(event(behavior = "openUrl", url = "https://example.com"))
        action.shouldBeInstanceOf<InboxAction.OpenUrl>()
        action.url shouldBeEqualTo "https://example.com"
    }

    @Test
    fun resolve_givenNewTabBehavior_expectOpenUrl() {
        resolveInboxAction(event(behavior = "newTab", url = "https://x.io")).shouldBeInstanceOf<InboxAction.OpenUrl>()
    }

    @Test
    fun resolve_givenHttpUrlNoBehavior_expectOpenUrl() {
        resolveInboxAction(event(url = "http://plain.example")).shouldBeInstanceOf<InboxAction.OpenUrl>()
        resolveInboxAction(event(url = "https://plain.example")).shouldBeInstanceOf<InboxAction.OpenUrl>()
    }

    @Test
    fun resolve_givenDeeplinkBehavior_expectDeeplink() {
        val action = resolveInboxAction(event(behavior = "deeplink", url = "myapp://home"))
        action.shouldBeInstanceOf<InboxAction.Deeplink>()
        action.url shouldBeEqualTo "myapp://home"
    }

    @Test
    fun resolve_givenNonHttpSchemeNoBehavior_expectDeeplink() {
        resolveInboxAction(event(url = "myapp://profile")).shouldBeInstanceOf<InboxAction.Deeplink>()
    }

    @Test
    fun resolve_givenNoUrlOrBehavior_expectUnknown() {
        resolveInboxAction(event()).shouldBeInstanceOf<InboxAction.Unknown>()
    }

    @Test
    fun resolve_givenBlankUrl_expectUnknown() {
        resolveInboxAction(event(url = "   ")).shouldBeInstanceOf<InboxAction.Unknown>()
    }

    @Test
    fun resolve_givenNonObjectData_expectUnknownNoThrow() {
        // data is a primitive, not an object — safe casts must yield Unknown, never throw.
        val ev = JistActionEvent(component = "c", name = "x", data = JsonPrimitive("oops"), meta = JsonObject(emptyMap()))
        resolveInboxAction(ev).shouldBeInstanceOf<InboxAction.Unknown>()
    }

    // --- handleAction: dismiss vs openUrl vs deeplink vs host-handled ---

    @Test
    fun handleAction_givenDismiss_expectMarkedDeletedAndNoNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox)

        val nav = controller.handleAction(visible(messages), JistInboxAdapter.toJist(messages.first()), event(behavior = "dismiss"))

        nav shouldBeEqualTo InboxNavigation.None
        verify(exactly = 1) { visualInbox.markMessageDeleted(match { it.queueId == "a" }) }
        // Dismiss is not a click — no clicked metric.
        verify(exactly = 0) { visualInbox.trackMessageClicked(any(), any()) }
    }

    @Test
    fun handleAction_givenOpenUrlNoListener_expectTrackedAndOpenUrlNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox)

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(name = "messageAction", url = "https://example.com")
        )

        nav.shouldBeInstanceOf<InboxNavigation.OpenUrl>()
        (nav as InboxNavigation.OpenUrl).url shouldBeEqualTo "https://example.com"
        verify(exactly = 1) { visualInbox.trackMessageClicked(match { it.queueId == "a" }, "messageAction") }
    }

    @Test
    fun handleAction_givenDeeplinkNoListener_expectTrackedAndNoNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        // Relaxed logger mock: the deeplink path logs, and the real LogcatLogger calls
        // android.util.Log (not mocked on the JVM).
        val controller = VisualInboxController(visualInbox, logger = mockk(relaxed = true))

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(behavior = "deeplink", url = "myapp://home")
        )

        // No listener => deeplink is logged + no-op (SDK can't resolve app routes).
        nav shouldBeEqualTo InboxNavigation.None
        verify(exactly = 1) { visualInbox.trackMessageClicked(match { it.queueId == "a" }, any()) }
    }

    @Test
    fun handleAction_givenHostHandlesAction_expectTrackedAndNoNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val captured = mutableListOf<Triple<InboxActionMessage, String, String>>()
        val listener = object : InboxEventListener {
            override fun messageActionTaken(message: InboxActionMessage, actionName: String, actionValue: String): Boolean {
                captured.add(Triple(message, actionName, actionValue))
                return true // host handled it
            }
        }
        val controller = VisualInboxController(visualInbox, inboxEventListener = listener)

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(name = "messageAction", url = "https://example.com")
        )

        // Host handled it: even though it was an http url, the SDK does not navigate.
        nav shouldBeEqualTo InboxNavigation.None
        // Still tracked (a click happened) and the listener got message id + delivery + action value.
        verify(exactly = 1) { visualInbox.trackMessageClicked(any(), any()) }
        captured.size shouldBeEqualTo 1
        captured.first().first.messageId shouldBeEqualTo "a"
        captured.first().first.deliveryId shouldBeEqualTo "d-a"
        captured.first().third shouldBeEqualTo "https://example.com"
    }

    @Test
    fun handleAction_givenHostDeclines_expectSdkDefaultNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val listener = object : InboxEventListener {
            override fun messageActionTaken(message: InboxActionMessage, actionName: String, actionValue: String) = false
        }
        val controller = VisualInboxController(visualInbox, inboxEventListener = listener)

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(url = "https://example.com")
        )

        nav.shouldBeInstanceOf<InboxNavigation.OpenUrl>()
    }

    @Test
    fun handleAction_givenThrowingHostListener_expectFallbackToDefaultNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val listener = object : InboxEventListener {
            override fun messageActionTaken(message: InboxActionMessage, actionName: String, actionValue: String): Boolean =
                throw RuntimeException("boom")
        }
        // Relaxed logger: the catch branch logs the listener failure.
        val controller = VisualInboxController(visualInbox, inboxEventListener = listener, logger = mockk(relaxed = true))

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(url = "https://example.com")
        )

        // A throwing listener must not crash the SDK; it falls back to default navigation.
        nav.shouldBeInstanceOf<InboxNavigation.OpenUrl>()
    }

    @Test
    fun handleAction_calledTwiceSameMessage_expectTrackedOnce() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox)

        controller.handleAction(visible(messages), JistInboxAdapter.toJist(messages.first()), event(url = "https://x.io"))
        controller.handleAction(visible(messages), JistInboxAdapter.toJist(messages.first()), event(url = "https://x.io"))

        verify(exactly = 1) { visualInbox.trackMessageClicked(any(), any()) }
    }

    // --- observe callbacks (item 14): shown / opened / dismissed ---

    private class RecordingListener : InboxEventListener {
        val shown = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        override fun messageActionTaken(message: InboxActionMessage, actionName: String, actionValue: String) = false
        override fun messageShown(message: InboxActionMessage) { shown.add(message.messageId) }
        override fun messageOpened(message: InboxActionMessage) { opened.add(message.messageId) }
        override fun messageDismissed(message: InboxActionMessage) { dismissed.add(message.messageId) }
    }

    @Test
    fun markOpenMessagesOpened_givenUnopened_expectMessageOpenedFired() {
        val messages = listOf(message("a"), message("b"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val listener = RecordingListener()
        val controller = VisualInboxController(visualInbox, inboxEventListener = listener)

        controller.markOpenMessagesOpened(visible(messages))

        listener.opened shouldBeEqualTo listOf("a", "b")
        verify(exactly = 1) { visualInbox.markMessageOpened(match { it.queueId == "a" }) }
        verify(exactly = 1) { visualInbox.markMessageOpened(match { it.queueId == "b" }) }
    }

    @Test
    fun dismissMessage_expectMessageDismissedFired() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val listener = RecordingListener()
        val controller = VisualInboxController(visualInbox, inboxEventListener = listener)

        controller.dismissMessage(visible(messages), "a")

        listener.dismissed shouldBeEqualTo listOf("a")
        verify(exactly = 1) { visualInbox.markMessageDeleted(match { it.queueId == "a" }) }
    }

    @Test
    fun notifyMessageShown_calledTwiceSameMessage_expectShownFiredOnce() {
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val listener = RecordingListener()
        val controller = VisualInboxController(visualInbox, inboxEventListener = listener)
        val jist = JistInboxAdapter.toJist(message("a"))

        controller.notifyMessageShown(jist)
        controller.notifyMessageShown(jist)

        listener.shown shouldBeEqualTo listOf("a")
    }
}
