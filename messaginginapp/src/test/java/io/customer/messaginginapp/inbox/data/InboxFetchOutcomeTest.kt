package io.customer.messaginginapp.inbox.data

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test

/**
 * Coverage of the interim HIDDEN-vs-VISIBLE policy in [decideOutcome] (the
 * #26/#27 seam). The inbox shows ONLY when renderable; "no data" is HIDDEN, not
 * an error. Both templates AND branding are required-to-render and each resolves
 * fresh -> stale -> missing:
 *  - templates + branding both available (fresh or stale) -> Visible
 *  - either input missing (no fresh, no stale)            -> Hidden (never error)
 */
class InboxFetchOutcomeTest {

    private val branding = Branding(theme = mapOf("color" to "blue"))
    private val staleBranding = Branding(theme = mapOf("color" to "green"))

    // --- Fresh templates + fresh branding -> Visible, not from cache ---

    @Test
    fun decideOutcome_givenFreshTemplatesAndFreshBranding_expectVisibleNotFromCache() {
        val outcome = decideOutcome(
            freshTemplatesJson = "{\"fresh\":true}",
            staleTemplatesJson = null,
            freshBranding = branding,
            staleBranding = null
        )

        outcome.shouldBeInstanceOf<InboxFetchOutcome.Visible>()
        val visible = outcome as InboxFetchOutcome.Visible
        visible.templatesJson shouldBeEqualTo "{\"fresh\":true}"
        visible.branding shouldBeEqualTo branding
        visible.fromCache shouldBeEqualTo false
    }

    // --- Both inputs serve from stale -> still Visible, fromCache=true ---

    @Test
    fun decideOutcome_givenBothServeFromStale_expectVisibleFromCache() {
        val outcome = decideOutcome(
            freshTemplatesJson = null,
            staleTemplatesJson = "{\"stale\":true}",
            freshBranding = null,
            staleBranding = staleBranding
        )

        outcome.shouldBeInstanceOf<InboxFetchOutcome.Visible>()
        val visible = outcome as InboxFetchOutcome.Visible
        visible.templatesJson shouldBeEqualTo "{\"stale\":true}"
        visible.branding shouldBeEqualTo staleBranding
        visible.fromCache shouldBeEqualTo true
    }

    @Test
    fun decideOutcome_givenFreshTemplatesButStaleBranding_expectVisibleFromCache() {
        val outcome = decideOutcome(
            freshTemplatesJson = "{\"fresh\":true}",
            staleTemplatesJson = null,
            freshBranding = null,
            staleBranding = staleBranding
        )

        outcome.shouldBeInstanceOf<InboxFetchOutcome.Visible>()
        val visible = outcome as InboxFetchOutcome.Visible
        visible.branding shouldBeEqualTo staleBranding
        // A stale input means the overall result is served (partly) from cache.
        visible.fromCache shouldBeEqualTo true
    }

    // --- Templates missing (no fresh, no stale) -> Hidden, not error ---

    @Test
    fun decideOutcome_givenNoTemplatesAtAll_expectHiddenNotError() {
        val outcome = decideOutcome(
            freshTemplatesJson = null,
            staleTemplatesJson = null,
            freshBranding = branding,
            staleBranding = null
        )

        outcome.shouldBeInstanceOf<InboxFetchOutcome.Hidden>()
        // Diagnostic reason is aligned BYTE-FOR-BYTE with iOS.
        (outcome as InboxFetchOutcome.Hidden).reason shouldBeEqualTo "templates unavailable"
    }

    // --- Branding now REQUIRED-to-render: missing branding hides the inbox ---

    @Test
    fun decideOutcome_givenTemplatesButNoBrandingAtAll_expectHiddenNotError() {
        val outcome = decideOutcome(
            freshTemplatesJson = "{\"fresh\":true}",
            staleTemplatesJson = null,
            freshBranding = null,
            staleBranding = null
        )

        outcome.shouldBeInstanceOf<InboxFetchOutcome.Hidden>()
        (outcome as InboxFetchOutcome.Hidden).reason shouldBeEqualTo "branding unavailable"
    }

    @Test
    fun decideOutcome_givenNothingAvailable_expectHidden() {
        val outcome = decideOutcome(
            freshTemplatesJson = null,
            staleTemplatesJson = null,
            freshBranding = null,
            staleBranding = null
        )

        outcome.shouldBeInstanceOf<InboxFetchOutcome.Hidden>()
        // Both missing -> applicable reasons joined with ", " in order (templates, branding),
        // mirroring the iOS combined diagnostic reason.
        (outcome as InboxFetchOutcome.Hidden).reason shouldBeEqualTo "templates unavailable, branding unavailable"
    }
}
