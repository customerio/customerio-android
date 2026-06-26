package io.customer.messaginginbox

import io.customer.messaginginapp.inbox.VisualInbox
import io.customer.messaginginapp.inbox.data.InboxVisibility
import io.customer.messaginginapp.inbox.jist.JistInboxMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * Read-only UI snapshot of the visual inbox, derived from the [VisualInbox] data layer. The
 * overlay never mutates inbox content except for the auto-mark-opened side effect; everything
 * the UI renders is read from here.
 *
 * @param loading true while a templates/branding load cycle is in flight.
 * @param visibility the data layer's terminal visibility signal.
 * @param messages the selected/sorted/typed message list for Jist rendering.
 * @param unopenedCount unread badge count, computed from [messages].
 */
internal data class VisualInboxUiState(
    val loading: Boolean = false,
    val visibility: InboxVisibility = InboxVisibility.Hidden("not loaded"),
    val messages: List<JistInboxMessage> = emptyList(),
    val unopenedCount: Int = 0
) {
    /** True when the data layer says the inbox is fully renderable. */
    val isVisible: Boolean get() = visibility is InboxVisibility.Visible

    /** Raw templates registry JSON to decode for Jist, when visible. */
    val templatesJson: String? get() = (visibility as? InboxVisibility.Visible)?.templatesJson
}

/**
 * Thin, testable controller around [VisualInbox]. Owns:
 *  - the load cycle (suspend [VisualInbox.loadTemplatesAndBranding]) + snapshot building,
 *  - the auto-mark-opened side effect with an in-flight / dedupe guard.
 *
 * No Compose dependency, so the marking/dedupe logic is unit-testable. The overlay
 * composable drives it from effects and renders the returned [VisualInboxUiState].
 */
