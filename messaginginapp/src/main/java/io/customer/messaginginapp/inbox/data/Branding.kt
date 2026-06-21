package io.customer.messaginginapp.inbox.data

import io.customer.base.internal.InternalCustomerIOApi

/**
 * Branding model returned by GET /api/v1/branding (gist-layer transport model;
 * the inbox/Jist layer interprets these tokens into Jist theme types).
 *
 * The raw [theme] map is kept permissive so new server-side tokens/keys flow
 * through without an SDK change and nested objects/arrays/numbers/bools are
 * preserved (not flattened to strings); on top of that we surface a
 * strongly-typed [InboxChrome] parsed from `patterns.inbox`.
 *
 * IMPORTANT: `patterns.modes.dark` is OPTIONAL — entirely absent in some
 * workspaces; in that case [Patterns.modes] is null and callers must never
 * assume dark-mode overrides exist.
 */
@InternalCustomerIOApi
data class Branding(
    /** Flat or nested theme design tokens (colors, spacing, typography, etc.). */
    val theme: Map<String, Any?> = emptyMap(),
    val patterns: Patterns = Patterns()
) {
    val inboxChrome: InboxChrome?
        get() = patterns.inbox

    val floatingIcon: FloatingIcon?
        get() = patterns.inbox?.floatingIcon

    @InternalCustomerIOApi
    data class Patterns(
        val inbox: InboxChrome? = null,
        /**
         * Mode overrides. Dark mode (patterns.modes.dark) is OPTIONAL and this is
         * null when `patterns.modes` is absent — callers must never assume it is
         * present.
         */
        val modes: Modes? = null
    )

    @InternalCustomerIOApi
    data class Modes(
        /** patterns.modes.dark — raw overrides map, or null when absent. */
        val dark: Map<String, Any?>? = null
    )

    /** The floating notification-bell icon (patterns.inbox.floatingIcon). */
    @InternalCustomerIOApi
    data class FloatingIcon(
        val background: String? = null,
        val color: String? = null
    )

    /**
     * A drop shadow (patterns.inbox.shadow).
     */
    @InternalCustomerIOApi
    data class Shadow(
        val color: String? = null,
        val offsetX: Double? = null,
        val offsetY: Double? = null,
        val blur: Double? = null
    )

    /**
     * The unread badge/indicator (patterns.inbox.unreadIndicator).
     *
     * @param showAlert whether the unread alert badge is shown
     * @param text raw text-style tokens for the badge label (preserved verbatim)
     * @param background the badge background color
     */
    @InternalCustomerIOApi
    data class UnreadIndicator(
        val showAlert: Boolean? = null,
        val text: Map<String, Any?>? = null,
        val background: String? = null
    )

    /**
     * Strongly-typed inbox chrome (patterns.inbox), including the floating bell.
     *
     * Every field is nullable so a partial / forward-incompatible response still
     * parses (Gson tolerance): missing keys simply stay null.
     */
    @InternalCustomerIOApi
    data class InboxChrome(
        val floatingIcon: FloatingIcon? = null,
        val background: String? = null,
        val cornerRadius: Double? = null,
        val borderColor: String? = null,
        val dividerColor: String? = null,
        val shadow: Shadow? = null,
        val position: String? = null,
        val hoverBackground: String? = null,
        val unreadIndicator: UnreadIndicator? = null
    )
}
