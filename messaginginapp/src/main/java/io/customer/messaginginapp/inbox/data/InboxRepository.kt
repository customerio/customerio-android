package io.customer.messaginginapp.inbox.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Orchestrates the visual-inbox data layer: a thin layer over the headless inbox.
 *
 * Messages: read on demand from the headless source ([InAppMessagingState.inboxMessages])
 * with the cio_inbox [InboxSelection] applied ON READ. There is no bespoke message cache —
 * the headless store keeps the last message set across a failed poll, so serve-stale for
 * messages is the store's existing behavior.
 *
 * Templates/branding: fetched (in parallel on enablement) through the SAME mechanism the
 * headless queue uses — the gist OkHttp HTTP cache + 304 + the [InAppPreferenceStore]
 * network-response store, keyed by URL (workspace/environment scoped). A successful fetch
 * (200 or 304-served) persists the response; a failed fetch leaves the last persisted value
 * in place, which the repository serves stale.
 *
 * Freshness — once-per-session server revalidation (no wall-clock TTL):
 * The first [loadTemplatesAndBranding] of a session performs a conditional GET for any
 * required asset (the gist client sends Cache-Control:no-cache, so the server returns 304
 * "unchanged" — cheap — or 200 "updated"), persists the result, and flips the session
 * revalidation gate ([hasRevalidatedAssetsThisSession]). Every subsequent call in the same
 * session that already has BOTH assets persisted serves them WITHOUT touching the network,
 * so the overlay's per-emission [loadTemplatesAndBranding] does not become chatty. A "session"
 * is the lifetime of this DI singleton: it resets ONLY on process restart. Net cadence: one
 * conditional GET per session, server-decided freshness, no expiry window.
 *
 * Resilience:
 * - Each fetch retries with exponential backoff, bounded by the 5s per-call timeout.
 * - Terminal behavior is the single decision point in [decideOutcome] (interim
 *   hidden-vs-visible policy).
 */
