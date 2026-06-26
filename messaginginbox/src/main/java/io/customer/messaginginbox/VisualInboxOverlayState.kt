package io.customer.messaginginbox

import io.customer.jist.JistActionEvent
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.inbox.VisualInbox
import io.customer.messaginginapp.inbox.data.InboxVisibility
import io.customer.messaginginapp.inbox.jist.JistInboxMessage
import io.customer.messaginginapp.type.InboxActionMessage
import io.customer.messaginginapp.type.InboxEventListener
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    // Host-registered listener (item 13) notified when a non-dismiss action is taken. When it
    // returns true the host handled the action and the SDK skips its default navigation. Resolved
    // from the in-app module config at construction; null when the host registered none.
    private val inboxEventListener: InboxEventListener? = null,
    // Logger for action diagnostics. Defaults to the SDK logger; injectable so unit tests can pass a
    // relaxed mock (the real LogcatLogger calls android.util.Log, which is not mocked on the JVM).
    private val logger: Logger = SDKComponent.logger,
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
        reconcileDedupeGuards(messages)
        return VisualInboxUiState(
            loading = false,
            visibility = visibility,
            messages = messages,
            unopenedCount = unopenedInboxCount(messages)
        )
    }

    /**
     * Release per-session dedupe guards for queueIds that are no longer present in the data layer's
     * current message list. The data layer now suppresses a dismissed message from resurrecting
     * (client-side tombstone in InAppMessagingState.deletedInboxMessageIds), and prunes that
     * tombstone once the server's list no longer echoes it. After that prune the server may
     * legitimately re-deliver the same queueId; without releasing these guards that row would stay
     * permanently un-dismissable / un-clickable / never re-marked-opened. Reconciling against the
     * live set keeps guards only for messages still on screen and frees the rest.
     */
    private fun reconcileDedupeGuards(messages: List<JistInboxMessage>) {
        val present = messages.mapTo(HashSet()) { it.queueId }
        deletedQueueIds.retainAll(present)
        clickedQueueIds.retainAll(present)
        markedOpenedQueueIds.retainAll(present)
        shownQueueIds.retainAll(present)
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
                    // Observational host callback (item 14): a message was marked opened.
                    notifyListener { messageOpened(message.toActionMessage()) }
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
            // Observational host callback (item 14): a message was dismissed/removed.
            notifyListener { messageDismissed(message.toActionMessage()) }
        } finally {
            deleteInFlight.set(false)
        }
    }

    /**
     * Handle a Jist action taken on an inbox message. Web/iOS parity flow:
     *  1. A dismiss action (item already shipped) removes the message and returns [InboxNavigation.None].
     *  2. Any other action is a "click": we track a clicked metric (reusing the existing
     *     [VisualInbox.trackMessageClicked] plumbing — no new network path) exactly once per
     *     queueId, then give the host listener (item 13) a chance to intercept. If the host returns
     *     true it fully handled the action and we return [InboxNavigation.None].
     *  3. Otherwise the SDK applies its default navigation (item 12): an http(s) / openUrl / newTab
     *     url opens in the system browser ([InboxNavigation.OpenUrl]); a deeplink is handed back to
     *     the host ([InboxNavigation.None] after logging — the SDK cannot resolve app routes); a
     *     missing/malformed url is a logged no-op.
     *
     * Returns a [InboxNavigation] the (Context-bearing) overlay executes, keeping this controller
     * free of Android Intent / Context dependencies so it stays unit-testable.
     */
    fun handleAction(
        visibility: InboxVisibility,
        message: JistInboxMessage,
        event: JistActionEvent
    ): InboxNavigation {
        val resolution = resolveInboxAction(event)
        if (resolution is InboxAction.Dismiss) {
            dismissMessage(visibility, message.queueId)
            return InboxNavigation.None
        }

        // Auto-dismiss-on-click: an action message can carry `data.dismiss == true` alongside its
        // behavior (e.g. performAction), meaning "run the action AND remove the message". Captured
        // before any early-return so it applies whether or not the host intercepts the action.
        val dismissAfterAction = actionDismissFlag(event)
        val url = resolution.url
        // Track the click against the same InboxMessage the UI renders (resolved from the visible
        // set), reusing the existing track-clicked plumbing. Deduped per queueId so a repeated tap
        // before the row updates does not double-count.
        trackClicked(visibility, message, event.name)

        // Host interception (item 13): true => host handled it, SDK runs no default nav (but still
        // honors the dismiss flag below).
        val handledByHost = notifyHostHandled(message, event.name, url.orEmpty())

        // SDK default navigation (item 12), unless the host handled it.
        val navigation = if (handledByHost) {
            InboxNavigation.None
        } else {
            when (resolution) {
                is InboxAction.OpenUrl -> InboxNavigation.OpenUrl(resolution.url)
                is InboxAction.Deeplink -> {
                    logger.debug(
                        "$INBOX_LOG_TAG deeplink '${resolution.url}' on ${message.queueId} not handled by host; " +
                            "SDK cannot resolve app routes, no-op"
                    )
                    InboxNavigation.None
                }

                is InboxAction.Unknown -> {
                    logger.debug(
                        "$INBOX_LOG_TAG action '${event.name}' (behavior=${actionBehavior(event)}, url=${actionUrl(event)}) " +
                            "on ${message.queueId}: no resolvable url/behavior, no-op"
                    )
                    InboxNavigation.None
                }

                is InboxAction.Dismiss -> InboxNavigation.None // unreachable; handled above
            }
        }

        // Honor `data.dismiss == true` after running the action (regardless of host handling / nav).
        if (dismissAfterAction) {
            dismissMessage(visibility, message.queueId)
        }
        return navigation
    }

    /**
     * Observational host callback (item 14): notify that [message] was first shown/rendered in the
     * inbox view. Deduped per queueId so the host is notified exactly once per message for the life
     * of this controller (the view may recompose/re-render the same row many times). Safe to call
     * from the renderer on every render.
     */
    fun notifyMessageShown(message: JistInboxMessage) {
        if (!shownQueueIds.add(message.queueId)) return
        notifyListener {
            messageShown(InboxActionMessage(messageId = message.queueId, deliveryId = message.deliveryId))
        }
    }

    /** Track a clicked metric for [message], once per queueId. Reuses [VisualInbox.trackMessageClicked]. */
    private fun trackClicked(visibility: InboxVisibility, message: JistInboxMessage, actionName: String?) {
        val visible = visibility as? InboxVisibility.Visible ?: return
        val tracked = visible.messages.firstOrNull { it.queueId == message.queueId } ?: return
        // Reserve only AFTER confirming the message exists, so a failed lookup never permanently
        // dedupes (blocks) the click metric for later taps on the same message.
        if (!clickedQueueIds.add(message.queueId)) return
        visualInbox.trackMessageClicked(tracked, actionName)
    }

    /** Invoke the host listener (if any), returning true if the host handled the action. */
    private fun notifyHostHandled(message: JistInboxMessage, actionName: String, actionValue: String): Boolean {
        val listener = inboxEventListener ?: return false
        return try {
            listener.messageActionTaken(
                message = InboxActionMessage(messageId = message.queueId, deliveryId = message.deliveryId),
                actionName = actionName,
                actionValue = actionValue
            )
        } catch (ex: Exception) {
            // A throwing host listener must not break the SDK; log and fall back to default nav.
            logger.error("$INBOX_LOG_TAG inbox event listener threw: ${ex.message}")
            false
        }
    }

    /**
     * Invoke an observational callback (shown / opened / dismissed) on the host listener (if any).
     * A throwing host listener must never break the SDK, so any exception is caught and logged.
     * Resolved from the same [inboxEventListener] (MessagingInAppModuleConfig.inboxEventListener) as
     * [messageActionTaken]; a null listener is a no-op.
     */
    private inline fun notifyListener(block: InboxEventListener.() -> Unit) {
        val listener = inboxEventListener ?: return
        try {
            listener.block()
        } catch (ex: Exception) {
            logger.error("$INBOX_LOG_TAG inbox event listener threw: ${ex.message}")
        }
    }

    private companion object {
        const val INBOX_LOG_TAG = "[CIO-Inbox]"
    }

    // Dedupe guard for click tracking: a message clicked in this session is tracked once.
    private val clickedQueueIds = HashSet<String>()

    // Dedupe guard for the observational messageShown callback: notified once per queueId.
    private val shownQueueIds = HashSet<String>()
}

