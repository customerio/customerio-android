package io.customer.messaginginbox

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.customer.messaginginapp.di.inAppModuleConfig
import io.customer.messaginginapp.type.NotificationInboxAccessibilityLabels
import io.customer.sdk.core.di.SDKComponent

/**
 * Accessibility plumbing for the visual inbox: the SDK emits no strings of its own — every TalkBack
 * label comes from the host's [NotificationInboxAccessibilityLabels] on the in-app module config, and
 * an unconfigured label leaves the element unlabeled rather than falling back to English.
 */

/**
 * The host's labels from the in-app module config.
 *
 * Read on each composition rather than `remember`ed: an inbox composable can enter composition before
 * `CustomerIO` finishes initializing, and a remembered value would pin the empty defaults for as long
 * as that composable stays on screen. Reading module config is a property lookup, cheap enough to
 * repeat.
 */
@Composable
internal fun inboxAccessibilityLabels(): NotificationInboxAccessibilityLabels =
    SDKComponent.inAppModuleConfig.notificationInboxAccessibilityLabels

/**
 * The bell button's label: the count-aware [NotificationInboxAccessibilityLabels.bellWithUnreadCount]
 * while the badge shows (falling back to the plain [NotificationInboxAccessibilityLabels.bell]), the
 * plain bell label otherwise, or null when the host configured neither — leaving a button that is
 * still focusable and tappable, just unnamed. [showsUnreadCount] gates the count so TalkBack never
 * announces a count the workspace chose to hide (branding `unreadIndicator.showAlert`).
 *
 * Pure (non-Compose) so it is unit-testable on the JVM.
 */
internal fun resolveBellAccessibilityLabel(
    labels: NotificationInboxAccessibilityLabels,
    unopenedCount: Int,
    showsUnreadCount: Boolean
): String? = if (showsUnreadCount) {
    labels.bellWithUnreadCount?.invoke(unopenedCount) ?: labels.bell
} else {
    labels.bell
}

/** Applies [label] as the node's content description, or leaves the node unlabeled when null. */
internal fun Modifier.inboxContentDescription(label: String?): Modifier =
    if (label == null) this else semantics { contentDescription = label }

/**
 * The inbox bell glyph, shared by the bell button and the empty state so both draw identical art:
 * the workspace's branding SVG (`patterns.inbox.floatingIcon.svg`) when present and parseable,
 * otherwise the bundled default bell drawable. Both are tinted with [tint]. Decorative — callers
 * attach any content description to their own node.
 *
 * The SVG art is built (and validated) once per [bellSvg] via `remember` — malformed path data yields
 * null (not a crash) and falls back to the drawable — rather than parsed per frame.
 */
@Composable
internal fun InboxBellGlyph(
    bellSvg: String?,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val bellArt = remember(bellSvg) { bellSvg?.let(InboxBellSvg::buildArt) }
    if (bellArt != null) {
        InboxBellIcon(art = bellArt, tint = tint, modifier = modifier)
    } else {
        Image(
            painter = painterResource(id = R.drawable.cio_inbox_notifications),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = modifier
        )
    }
}