internal class InboxRepository(
    private val api: InboxApi = InboxApi(),
    private val inAppMessagingManager: InAppMessagingManager = SDKComponent.inAppMessagingManager,
    private val preferenceStore: InAppPreferenceStore = SDKComponent.inAppPreferenceStore,
    private val gistQueue: GistQueue = SDKComponent.gistQueue,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val logger: Logger = SDKComponent.logger
) {
    private val gson = Gson()

    /** Full URL key for the persisted templates response (matches the gist interceptor key). */
    private val templatesUrl: String
        get() = "${gistQueue.baseUrl}/api/v1/templates"

    /** Full URL key for the persisted branding response (matches the gist interceptor key). */
    private val brandingUrl: String
        get() = "${gistQueue.baseUrl}/api/v1/branding"

    /**
     * In-flight guard so overlapping queue polls (or a transition + cache-miss
     * firing back-to-back) never launch duplicate concurrent templates/branding
     * fetches. Flipped true when a fetch begins and reset in a finally block.
     */
    private val fetchInFlight = AtomicBoolean(false)

    /**
     * Session-scoped revalidation gate. False until the first [loadTemplatesAndBranding] of
     * the session has performed (or attempted) a conditional GET; true afterward. The repo is
     * a DI singleton, so this resets to false ONLY on process restart (= new session). While
     * true (and both assets persisted) loads serve the persisted value with no network call.
     */
    private val hasRevalidatedAssetsThisSession = AtomicBoolean(false)

    /** True while a [loadTemplatesAndBranding] fetch cycle is currently running. */
    val isFetchInFlight: Boolean
        get() = fetchInFlight.get()

    /**
     * Fetch-completion signal. Emits [Unit] once whenever a [loadTemplatesAndBranding] cycle that
     * actually ran the fetch path FINISHES (any terminal outcome). This is the reactive edge the
     * overlay needs: a fetch triggered by an enablement flip populates the templates/branding
     * cache WITHOUT changing `isInboxEnabled` or `inboxMessages`, so the store-keyed
     * `observeInboxChanges` never re-emits. Observers re-read the now-warm cached visibility and
     * transition Hidden -> Visible. Mirrors iOS's repository `loadStateChanges()` edge.
     *
     * `replay = 0` because it is a pure edge (not state); `extraBufferCapacity` lets the
     * non-suspending `tryEmit` from the fetch coroutine never block or drop.
     */
    private val contentChanges = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 8)

    /**
     * Observe [contentChanges]: fires when a templates/branding fetch cycle completes.
     * Carries no payload — observers re-read cached visibility via [computeVisibility].
     */
    fun observeContentChanges(): Flow<Unit> = contentChanges.asSharedFlow()

    /**
     * Whether a templates/branding fetch is warranted right now: true when either
     * required render input is missing from the HTTP-cache-backed store. Used by the
     * live poll to decide whether to trigger a fetch-if-missing.
     */
    fun needsTemplatesOrBrandingFetch(): Boolean {
        return persistedTemplatesJson() == null || persistedBranding() == null
    }

    val isInboxEnabled: Boolean
        get() = inAppMessagingManager.getCurrentState().isInboxEnabled

    /**
     * Loads templates + branding for the current user, applying retry/backoff, and returns the
     * terminal [InboxFetchOutcome] (a hidden-vs-visible decision; never an error to the UI).
     *
     * Once-per-session revalidation gate (see class doc):
     * - First call of the session ([hasRevalidatedAssetsThisSession] false): perform a
     *   conditional GET (Cache-Control:no-cache) for each required asset, persist the result,
     *   then flip the gate. This revalidates even already-persisted assets (304 = cheap).
     * - Subsequent calls in the same session, with BOTH assets persisted: serve from the
     *   persisted store WITHOUT any network call.
     * - A still-missing asset is always fetched (regardless of the gate).
     *
     * Both templates AND branding are required-to-render: each independently resolves fresh,
     * then stale (last persisted), then (if missing) the inbox is [InboxFetchOutcome.Hidden].
     */
    suspend fun loadTemplatesAndBranding(): InboxFetchOutcome {
        // In-flight guard: if a fetch is already running, do not start a second one.
        // Overlapping polls simply observe the existing cycle and serve current state.
        if (!fetchInFlight.compareAndSet(false, true)) {
            logger.debug("$LOG_TAG fetch skipped: already in-flight (concurrent poll)")
            return currentTemplatesBrandingOutcome()
        }
        try {
            return doLoadTemplatesAndBranding()
        } finally {
            fetchInFlight.set(false)
            // Signal completion after the cache has been populated. Only the path that actually
            // ran the fetch reaches here (the in-flight short-circuit above returns early without
            // emitting). tryEmit is non-suspending and cannot block/deadlock here.
            contentChanges.tryEmit(Unit)
        }
    }

    private suspend fun doLoadTemplatesAndBranding(): InboxFetchOutcome {
        // Last-persisted values up front: serve-stale source if a fetch fails.
        val persistedTemplates = persistedTemplatesJson()
        val persistedBranding = persistedBranding()

        // Once-per-session revalidation gate. Already revalidated this session AND both assets
        // persisted -> serve from cache with no network (keeps the per-emission load cheap).
        val alreadyRevalidated = hasRevalidatedAssetsThisSession.get()
        if (alreadyRevalidated && persistedTemplates != null && persistedBranding != null) {
            logger.debug("$LOG_TAG fetch short-circuit: revalidated this session + both assets present, no network")
            return InboxFetchOutcome.Visible(persistedTemplates, persistedBranding, fromCache = true)
        }

        // Otherwise we hit the network for each required asset. Until the session has been
        // revalidated we issue a conditional GET even for an already-persisted asset (the gist
        // client sends Cache-Control:no-cache -> server returns 304 unchanged / 200 updated);
        // a still-missing asset is always fetched.
        val fetchTemplates = !alreadyRevalidated || persistedTemplates == null
        val fetchBranding = !alreadyRevalidated || persistedBranding == null

        logger.info(
            "$LOG_TAG fetch starting (parallel, revalidate=${!alreadyRevalidated}): " +
                "templates=${describeFetchTarget(fetchTemplates, persistedTemplates == null)}, " +
                "branding=${describeFetchTarget(fetchBranding, persistedBranding == null)}"
        )

        // Fresh inputs: an already-persisted value (when we are not re-fetching it), or a
        // successful network fetch below (the gist interceptor persists the response on
        // success, incl. 304-served bodies).
        var freshTemplates: String? = if (fetchTemplates) null else persistedTemplates
        var freshBranding: Branding? = if (fetchBranding) null else persistedBranding

        coroutineScope {
            // Parallel fetch of every asset that needs the network this call.
            val templatesDeferred = if (fetchTemplates) {
                async {
                    runCatching {
                        retryWithBackoff(retryPolicy, onAttempt = ::logTemplatesAttempt) { api.fetchTemplatesRaw() }
                    }
                }
            } else {
                null
            }
            val brandingDeferred = if (fetchBranding) {
                async {
                    runCatching {
                        retryWithBackoff(retryPolicy, onAttempt = ::logBrandingAttempt) { api.fetchBranding() }
                    }
                }
            } else {
                null
            }

            templatesDeferred?.await()?.let { result ->
                result.onSuccess { json ->
                    freshTemplates = json
                    logger.info("$LOG_TAG templates fetch OK: ${templateNamesCount(json)} template name(s)")
                }.onFailure { e ->
                    logger.error("$LOG_TAG templates fetch FAILED after ${retryPolicy.maxAttempts} attempt(s): ${e.message}")
                }
            }

            brandingDeferred?.await()?.let { result ->
                result.onSuccess { branding ->
                    freshBranding = branding
                    logger.info("$LOG_TAG branding fetch OK: parsed")
                }.onFailure { e ->
                    logger.error("$LOG_TAG branding fetch FAILED after ${retryPolicy.maxAttempts} attempt(s): ${e.message}")
                }
            }
        }

        // The session has now attempted its conditional GET (whether the result was 304, 200,
        // or a failure that fell back to stale). Close the gate so later loads in this session
        // serve persisted values without re-hitting the network until the session resets
        // (process restart).
        hasRevalidatedAssetsThisSession.set(true)

        // Single decision point: only consult last-persisted (stale) values when we have no
        // fresh input to serve, so the explicit serve-stale path is exercised for both inputs.
        val staleTemplates = if (freshTemplates == null) persistedTemplates else null
        val staleBranding = if (freshBranding == null) persistedBranding else null

        if (staleTemplates != null) logger.info("$LOG_TAG serve-stale: using last-persisted TEMPLATES (fetch failed)")
        if (staleBranding != null) logger.info("$LOG_TAG serve-stale: using last-persisted BRANDING (fetch failed)")

        val outcome = decideOutcome(
            freshTemplatesJson = freshTemplates,
            staleTemplatesJson = staleTemplates,
            freshBranding = freshBranding,
            staleBranding = staleBranding
        )
        when (outcome) {
            is InboxFetchOutcome.Visible ->
                logger.info("$LOG_TAG fetch outcome: Visible (fromCache=${outcome.fromCache})")
            is InboxFetchOutcome.Hidden ->
                logger.info("$LOG_TAG fetch outcome: Hidden -> ${outcome.reason}")
        }
        return outcome
    }

    /** Human-readable fetch target for the start-of-fetch log line. */
    private fun describeFetchTarget(willFetch: Boolean, isMissing: Boolean): String = when {
        !willFetch -> "cached"
        isMissing -> "MISS"
        else -> "revalidate"
    }

    private fun logTemplatesAttempt(attemptZeroBased: Int, delayMillis: Long?) {
        val n = attemptZeroBased + 1
        if (delayMillis == null) {
            logger.debug("$LOG_TAG templates attempt $n of ${retryPolicy.maxAttempts}")
        } else {
            logger.debug("$LOG_TAG templates attempt $n of ${retryPolicy.maxAttempts} failed; retrying after ${delayMillis}ms backoff")
        }
    }

    private fun logBrandingAttempt(attemptZeroBased: Int, delayMillis: Long?) {
        val n = attemptZeroBased + 1
        if (delayMillis == null) {
            logger.debug("$LOG_TAG branding attempt $n of ${retryPolicy.maxAttempts}")
        } else {
            logger.debug("$LOG_TAG branding attempt $n of ${retryPolicy.maxAttempts} failed; retrying after ${delayMillis}ms backoff")
        }
    }

    /**
     * Best-effort count of template names in the raw registry JSON for logging only.
     * Tolerant of any shape: returns the top-level key count, or -1 if unparseable.
     */
    private fun templateNamesCount(rawJson: String): Int = runCatching {
        Gson().fromJson(rawJson, JsonObject::class.java).keySet().size
    }.getOrDefault(-1)

    /**
     * Whether the visual inbox should be shown right now. The inbox is VISIBLE iff
     * ALL of the following hold:
     *  - [isInboxEnabled] is true (server-driven gate), AND
     *  - at least one selected message exists (read from the headless store), AND
     *  - templates are available (persisted via the HTTP cache), AND
     *  - branding is available (persisted via the HTTP cache).
     *
     * Any missing/uncached piece => hidden (no error). This folds the enabled +
     * messages inputs in around [decideOutcome]'s templates+branding decision.
     */
    fun computeVisibility(outcome: InboxFetchOutcome = currentTemplatesBrandingOutcome()): InboxVisibility {
        val visibility = computeVisibilityInternal(outcome)
        when (visibility) {
            is InboxVisibility.Visible ->
                logger.info("$LOG_TAG visibility: Visible(${visibility.messages.size} message(s), fromCache=${visibility.fromCache})")
            is InboxVisibility.Hidden ->
                logger.info("$LOG_TAG visibility: Hidden -> ${visibility.reason}")
        }
        return visibility
    }

    private fun computeVisibilityInternal(outcome: InboxFetchOutcome): InboxVisibility {
        if (!isInboxEnabled) {
            return InboxVisibility.Hidden(REASON_INBOX_DISABLED)
        }
        val messages = selectVisualInboxMessages()
        if (messages.isEmpty()) {
            return InboxVisibility.Hidden(REASON_NO_SELECTED_MESSAGES)
        }
        return when (outcome) {
            is InboxFetchOutcome.Visible -> InboxVisibility.Visible(
                templatesJson = outcome.templatesJson,
                branding = outcome.branding,
                messages = messages,
                fromCache = outcome.fromCache
            )

            is InboxFetchOutcome.Hidden -> InboxVisibility.Hidden(outcome.reason)
        }
    }

    /** Convenience: whether the inbox is currently visible (see [computeVisibility]). */
    val isInboxVisible: Boolean
        get() = computeVisibility() is InboxVisibility.Visible

    /**
     * Builds the templates+branding outcome from persisted values WITHOUT fetching.
     * Used by [computeVisibility] so a visibility query never triggers network I/O.
     */
    private fun currentTemplatesBrandingOutcome(): InboxFetchOutcome {
        val templates = persistedTemplatesJson()
        val branding = persistedBranding()
        // Read-only view: everything available here came from the persisted HTTP cache.
        return decideOutcome(
            freshTemplatesJson = null,
            staleTemplatesJson = templates,
            freshBranding = null,
            staleBranding = branding
        )
    }

    /** Raw templates JSON, if persisted (Jist-agnostic). */
    fun cachedTemplatesJson(): String? = persistedTemplatesJson()

    fun cachedBranding(): Branding? = persistedBranding()

    /** Reads the last persisted templates response from the HTTP-cache-backed store. */
    private fun persistedTemplatesJson(): String? = preferenceStore.getNetworkResponse(templatesUrl)

    /** Reads + parses the last persisted branding response from the HTTP-cache-backed store. */
    private fun persistedBranding(): Branding? {
        val raw = preferenceStore.getNetworkResponse(brandingUrl) ?: return null
        return runCatching {
            parseBrandingJson(gson.fromJson(raw, JsonObject::class.java), gson)
        }.getOrNull()
    }

    /**
     * Selects the visual-inbox message list for the current user: cio_inbox prefix
     * filter, expiry drop, priority-asc then sentAt-desc sort.
     *
     * Messages are read straight from the headless store ([InAppMessagingState.inboxMessages]).
     * The headless store retains the last message set across a failed poll, so the
     * serve-stale behavior for messages is the store's existing behavior — no separate
     * message cache is needed here.
     */
    fun selectVisualInboxMessages(): List<InboxMessage> {
        val messages = inAppMessagingManager.getCurrentState().inboxMessages.toList()
        val selected = InboxSelection.select(messages)
        logger.debug(
            "$LOG_TAG selection: ${messages.size} input -> ${selected.size} selected " +
                "(cio_inbox prefix / priority / expiry)"
        )
        return selected
    }

    companion object {
        // Consistent, greppable prefix for every visual-inbox data-layer log line.
        const val LOG_TAG = "[CIO-Inbox]"
    }
}
