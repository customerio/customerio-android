package io.customer.messaginginbox

import androidx.compose.ui.Alignment

/**
 * Where the floating inbox bell is pinned, driven by the workspace branding
 * `patterns.inbox.position`. The raw values mirror the web SDK's `positionStyles`
 * (gist-web `inbox-component-manager`); anything absent or unrecognized falls back
 * to [BottomRight], matching web's `switch(position)` default.
 */
internal enum class InboxBellPosition(val raw: String) {
    BottomRight("bottom-right"),
    BottomLeft("bottom-left"),
    TopRight("top-right"),
    TopLeft("top-left");

    /** The Compose [Alignment] this position maps to within the full-screen overlay box. */
    val alignment: Alignment
        get() = when (this) {
            BottomRight -> Alignment.BottomEnd
            BottomLeft -> Alignment.BottomStart
            TopRight -> Alignment.TopEnd
            TopLeft -> Alignment.TopStart
        }

    companion object {
        /**
         * Resolve a raw branding position string to a [InboxBellPosition]. Exact-match only (no
         * case/format normalization), mirroring web's `switch(position)`; null / unknown → [BottomRight].
         */
        fun resolve(raw: String?): InboxBellPosition =
            entries.firstOrNull { it.raw == raw } ?: BottomRight
    }
}
