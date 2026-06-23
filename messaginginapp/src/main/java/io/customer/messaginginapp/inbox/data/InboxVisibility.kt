package io.customer.messaginginapp.inbox.data

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.gist.data.model.InboxMessage

/**
 * The single top-level visibility signal for the visual notification inbox.
 *
 * The inbox shows ONLY when it is fully renderable. There is NO error state exposed
 * to the UI: a "no data / not renderable" situation is simply [Hidden].
 *
 * [Visible] iff ALL of:
 *  - `isInboxEnabled` (server-driven gate), AND
 *  - at least one selected message (fresh OR stale cache), AND
 *  - templates available (fresh OR stale cache), AND
 *  - branding available (fresh OR stale cache; branding is required-to-render).
 *
 * If any piece is missing/uncached, the inbox is [Hidden]. See
 * [InboxRepository.computeVisibility] and [decideOutcome] for how the inputs combine.
 */
@InternalCustomerIOApi
sealed class InboxVisibility {
    /**
     * The inbox is renderable and should be shown.
     *
     * @param templatesJson raw, Jist-agnostic template registry JSON
     * @param branding branding tokens + patterns (required-to-render; never null)
     * @param messages the selected/sorted message list to render
     * @param fromCache true when any served render input came from cache (fresh-cache or stale)
     */
    @InternalCustomerIOApi
    data class Visible(
        val templatesJson: String,
        val branding: Branding,
        val messages: List<InboxMessage>,
        val fromCache: Boolean
    ) : InboxVisibility()

    /**
     * The inbox is not renderable, so it is hidden (it simply does not appear).
     * [reason] is for logging/diagnostics only; it is NOT an error surfaced to users.
     */
    @InternalCustomerIOApi
    data class Hidden(
        val reason: String
    ) : InboxVisibility()
}
