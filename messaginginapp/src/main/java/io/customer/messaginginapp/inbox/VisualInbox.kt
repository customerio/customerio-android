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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

    /**
     * Reactive signal that fires on each distinct change to a store input that can alter the
     * inbox's visibility or unread count: the enablement gate or the inbox message set
     * (including opened-state changes). Backed by the store's StateFlow, so it emits the
     * current value immediately on collection (a late-mounting overlay still gets latest state).
     * The overlay maps each emission to a fresh read of CACHED state via [getVisibility] /
     * [getSelectedMessages]; this never triggers a network fetch.
     */
    fun observeInboxChanges(): Flow<Unit> =
        inAppMessagingManager.state
            .map { state -> InboxStateKey(state.isInboxEnabled, state.inboxMessages) }
            .distinctUntilChanged()
            .map { }

    /**
     * Companion to [observeInboxChanges] that fires when a templates/branding fetch cycle
     * completes. It closes a reactive gap: a fetch populates the cache WITHOUT changing
     * `isInboxEnabled` or `inboxMessages`, so [observeInboxChanges] would not re-emit and an
     * overlay that computed Hidden on the enablement flip would stay Hidden. Re-reading cached
     * state on this emission lets it transition Hidden -> Visible. Does NOT trigger a fetch.
     */
    fun observeContentChanges(): Flow<Unit> = repository.observeContentChanges()

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

/**
 * Equality key for [VisualInbox.observeInboxChanges]: the store inputs whose change can alter
 * the visual inbox's visibility or unread count. Used with `distinctUntilChanged` so the overlay
 * only re-reads cached state when something it cares about actually changed.
 */
private data class InboxStateKey(
    val isInboxEnabled: Boolean,
    val inboxMessages: Set<InboxMessage>
)
