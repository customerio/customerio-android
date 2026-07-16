package io.customer.messaginginbox

import io.customer.jist.JistActionEvent
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.inbox.VisualInbox
import io.customer.messaginginapp.inbox.data.Branding
import io.customer.messaginginapp.inbox.data.InboxVisibility
import io.customer.messaginginapp.inbox.jist.JistInboxAdapter
import io.customer.messaginginapp.type.InboxEventListener
import io.mockk.mockk
import io.mockk.verify
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test

/**
 * Unit tests for inbox action mapping (items 12 + 13): [resolveInboxAction] (dismiss / openUrl /
 * openDeeplink / performAction / unknown, web-schema `data.action` value + legacy `data.url`
 * fallback) and [VisualInboxController.handleAction] (dismiss removes; non-dismiss tracks clicked +
 * offers host interception + returns the correct default navigation).
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

    /**
     * Builds a Jist action event whose `data` object carries optional `behavior` / `action`
     * (web-schema value) / `url` (legacy value) / `name` (web-schema action name).
     */
    private fun event(
        name: String = "messageAction",
        behavior: String? = null,
        action: String? = null,
        url: String? = null,
        dataName: String? = null,
        newTab: Boolean? = null
    ): JistActionEvent {
        val data = JsonObject(
            buildMap {
                if (behavior != null) put("behavior", JsonPrimitive(behavior))
                if (action != null) put("action", JsonPrimitive(action))
                if (url != null) put("url", JsonPrimitive(url))
                if (dataName != null) put("name", JsonPrimitive(dataName))
                if (newTab != null) put("newTab", JsonPrimitive(newTab))
            }
        )
        return JistActionEvent(component = "button", name = name, data = data, meta = JsonObject(emptyMap()))
    }

    /** Builds an event from a raw JSON `data` object literal — for exercising non-string field shapes. */
    private fun rawEvent(dataJson: String): JistActionEvent = JistActionEvent(
        component = "button",
        name = "messageAction",
        data = Json.parseToJsonElement(dataJson),
        meta = JsonObject(emptyMap())
    )

    // --- resolveInboxAction mapping ---

    @Test
    fun resolve_givenDismissBehavior_expectDismiss() {
        resolveInboxAction(event(behavior = "dismiss")).shouldBeInstanceOf<InboxAction.Dismiss>()
    }

    @Test
    fun resolve_givenDismissSentinels_expectDismiss() {
        resolveInboxAction(event(name = "dismiss")).shouldBeInstanceOf<InboxAction.Dismiss>()
        resolveInboxAction(event(action = "#dismiss")).shouldBeInstanceOf<InboxAction.Dismiss>()
    }

    @Test
    fun resolve_givenOpenUrlBehavior_expectOpenUrl() {
        val action = resolveInboxAction(event(behavior = "openUrl", action = "https://example.com"))
        action.shouldBeInstanceOf<InboxAction.OpenUrl>()
        action.url shouldBeEqualTo "https://example.com"
    }

    @Test
    fun resolve_givenRealOpenUrlWithNewTabFlag_expectOpenUrl() {
        // Web emits newTab as a BOOLEAN flag alongside behavior:"openUrl", not a behavior of its own.
        // The flag is ignored on mobile (no "new tab"); the openUrl still opens externally.
        val action = resolveInboxAction(event(behavior = "openUrl", action = "https://x.io", newTab = true))
        action.shouldBeInstanceOf<InboxAction.OpenUrl>()
        action.url shouldBeEqualTo "https://x.io"
    }

    @Test
    fun resolve_givenUnrecognizedBehavior_expectUnknownCarryingValue() {
        // A behavior outside openUrl/openDeeplink/performAction/dismiss — the legacy demo `deeplink`,
        // or the web-only `newTab` used (incorrectly) as a behavior — is a host-only no-op; no
        // browser/deeplink guessing (mirrors iOS strict switch).
        resolveInboxAction(event(behavior = "deeplink", action = "myapp://x")).let {
            it.shouldBeInstanceOf<InboxAction.Unknown>()
            it.url shouldBeEqualTo "myapp://x"
        }
        resolveInboxAction(event(behavior = "newTab", action = "https://x.io")).shouldBeInstanceOf<InboxAction.Unknown>()
    }

    @Test
    fun resolve_givenNonStringFields_expectRejected() {
        // contentOrNull would coerce numbers/bools to text; the web parser rejects non-strings, so a
        // boolean/numeric action (or behavior) must not reach navigation/resolution.
        resolveInboxAction(rawEvent("""{"behavior":"openUrl","action":true}""")).shouldBeInstanceOf<InboxAction.Unknown>()
        resolveInboxAction(rawEvent("""{"behavior":123,"action":"https://x.io"}""")).shouldBeInstanceOf<InboxAction.Unknown>()
    }

    @Test
    fun resolve_givenLegacyUrlValue_expectOpenUrl() {
        // `data.action` absent but legacy `data.url` present — value falls back to url.
        val action = resolveInboxAction(event(behavior = "openUrl", url = "https://legacy.example"))
        action.shouldBeInstanceOf<InboxAction.OpenUrl>()
        action.url shouldBeEqualTo "https://legacy.example"
    }

    @Test
    fun resolve_givenBothActionAndUrl_expectActionPreferred() {
        val action = resolveInboxAction(event(behavior = "openUrl", action = "https://new.example", url = "https://old.example"))
        action.url shouldBeEqualTo "https://new.example"
    }

    @Test
    fun resolve_givenValueButNoBehavior_expectUnknown() {
        // No behavior → host-only no-op regardless of the value's scheme (mirrors iOS; the SDK never
        // guesses browser-vs-deeplink from the URL shape).
        resolveInboxAction(event(action = "https://plain.example")).shouldBeInstanceOf<InboxAction.Unknown>()
        resolveInboxAction(event(action = "myapp://profile")).shouldBeInstanceOf<InboxAction.Unknown>()
    }

    @Test
    fun resolve_givenOpenDeeplinkBehavior_expectDeeplink() {
        val action = resolveInboxAction(event(behavior = "openDeeplink", action = "myapp://home"))
        action.shouldBeInstanceOf<InboxAction.Deeplink>()
        action.url shouldBeEqualTo "myapp://home"
    }

    @Test
    fun resolve_givenPerformActionBehavior_expectPerformActionCarryingValue() {
        val action = resolveInboxAction(event(behavior = "performAction", action = "customThing"))
        action.shouldBeInstanceOf<InboxAction.PerformAction>()
        // The action value is retained so the host listener can perform the custom action.
        action.url shouldBeEqualTo "customThing"
    }

    @Test
    fun resolve_givenNoValueOrBehavior_expectUnknown() {
        resolveInboxAction(event()).shouldBeInstanceOf<InboxAction.Unknown>()
    }

    @Test
    fun resolve_givenBlankValue_expectUnknown() {
        resolveInboxAction(event(action = "   ")).shouldBeInstanceOf<InboxAction.Unknown>()
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
        verify(exactly = 0) { visualInbox.trackMessageClicked(any(), any(), any()) }
    }

    @Test
    fun handleAction_givenOpenUrlNoListener_expectTrackedAndOpenUrlNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox)

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(name = "messageAction", behavior = "openUrl", action = "https://example.com")
        )

        nav.shouldBeInstanceOf<InboxNavigation.OpenUrl>()
        (nav as InboxNavigation.OpenUrl).url shouldBeEqualTo "https://example.com"
        verify(exactly = 1) { visualInbox.trackMessageClicked(match { it.queueId == "a" }, "messageAction", "https://example.com") }
    }

    @Test
    fun handleAction_givenDeeplinkNoListener_expectTrackedAndOpenDeeplinkNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox, logger = mockk(relaxed = true))

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(behavior = "openDeeplink", action = "myapp://home")
        )

        // No host listener => the SDK routes the deeplink itself (the overlay resolves the intent).
        nav.shouldBeInstanceOf<InboxNavigation.OpenDeeplink>()
        (nav as InboxNavigation.OpenDeeplink).url shouldBeEqualTo "myapp://home"
        verify(exactly = 1) { visualInbox.trackMessageClicked(match { it.queueId == "a" }, any(), "myapp://home") }
    }

    @Test
    fun handleAction_givenPerformActionNoListener_expectTrackedAndNoNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox, logger = mockk(relaxed = true))

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(behavior = "performAction", action = "doThing")
        )

        // performAction is host-only; with no host listener there is nothing for the SDK to navigate.
        nav shouldBeEqualTo InboxNavigation.None
        verify(exactly = 1) { visualInbox.trackMessageClicked(any(), any(), "doThing") }
    }

    @Test
    fun handleAction_givenPerformActionWithListener_expectListenerReceivesValue() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val captured = mutableListOf<Pair<String, String>>()
        val listener = object : InboxEventListener {
            override fun messageActionTaken(message: InboxMessage, actionName: String, actionValue: String): Boolean {
                captured.add(actionName to actionValue)
                return true
            }
        }
        val controller = VisualInboxController(visualInbox, inboxEventListenerProvider = { listener })

        controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(behavior = "performAction", action = "custom-action", dataName = "custom-name")
        )

        // The host must receive the performAction value (regression: it was dropped as empty before).
        captured.single() shouldBeEqualTo ("custom-name" to "custom-action")
    }

    @Test
    fun handleAction_givenDataName_expectListenerAndTrackUseDataName() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val captured = mutableListOf<String>()
        val listener = object : InboxEventListener {
            override fun messageActionTaken(message: InboxMessage, actionName: String, actionValue: String): Boolean {
                captured.add(actionName)
                return true
            }
        }
        val controller = VisualInboxController(visualInbox, inboxEventListenerProvider = { listener })

        controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(name = "messageAction", dataName = "cta_click", action = "https://x.io")
        )

        // The host-facing action name comes from web-schema `data.name`, not the Jist event name.
        captured.single() shouldBeEqualTo "cta_click"
        verify(exactly = 1) { visualInbox.trackMessageClicked(any(), "cta_click", "https://x.io") }
    }

    @Test
    fun handleAction_givenHostHandlesNavAction_expectTrackedAndDismiss() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val captured = mutableListOf<Triple<InboxMessage, String, String>>()
        val listener = object : InboxEventListener {
            override fun messageActionTaken(message: InboxMessage, actionName: String, actionValue: String): Boolean {
                captured.add(Triple(message, actionName, actionValue))
                return true // host handled it
            }
        }
        val controller = VisualInboxController(visualInbox, inboxEventListenerProvider = { listener })

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(name = "messageAction", behavior = "openUrl", action = "https://example.com")
        )

        // Host handled the navigating action itself: the SDK does not navigate, but the sheet still
        // dismisses so the inbox doesn't linger over the host's destination.
        nav shouldBeEqualTo InboxNavigation.Dismiss
        // Still tracked (a click happened) and the listener got message id + delivery + action value.
        verify(exactly = 1) { visualInbox.trackMessageClicked(any(), any(), "https://example.com") }
        captured.size shouldBeEqualTo 1
        captured.first().first.queueId shouldBeEqualTo "a"
        captured.first().first.deliveryId shouldBeEqualTo "d-a"
        captured.first().third shouldBeEqualTo "https://example.com"
    }

    @Test
    fun handleAction_givenBooleanDismissFlag_expectMessageRemoved() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox, logger = mockk(relaxed = true))

        // Real web shape: a JSON boolean `dismiss: true` chained onto a performAction.
        controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            rawEvent("""{"behavior":"performAction","action":"do","dismiss":true}""")
        )

        verify(exactly = 1) { visualInbox.markMessageDeleted(match { it.queueId == "a" }) }
    }

    @Test
    fun handleAction_givenHostDeclines_expectSdkDefaultNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val listener = object : InboxEventListener {
            override fun messageActionTaken(message: InboxMessage, actionName: String, actionValue: String) = false
        }
        val controller = VisualInboxController(visualInbox, inboxEventListenerProvider = { listener })

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(behavior = "openUrl", action = "https://example.com")
        )

        nav.shouldBeInstanceOf<InboxNavigation.OpenUrl>()
    }

    @Test
    fun handleAction_givenThrowingHostListener_expectFallbackToDefaultNav() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val listener = object : InboxEventListener {
            override fun messageActionTaken(message: InboxMessage, actionName: String, actionValue: String): Boolean =
                throw RuntimeException("boom")
        }
        // Relaxed logger: the catch branch logs the listener failure.
        val controller = VisualInboxController(visualInbox, inboxEventListenerProvider = { listener }, logger = mockk(relaxed = true))

        val nav = controller.handleAction(
            visible(messages),
            JistInboxAdapter.toJist(messages.first()),
            event(behavior = "openUrl", action = "https://example.com")
        )

        // A throwing listener must not crash the SDK; it falls back to default navigation.
        nav.shouldBeInstanceOf<InboxNavigation.OpenUrl>()
    }

    @Test
    fun handleAction_calledTwiceSameMessage_expectTrackedOnce() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox)

        controller.handleAction(visible(messages), JistInboxAdapter.toJist(messages.first()), event(behavior = "openUrl", action = "https://x.io"))
        controller.handleAction(visible(messages), JistInboxAdapter.toJist(messages.first()), event(behavior = "openUrl", action = "https://x.io"))

        verify(exactly = 1) { visualInbox.trackMessageClicked(any(), any(), "https://x.io") }
    }

    // --- observe callbacks (item 14): shown / opened / dismissed ---

    private class RecordingListener : InboxEventListener {
        val shown = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        override fun messageActionTaken(message: InboxMessage, actionName: String, actionValue: String) = false
        override fun messageShown(message: InboxMessage) { shown.add(message.queueId) }
        override fun messageOpened(message: InboxMessage) { opened.add(message.queueId) }
        override fun messageDismissed(message: InboxMessage) { dismissed.add(message.queueId) }
    }

    @Test
    fun markOpenMessagesOpened_givenUnopened_expectMessageOpenedFired() {
        val messages = listOf(message("a"), message("b"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val listener = RecordingListener()
        val controller = VisualInboxController(visualInbox, inboxEventListenerProvider = { listener })

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
        val controller = VisualInboxController(visualInbox, inboxEventListenerProvider = { listener })

        controller.dismissMessage(visible(messages), "a")

        listener.dismissed shouldBeEqualTo listOf("a")
        verify(exactly = 1) { visualInbox.markMessageDeleted(match { it.queueId == "a" }) }
    }

    @Test
    fun notifyMessageShown_calledTwiceSameMessage_expectShownFiredOnce() {
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val listener = RecordingListener()
        val controller = VisualInboxController(visualInbox, inboxEventListenerProvider = { listener })
        val messageA = message("a")
        val visibility = visible(listOf(messageA))
        val jist = JistInboxAdapter.toJist(messageA)

        controller.notifyMessageShown(visibility, jist)
        controller.notifyMessageShown(visibility, jist)

        listener.shown shouldBeEqualTo listOf("a")
    }
}
