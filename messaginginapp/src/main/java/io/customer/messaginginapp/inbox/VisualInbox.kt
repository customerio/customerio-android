package io.customer.messaginginapp.inbox

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.inbox.data.Branding
import io.customer.messaginginapp.inbox.data.InboxFetchOutcome
import io.customer.messaginginapp.inbox.data.InboxRepository
import io.customer.messaginginapp.inbox.data.InboxVisibility
import io.customer.messaginginapp.inbox.jist.JistInboxAdapter
import io.customer.messaginginapp.inbox.jist.JistInboxMessage
import io.customer.messaginginapp.state.InAppMessagingManager

/**
 * Data-layer entry point that the visual notification inbox overlay reads from.
 *
 * Builds on top of the existing [NotificationInbox] plumbing — it reuses
 * mark-opened / mark-deleted / track-clicked rather than duplicating mutation
 * logic — and adds the visual-inbox-specific surface:
 * - [isEnabled]: the server-driven enablement gate (X-CIO-Inbox-Enabled).
 * - [isInboxVisible] / [getVisibility]: the single visibility signal — the inbox shows
 *   ONLY when fully renderable (enabled + messages + templates + branding, each fresh
 *   or stale). A "no data" situation is [InboxVisibility.Hidden], never an error.
 * - [getSelectedMessages]: cio_inbox-prefix-filtered, expiry-dropped, priority+sentAt-sorted,
 *   mapped to Jist types with typed/nested properties preserved.
 * - [loadTemplatesAndBranding]: fetches (parallel) + caches templates/branding,
 *   returning a terminal [InboxFetchOutcome] (visible-vs-hidden; never an error to UI).
 * - [getTemplatesJson] / [getBranding]: cached accessors.
 *
 * Marked internal API; the overlay module consumes it via the SDK component.
 */
@InternalCustomerIOApi
class VisualInbox internal constructor(
    private val notificationInbox: NotificationInbox,
    private val repository: InboxRepository,
    private val inAppMessagingManager: InAppMessagingManager
) {
    val isEnabled: Boolean
        get() = inAppMessagingManager.getCurrentState().isInboxEnabled

    val isInboxVisible: Boolean
        get() = repository.isInboxVisible

    fun getVisibility(): InboxVisibility = repository.computeVisibility()

    fun getSelectedMessages(): List<JistInboxMessage> =
        JistInboxAdapter.toJist(repository.selectVisualInboxMessages())

    suspend fun loadTemplatesAndBranding(): InboxFetchOutcome = repository.loadTemplatesAndBranding()

    fun getTemplatesJson(): String? = repository.cachedTemplatesJson()

    fun getBranding(): Branding? = repository.cachedBranding()

    // --- Mutations: reuse existing NotificationInbox plumbing ---

    fun markMessageOpened(message: InboxMessage) = notificationInbox.markMessageOpened(message)

    fun markMessageDeleted(message: InboxMessage) = notificationInbox.markMessageDeleted(message)

    @JvmOverloads
    fun trackMessageClicked(message: InboxMessage, actionName: String? = null) =
        notificationInbox.trackMessageClicked(message, actionName)
}
