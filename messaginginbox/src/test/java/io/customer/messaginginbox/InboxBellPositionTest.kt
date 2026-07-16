package io.customer.messaginginbox

import androidx.compose.ui.Alignment
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * Unit tests for [InboxBellPosition] — the branding `patterns.inbox.position` → Compose [Alignment]
 * mapping. The raw values must match the web SDK's `positionStyles` strings exactly.
 */
class InboxBellPositionTest {

    @Test
    fun rawValues_matchWebStrings() {
        InboxBellPosition.BottomRight.raw shouldBeEqualTo "bottom-right"
        InboxBellPosition.BottomLeft.raw shouldBeEqualTo "bottom-left"
        InboxBellPosition.TopRight.raw shouldBeEqualTo "top-right"
        InboxBellPosition.TopLeft.raw shouldBeEqualTo "top-left"
    }

    @Test
    fun resolve_givenBrandingStrings_mapsToAlignment() {
        InboxBellPosition.resolve("bottom-right").alignment shouldBeEqualTo Alignment.BottomEnd
        InboxBellPosition.resolve("bottom-left").alignment shouldBeEqualTo Alignment.BottomStart
        InboxBellPosition.resolve("top-right").alignment shouldBeEqualTo Alignment.TopEnd
        InboxBellPosition.resolve("top-left").alignment shouldBeEqualTo Alignment.TopStart
    }

    @Test
    fun resolve_givenNullOrUnknown_defaultsToBottomRight() {
        InboxBellPosition.resolve(null) shouldBeEqualTo InboxBellPosition.BottomRight
        InboxBellPosition.resolve("somewhere") shouldBeEqualTo InboxBellPosition.BottomRight
        // Exact-match only — no case/format normalization (matches web's switch(position)).
        InboxBellPosition.resolve("BOTTOM-LEFT") shouldBeEqualTo InboxBellPosition.BottomRight
    }
}