/** Build the public [InboxActionMessage] identity handed to [InboxEventListener] from an [InboxMessage]. */
private fun InboxMessage.toActionMessage(): InboxActionMessage =
    InboxActionMessage(messageId = queueId, deliveryId = deliveryId)

/**
 * The SDK's resolved interpretation of a Jist inbox action, derived from the action's
 * `name` / `data.behavior` / `data.url` shape. The live inbox emits the dismiss action as
 * `name = "messageAction"` with `data.behavior == "dismiss"`; other actions carry a `data.url`
 * (and/or an `openUrl` / `newTab` / `deeplink` behavior). See [resolveInboxAction].
 */
internal sealed interface InboxAction {
    /** The resolved url for the action, when present. */
    val url: String?

    /** Remove (delete) the message. Web parity for a dismiss action. */
    object Dismiss : InboxAction {
        override val url: String? get() = null
    }

    /** Open [url] in the system browser (http(s) / openUrl / newTab). */
    data class OpenUrl(override val url: String) : InboxAction

    /** A deeplink the host must resolve (the SDK cannot resolve app routes). */
    data class Deeplink(override val url: String) : InboxAction

    /** No resolvable url/behavior (e.g. missing/malformed url) — a no-op. */
    data class Unknown(override val url: String?) : InboxAction
}

