package io.customer.messaginginapp.inbox.data

import io.customer.base.internal.InternalCustomerIOApi

/**
 * Terminal result of an inbox template/branding fetch cycle, as a VISIBILITY
 * decision: the inbox shows ONLY when fully renderable; "no data" is [Hidden],
 * never an error.
 *
 * This sealed type + [decideOutcome] are the single decision point for terminal
 * fetch behavior, so future policy can change here without touching callers. The
 * inbox is [Visible] iff BOTH render inputs resolve, each via: fresh fetch/cache-hit,
 * else serve stale (last-persisted), else missing -> [Hidden]. Branding is
 * REQUIRED-to-render and behaves exactly like templates (no defaults). Full
 * visibility ALSO requires `isInboxEnabled`, folded in by
 * [InboxRepository.computeVisibility]; [decideOutcome] decides only templates+branding.
 * The message count does not gate visibility — an empty inbox is visible and empty.
 */
@InternalCustomerIOApi
sealed class InboxFetchOutcome {
    /**
     * Render inputs resolved: templates AND branding are both available (each
     * fresh or served stale). The inbox can be shown (subject to the enabled
     * check folded in by the repository's visibility computation).
     *
     * @param templatesJson raw, Jist-agnostic template registry JSON
     * @param branding branding tokens + patterns (required-to-render; never null here)
     */
    @InternalCustomerIOApi
    data class Visible(
        val templatesJson: String,
        val branding: Branding
    ) : InboxFetchOutcome()

    /**
     * A render input is missing and uncached, so there is nothing to show. This is
     * surfaced to the UI as a HIDDEN inbox (the inbox simply does not appear) —
     * NOT as an error state.
     */
    @InternalCustomerIOApi
    data class Hidden(
        val reason: String
    ) : InboxFetchOutcome()
}

// Diagnostic "hidden reason" strings. These are kept BYTE-FOR-BYTE identical to the
// iOS SDK so both platforms emit the same visual-inbox diagnostic reasons. They are
// logging-only and never surfaced to users; do not change without aligning iOS.
internal const val REASON_INBOX_DISABLED = "inbox disabled"
internal const val REASON_TEMPLATES_UNAVAILABLE = "templates unavailable"
internal const val REASON_BRANDING_UNAVAILABLE = "branding unavailable"

/**
 * The interim terminal decision for the templates+branding half of visibility
 * (single decision point; see the type doc above).
 *
 * Both templates AND branding are required-to-render: each independently resolves
 * to fresh, then stale, then missing. If either is missing the inbox is [Hidden].
 *
 * @param freshTemplatesJson templates from a successful fresh fetch / fresh cache hit, else null
 * @param staleTemplatesJson expired-but-present cached templates, or null if none
 * @param freshBranding branding from a successful fresh fetch / fresh cache hit, else null
 * @param staleBranding expired-but-present cached branding, or null if none
 */
internal fun decideOutcome(
    freshTemplatesJson: String?,
    staleTemplatesJson: String?,
    freshBranding: Branding?,
    staleBranding: Branding?
): InboxFetchOutcome {
    // Interim hidden-vs-visible policy; single decision point. Do NOT add policy logic
    // at call sites — extend or change this function instead.

    // Templates: fresh wins, else serve stale, else missing.
    val templates = freshTemplatesJson ?: staleTemplatesJson
    // Branding: fresh wins, else serve stale, else missing (required-to-render).
    val branding = freshBranding ?: staleBranding

    return when {
        templates == null || branding == null -> {
            // Diagnostic reason mirrors iOS EXACTLY: emit the applicable missing-bucket
            // reasons joined with ", " in order (templates, then branding). Both halves
            // are computed together here, so when both are missing they combine into
            // "templates unavailable, branding unavailable".
            val reasons = buildList {
                if (templates == null) add(REASON_TEMPLATES_UNAVAILABLE)
                if (branding == null) add(REASON_BRANDING_UNAVAILABLE)
            }
            InboxFetchOutcome.Hidden(reasons.joinToString(", "))
        }

        else -> InboxFetchOutcome.Visible(
            templatesJson = templates,
            branding = branding
        )
    }
}