internal class VisualInboxController(
    private val visualInbox: VisualInbox,
    // Dispatcher the uiStateFlow upstream (load/snapshot) runs on. Defaults to IO so the
    // retry/backoff + parsing in load() never runs on the main (collector) thread; injectable so
    // unit tests can substitute a TestDispatcher and keep the flow on virtual time.
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // Dedupe guard for auto-mark-opened: queueIds already marked opened in this session, plus a
    // simple in-flight flag so a re-entrant open doesn't re-issue marks before the first finishes.
    private val markedOpenedQueueIds = HashSet<String>()
    private val markInFlight = AtomicBoolean(false)

    // Dedupe guard for dismiss (mark-deleted), mirroring the mark-opened guards above: queueIds
    // already dismissed in this session, plus an in-flight flag so a duplicate action event (e.g. a
    // double-tap before the store re-emits and removes the row) does not issue a second delete.
    private val deletedQueueIds = HashSet<String>()
    private val deleteInFlight = AtomicBoolean(false)

    /**
     * Reactive stream of UI snapshots. Merges two sources, distinguished by whether the emission
     * may FETCH:
     *  - STORE changes ([VisualInbox.observeInboxChanges]) map to [load] (enablement-gated,
     *    fetch-if-missing) — covers the enablement flip when the queue poll returns enabled.
     *  - FETCH-COMPLETION ([VisualInbox.observeContentChanges]) maps to [snapshot] (re-read only,
     *    never fetches), so a fetch completing re-renders without re-fetching (no loop).
     * `onStart` seeds one initial load so a late collector gets the current snapshot immediately;
     * `distinctUntilChanged` collapses no-op emissions.
     *
     * The `map` step runs [load] (which calls [VisualInbox.loadTemplatesAndBranding] — retry/backoff
     * + parsing) and [snapshot]; both are main-safe (suspend + repository reads) but must not run on
     * the collector's context, since the overlay collects via `collectAsState` on the main thread.
     * [flowOn] moves the entire upstream (merge/map/load/snapshot/distinctUntilChanged) onto
     * [loadDispatcher] (IO by default), so only the (cheap) state observation stays on main — no
     * jank/ANR risk. The merge/dedupe semantics are unchanged.
     */
    fun uiStateFlow(): Flow<VisualInboxUiState> {
        // Store changes are allowed to fetch (mayFetch = true); fetch-completion signals only
        // re-read the cache (mayFetch = false) to avoid re-triggering the network.
        val storeChanges: Flow<Boolean> = visualInbox.observeInboxChanges()
            .map { true }
            // Seed an initial fetching emission so a collector that mounts before any store change
            // still gets the current snapshot right away (and then every subsequent change).
            .onStart { emit(true) }
        val fetchCompletions: Flow<Boolean> = visualInbox.observeContentChanges()
            .map { false }
        return merge(storeChanges, fetchCompletions)
            .map { mayFetch -> if (mayFetch) load() else snapshot() }
            .distinctUntilChanged()
            .flowOn(loadDispatcher)
    }

    /**
     * Runs a load cycle and returns the resulting UI snapshot. Reads the enablement gate first
     * (a disabled inbox short-circuits to Hidden without a network fetch), then fetches
     * templates + branding and reads back the terminal visibility + selected messages.
     */
    suspend fun load(): VisualInboxUiState {
        if (!visualInbox.isEnabled) {
            return VisualInboxUiState(
                loading = false,
                visibility = InboxVisibility.Hidden("inbox disabled"),
                messages = emptyList(),
                unopenedCount = 0
            )
        }
        // Fetch (or serve-stale) templates + branding; outcome folds into getVisibility() below.
        visualInbox.loadTemplatesAndBranding()
        return snapshot()
    }

    /** Builds a snapshot from the current data-layer state without triggering a fetch. */
    fun snapshot(): VisualInboxUiState {
        val visibility = visualInbox.getVisibility()
        // Visibility is the single source of truth: only carry messages when the inbox is fully
        // renderable (Visible == enabled + templates + branding + >=1 message). Otherwise the
        // panel could render the list with null templates/theme (Jist renders empty) and
        // markOpenMessagesOpened would no-op, leaving viewed messages unopened.
        val messages = if (visibility is InboxVisibility.Visible) {
            visualInbox.getSelectedMessages()
        } else {
            emptyList()
        }
        return VisualInboxUiState(
            loading = false,
            visibility = visibility,
            messages = messages,
            unopenedCount = unopenedInboxCount(messages)
        )
    }

    /**
     * Mark every currently-selected, still-unopened message as opened, exactly once. Guarded by
     * [markInFlight] (no re-entry while a marking pass runs) and [markedOpenedQueueIds] (a message
     * marked in this session is never re-marked). Marks the data layer's
     * [InboxVisibility.Visible.messages] — the same set the UI renders — so no Jist re-correlation.
     */
    fun markOpenMessagesOpened(visibility: InboxVisibility) {
        val visible = visibility as? InboxVisibility.Visible ?: return
        if (!markInFlight.compareAndSet(false, true)) return
        try {
            visible.messages
                .filter { !it.opened && it.queueId !in markedOpenedQueueIds }
                .forEach { message ->
                    markedOpenedQueueIds.add(message.queueId)
                    visualInbox.markMessageOpened(message)
                }
        } finally {
            markInFlight.set(false)
        }
    }

    /**
     * Dismiss (remove) a single message in response to a Jist `dismiss` action, exactly once.
     * Mirrors [markOpenMessagesOpened]: the [queueId] is resolved against the data layer's
     * [InboxVisibility.Visible.messages] — the same [io.customer.messaginginapp.gist.data.model.InboxMessage]
     * set the UI renders — so there is no Jist re-correlation, and the delete reuses the existing
     * NotificationInbox plumbing via [VisualInbox.markMessageDeleted]. Guarded by [deleteInFlight]
     * (no re-entry while a dismiss runs) and [deletedQueueIds] (a message dismissed in this session
     * is never re-deleted, e.g. on a duplicate action event before the row is removed). Removing the
     * last message empties the list, which the overlay reacts to by auto-closing the panel.
     */
    fun dismissMessage(visibility: InboxVisibility, queueId: String) {
        val visible = visibility as? InboxVisibility.Visible ?: return
        if (queueId in deletedQueueIds) return
        if (!deleteInFlight.compareAndSet(false, true)) return
        try {
            val message = visible.messages.firstOrNull { it.queueId == queueId } ?: return
            deletedQueueIds.add(queueId)
            visualInbox.markMessageDeleted(message)
        } finally {
            deleteInFlight.set(false)
        }
    }
}