/** Instruction the overlay (which owns an Android Context) executes after [VisualInboxController.handleAction]. */
internal sealed interface InboxNavigation {
    /** Nothing for the overlay to do (dismiss, host-handled, deeplink, or no-op already handled). */
    object None : InboxNavigation

    /** Open [url] in the system browser via an ACTION_VIEW intent. */
    data class OpenUrl(val url: String) : InboxNavigation
}

/**
 * Maps a Jist action event to the SDK's [InboxAction]. Determined from the action `name`,
 * `data.behavior` and `data.url`:
 *  - dismiss: `data.behavior == "dismiss"`, or the Jist-demo sentinels `name == "dismiss"` /
 *    `data.url == "#dismiss"`.
 *  - openUrl: `data.behavior` is `openUrl` / `newTab`, OR (absent a behavior) `data.url` is an
 *    http(s) url.
 *  - deeplink: `data.behavior == "deeplink"`, OR (absent a behavior) `data.url` is a non-http(s)
 *    scheme url (e.g. `myapp://...`).
 *  - unknown: no resolvable url/behavior (missing/malformed) — robust to nulls, never throws.
 */
internal fun resolveInboxAction(event: JistActionEvent): InboxAction {
    val behavior = actionBehavior(event)?.lowercase()
    val url = actionUrl(event)?.takeIf { it.isNotBlank() }

    val isDismiss = behavior == DISMISS_BEHAVIOR ||
        event.name == DISMISS_ACTION_NAME ||
        actionUrl(event) == DISMISS_URL
    if (isDismiss) return InboxAction.Dismiss

    return when (behavior) {
        OPEN_URL_BEHAVIOR, NEW_TAB_BEHAVIOR -> if (url != null) InboxAction.OpenUrl(url) else InboxAction.Unknown(null)
        DEEPLINK_BEHAVIOR -> if (url != null) InboxAction.Deeplink(url) else InboxAction.Unknown(null)
        else -> when {
            url == null -> InboxAction.Unknown(null)
            isHttpUrl(url) -> InboxAction.OpenUrl(url)
            else -> InboxAction.Deeplink(url)
        }
    }
}

/** True for `http://` / `https://` urls (case-insensitive). These open in the system browser. */
private fun isHttpUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/**
 * Extracts the `url` string from a Jist action event's data object, or null if absent / not a
 * string. Safe casts throughout: a non-object `data` or non-primitive `url` yields null.
 */
private fun actionUrl(event: JistActionEvent): String? =
    ((event.data as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull

/**
 * Extracts the `behavior` string from a Jist action event's data object (e.g. the live inbox's
 * `messageAction = { behavior: "dismiss" }`), or null if absent / not a string. Safe casts.
 */
private fun actionBehavior(event: JistActionEvent): String? =
    ((event.data as? JsonObject)?.get("behavior") as? JsonPrimitive)?.contentOrNull

/**
 * True when the action carries `data.dismiss == true` — "auto dismiss on click": remove the message
 * after running its (non-dismiss) action. Matches both a JSON boolean `true` and the string `"true"`.
 */
private fun actionDismissFlag(event: JistActionEvent): Boolean =
    ((event.data as? JsonObject)?.get("dismiss") as? JsonPrimitive)?.contentOrNull == "true"

/** Jist action `behavior` / sentinel constants matched by [resolveInboxAction] (compared lowercase). */
private const val DISMISS_BEHAVIOR = "dismiss"
private const val DISMISS_ACTION_NAME = "dismiss"
private const val DISMISS_URL = "#dismiss"
private const val OPEN_URL_BEHAVIOR = "openurl"
private const val NEW_TAB_BEHAVIOR = "newtab"
private const val DEEPLINK_BEHAVIOR = "deeplink"
