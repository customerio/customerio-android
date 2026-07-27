package io.customer.messaginginbox

import io.customer.jist.JistActionEvent
import io.customer.messaginginapp.inbox.VisualInbox
import io.customer.messaginginapp.inbox.data.InboxVisibility
import io.customer.messaginginapp.inbox.jist.JistInboxAdapter
import io.customer.messaginginapp.inbox.jist.JistInboxMessage
import io.customer.messaginginapp.type.InboxEventListener
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
    // Provider for the host-registered listener (item 13) notified when a non-dismiss action is
    // taken. When it returns true the host handled the action and the SDK skips its default
    // navigation. Invoked on each callback (not snapshotted) so a listener registered at runtime via
    // ModuleMessagingInApp.setInboxEventListener takes effect; returns null when none is registered.
    private val inboxEventListenerProvider: () -> InboxEventListener? = { null },
    // Logger for action diagnostics. Defaults to the SDK logger; injectable so unit tests can pass a
    // relaxed mock (the real LogcatLogger calls android.util.Log, which is not mocked on the JVM).
    private val logger: Logger = SDKComponent.logger,
    // Dispatcher the uiStateFlow upstream (load/snapshot) runs on. Defaults to IO so the
    // retry/backoff + parsing in load() never runs on the main (collector) thread; injectable so
    // unit tests can substitute a TestDispatcher and keep the flow on virtual time.
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Scope the shared ui-state flow is kept alive on. Defaults to the SDK's in-app lifecycle scope;
    // injectable so unit tests can supply a TestScope.
    private val sharingScope: CoroutineScope = SDKComponent.scopeProvider.inAppLifecycleScope
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
    fun uiStateFlow(): Flow<VisualInboxUiState> = sharedUiState

    /**
     * Shared so every consumer (bell, panel, a host-embedded view) observes ONE upstream. Collecting
     * a cold flow per consumer would re-run the whole load/snapshot pipeline — including the
     * templates/branding reads and the Jist mapping — once per collector on every store change.
     *
     * `WhileSubscribed` keeps the upstream running only while something is mounted; the replay cache
     * hands a late collector (e.g. the panel opening) the current snapshot immediately.
     */
    private val sharedUiState: SharedFlow<VisualInboxUiState> by lazy {
        buildUiStateFlow().shareIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
            replay = 1
        )
    }

    private fun buildUiStateFlow(): Flow<VisualInboxUiState> {
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
        //
        // Reuse the list the visibility decision was made from rather than re-selecting: a second
        // read would repeat the selection and the Jist deep-copy, and could observe a store change
        // in between and disagree with the state published alongside it.
        val messages = (visibility as? InboxVisibility.Visible)
            ?.let { JistInboxAdapter.toJist(it.messages) }
            .orEmpty()
        // The per-session dedupe guards (shown/opened/clicked/deleted queueIds) are intentionally NOT
        // reconciled against the live list: the data-layer tombstone (deletedInboxMessageIds) prevents
        // a dismissed message from resurrecting and queueIds are never reused, so a guard never needs
        // releasing within a session. Reconciling here only re-introduced edge cases (guards wiped on
        // a transient non-Visible state, or stuck after the last row is dismissed → Hidden).
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
                    // markMessageOpened dispatches UpdateOpened, whose middleware reports the opened
                    // metric via the generic `Report Delivery Event` (metric: opened) — matching web
                    // (rendered as "Opened Inbox Message" for an inbox delivery). No named CDP event.
                    visualInbox.markMessageOpened(message)
                    // Hand the host the post-action state: the resolved message predates the mark.
                    notifyListener { messageOpened(message.copy(opened = true)) }
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
            notifyListener { messageDismissed(message) }
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
     *  3. Otherwise the SDK applies its default navigation (item 12): an `openUrl` value opens
     *     externally ([InboxNavigation.OpenUrl]); an `openDeeplink`
     *     value is routed through the app's deep-link handling ([InboxNavigation.OpenDeeplink],
     *     which the overlay resolves against the host app first, mirroring push/in-app deep links);
     *     a `performAction` is host-only (no navigation); a missing/malformed value is a logged no-op.
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
        // Host-facing action name (web `data.name`, else the Jist event name) + resolved value.
        val name = actionName(event)
        val url = resolution.url
        // Track the click against the same InboxMessage the UI renders (resolved from the visible
        // set), reusing the existing track-clicked plumbing. Deduped per queueId so a repeated tap
        // before the row updates does not double-count.
        trackClicked(visibility, message, name, url)

        // Host interception (item 13): true => host handled it, SDK runs no default nav (but still
        // honors the dismiss flag below).
        val handledByHost = notifyHostHandled(visibility, message, name, url.orEmpty())

        // SDK default navigation (item 12), unless the host handled it.
        val navigation = if (handledByHost) {
            // The host performed the action. If it was a navigating action (openUrl / openDeeplink),
            // still dismiss the presenting sheet so the SDK inbox doesn't linger over the host's
            // destination (mirrors iOS). A non-navigating action (performAction / unknown) leaves the
            // inbox open.
            when (resolution) {
                is InboxAction.OpenUrl, is InboxAction.Deeplink -> InboxNavigation.Dismiss
                else -> InboxNavigation.None
            }
        } else {
            when (resolution) {
                is InboxAction.OpenUrl -> InboxNavigation.OpenUrl(resolution.url)
                // openDeeplink: route through the app's deep-link handling (the overlay resolves the
                // host app first, then external), mirroring push/in-app deep links.
                is InboxAction.Deeplink -> InboxNavigation.OpenDeeplink(resolution.url)

                // performAction is host-only: if no host listener handled it there is nothing for
                // the SDK to navigate to.
                is InboxAction.PerformAction -> {
                    logger.debug(
                        "$INBOX_LOG_TAG performAction '$name' on ${message.queueId} not handled by host; no navigation"
                    )
                    InboxNavigation.None
                }

                is InboxAction.Unknown -> {
                    logger.debug(
                        "$INBOX_LOG_TAG action '$name' (behavior=${actionBehavior(event)}, value=${actionValue(event)}) " +
                            "on ${message.queueId}: no resolvable value/behavior, no-op"
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
    fun notifyMessageShown(visibility: InboxVisibility, message: JistInboxMessage) {
        // Resolve the canonical InboxMessage (the same type NotificationInbox.getMessages() returns)
        // from the visible set so the host receives the full message, not the internal render type.
        val inboxMessage = (visibility as? InboxVisibility.Visible)
            ?.messages?.firstOrNull { it.queueId == message.queueId } ?: return
        if (!shownQueueIds.add(message.queueId)) return
        // No "delivered" CDP event is emitted from the client — web doesn't send one; the backend
        // synthesizes `Delivered Inbox Message` when the message is delivered to the inbox.
        notifyListener { messageShown(inboxMessage) }
    }

    /**
     * Track a click for [message], once per queueId, via the existing generic delivery metric
     * ([VisualInbox.trackMessageClicked]) — carrying both [actionName] and [actionValue] so the
     * `Report Delivery Event` (metric: clicked) matches web (MBL-2125). The CDP backend renders it
     * as "Clicked Inbox Message" for an inbox delivery; no separate named event is emitted.
     */
    private fun trackClicked(
        visibility: InboxVisibility,
        message: JistInboxMessage,
        actionName: String?,
        actionValue: String?
    ) {
        val visible = visibility as? InboxVisibility.Visible ?: return
        val tracked = visible.messages.firstOrNull { it.queueId == message.queueId } ?: return
        // Dedupe per DISTINCT action (queueId + action name + value), NOT per message: a single
        // message can carry multiple CTAs and web reports a click for each, so two different actions
        // on the same message must each report. Only a repeat of the SAME action (e.g. a rapid
        // double-tap of one CTA) is suppressed here; the backend deduplicates the delivery's
        // first-click metric regardless. Reserve only AFTER confirming the message exists, so a
        // failed lookup never permanently blocks later clicks.
        if (!clickedActions.add(Triple(message.queueId, actionName, actionValue))) return
        visualInbox.trackMessageClicked(tracked, actionName, actionValue)
    }

    /** Invoke the host listener (if any), returning true if the host handled the action. */
    private fun notifyHostHandled(
        visibility: InboxVisibility,
        message: JistInboxMessage,
        actionName: String,
        actionValue: String
    ): Boolean {
        val listener = inboxEventListenerProvider() ?: return false
        // Hand the host the canonical InboxMessage (the same type NotificationInbox.getMessages()
        // returns), resolved from the visible set; a missing message means nothing to intercept.
        val inboxMessage = (visibility as? InboxVisibility.Visible)
            ?.messages?.firstOrNull { it.queueId == message.queueId } ?: return false
        return try {
            listener.messageActionTaken(
                message = inboxMessage,
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
     * Resolved from the same [inboxEventListenerProvider] (the module's current inbox listener) as
     * [messageActionTaken]; a null listener is a no-op.
     */
    private inline fun notifyListener(block: InboxEventListener.() -> Unit) {
        val listener = inboxEventListenerProvider() ?: return
        try {
            listener.block()
        } catch (ex: Exception) {
            logger.error("$INBOX_LOG_TAG inbox event listener threw: ${ex.message}")
        }
    }

    private companion object {
        const val INBOX_LOG_TAG = "[CIO-Inbox]"
    }

    // Dedupe guard for click tracking, keyed by DISTINCT action (queueId + action name + value) so a
    // message with multiple CTAs reports a click for each (web parity); only a repeat of the same
    // action is suppressed.
    private val clickedActions = HashSet<Triple<String, String?, String?>>()

    // Dedupe guard for the observational messageShown callback: notified once per queueId.
    private val shownQueueIds = HashSet<String>()
}

/**
 * The SDK's resolved interpretation of a Jist inbox action, derived from the action's
 * `data.behavior` / `data.action` shape (web parity — see gist-web `handleInboxAction` /
 * `InboxActionConfig`). The live inbox emits dismiss as `data.behavior == "dismiss"`; other actions
 * carry a `data.action` value and one of the `openUrl` / `openDeeplink` / `performAction` behaviors.
 * See [resolveInboxAction].
 */
internal sealed interface InboxAction {
    /** The resolved action value (a url / deeplink), when present. */
    val url: String?

    /** Remove (delete) the message. Web parity for a `dismiss` action. */
    object Dismiss : InboxAction {
        override val url: String? get() = null
    }

    /** Open [url] externally, as the platform resolves it (`openUrl`). */
    data class OpenUrl(override val url: String) : InboxAction

    /** Route [url] through the app's deep-link handling (`openDeeplink`, or a non-http scheme value). */
    data class Deeplink(override val url: String) : InboxAction

    /**
     * A host-only action (`performAction`): the SDK performs no navigation, but the action [url]
     * (value) is still handed to the host listener so it can perform the custom action.
     */
    data class PerformAction(override val url: String?) : InboxAction

    /** No resolvable value/behavior (e.g. missing/malformed) — a no-op. */
    data class Unknown(override val url: String?) : InboxAction
}

/** Instruction the overlay (which owns an Android Context) executes after [VisualInboxController.handleAction]. */
internal sealed interface InboxNavigation {
    /** Nothing for the overlay to do (a non-navigating performAction / unknown; the inbox stays open). */
    object None : InboxNavigation

    /** Close the presenting sheet without the SDK navigating — the host already handled a nav action. */
    object Dismiss : InboxNavigation

    /** Open [url] externally in the system browser (ACTION_VIEW), then close the presenting sheet. */
    data class OpenUrl(val url: String) : InboxNavigation

    /** Route [url] through the app's deep-link handling (host app first, then external), then close the sheet. */
    data class OpenDeeplink(val url: String) : InboxNavigation
}

/**
 * Maps a Jist action event to the SDK's [InboxAction], matching the real gist-web `InboxActionConfig`
 * shape (and the iOS overlay's `resolve`). STRICT `data.behavior` switch — no http/scheme guessing
 * and no synthetic behaviors. Value is `data.action` (legacy `data.url` accepted as a fallback):
 *  - dismiss: `data.behavior == "dismiss"`, or the Jist-demo sentinels `name == "dismiss"` /
 *    value `== "#dismiss"`.
 *  - openUrl: `data.behavior == "openUrl"` → open the value externally in the browser. (Web's
 *    `newTab` is a separate BOOLEAN flag on an openUrl action, not a behavior; on mobile there is no
 *    "new tab", so we ignore it — the openUrl already opens externally.)
 *  - openDeeplink: `data.behavior == "openDeeplink"` → route through the app's deep-link handling.
 *  - performAction: `data.behavior == "performAction"` — host-only; the SDK performs no navigation.
 *  - unknown: absent/unrecognized behavior (incl. the legacy demo `deeplink`, or a missing value) —
 *    host-only no-op. The host listener still receives the action value. Robust to nulls, never throws.
 */
internal fun resolveInboxAction(event: JistActionEvent): InboxAction {
    val behavior = actionBehavior(event)?.lowercase()
    val rawValue = actionValue(event)
    val url = rawValue?.takeIf { it.isNotBlank() }

    val isDismiss = behavior == DISMISS_BEHAVIOR ||
        event.name == DISMISS_ACTION_NAME ||
        rawValue == DISMISS_URL
    if (isDismiss) return InboxAction.Dismiss

    return when (behavior) {
        OPEN_URL_BEHAVIOR -> if (url != null) InboxAction.OpenUrl(url) else InboxAction.Unknown(null)
        OPEN_DEEPLINK_BEHAVIOR -> if (url != null) InboxAction.Deeplink(url) else InboxAction.Unknown(null)
        PERFORM_ACTION_BEHAVIOR -> InboxAction.PerformAction(url)
        // Absent/unrecognized behavior (e.g. the legacy demo `deeplink`, or the web-only `newTab`
        // which is a boolean flag rather than a behavior) is a host-only no-op — the SDK never
        // guesses a browser/deeplink route from the value shape. The host still gets the value.
        else -> InboxAction.Unknown(url)
    }
}

/**
 * Reads a STRING field from a JSON object — only when the primitive is actually a string.
 * `contentOrNull` also returns the text of numeric / boolean primitives (e.g. `true` -> "true",
 * `5` -> "5"), which the web parser rejects, so we gate on [JsonPrimitive.isString] to keep the
 * native contract aligned (a non-string `action` / `behavior` / `name` yields null).
 */
private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Extracts the action value from a Jist action event's data object — the web-schema `data.action`,
 * falling back to the legacy `data.url`. String-only (see [stringField]); a non-object `data`,
 * missing key, or non-string value yields null.
 */
private fun actionValue(event: JistActionEvent): String? {
    val data = event.data as? JsonObject ?: return null
    return data.stringField("action") ?: data.stringField("url")
}

/**
 * Extracts the `behavior` string from a Jist action event's data object (e.g. the live inbox's
 * `messageAction = { behavior: "dismiss" }`). String-only; absent / non-string yields null.
 */
private fun actionBehavior(event: JistActionEvent): String? =
    (event.data as? JsonObject)?.stringField("behavior")

/**
 * The host-facing action name: the web-schema `data.name` (string-only), falling back to the Jist
 * event name. Handed to the host [InboxEventListener.messageActionTaken] and the clicked metric.
 */
private fun actionName(event: JistActionEvent): String =
    (event.data as? JsonObject)?.stringField("name") ?: event.name

/**
 * True when the action carries `data.dismiss == true` — "auto dismiss on click": remove the message
 * after running its (non-dismiss) action. Accepts the real JSON boolean `true` (the web shape) as
 * well as the legacy string `"true"`.
 */
private fun actionDismissFlag(event: JistActionEvent): Boolean {
    val dismiss = (event.data as? JsonObject)?.get("dismiss") as? JsonPrimitive ?: return false
    return dismiss.booleanOrNull == true || (dismiss.isString && dismiss.content == "true")
}

/** Jist action `behavior` / sentinel constants matched by [resolveInboxAction] (compared lowercase). */
private const val DISMISS_BEHAVIOR = "dismiss"
private const val DISMISS_ACTION_NAME = "dismiss"
private const val DISMISS_URL = "#dismiss"
private const val OPEN_URL_BEHAVIOR = "openurl"
private const val OPEN_DEEPLINK_BEHAVIOR = "opendeeplink"
private const val PERFORM_ACTION_BEHAVIOR = "performaction"
