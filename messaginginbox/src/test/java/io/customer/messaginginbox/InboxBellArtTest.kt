package io.customer.messaginginbox

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [InboxBellSvg.buildArt], which builds an `android.graphics.Path` and so
 * needs an Android runtime (unlike the pure string extraction in [InboxBellSvg.parse], covered by
 * [InboxBellSvgTest]).
 *
 * Regression guard for the crash-safe fallback: [PathParser][androidx.compose.ui.graphics.vector.PathParser]
 * THROWS `IllegalArgumentException` on invalid path commands, so malformed branding SVG must fall back
 * to the bundled bell (null) instead of crashing.
 */
// Robolectric SDK capped at 35 (project convention: 4.16 max is Android 15 / Java 17), matching
// `RobolectricTest`. Not extending that base — this suite needs no DI/context harness, only a
// runtime for `android.graphics.Path`.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InboxBellArtTest {

    @Test
    fun buildArt_givenValidPath_expectBuiltArtWithViewBox() {
        val svg = """<svg viewBox="0 0 24 25"><path d="M0 0 L24 0 L24 25 Z"/></svg>"""
        val art = InboxBellSvg.buildArt(svg)
        art.shouldNotBeNull()
        art.width shouldBeEqualTo 24f
        art.height shouldBeEqualTo 25f
    }

    @Test
    fun buildArt_givenMalformedPathCommand_expectNullNotCrash() {
        // 'X' is not a valid SVG path command; PathParser.parsePathString throws
        // IllegalArgumentException. buildArt must catch it and return null (→ bundled bell fallback).
        val svg = """<svg viewBox="0 0 24 25"><path d="M0 0 X1 1"/></svg>"""
        InboxBellSvg.buildArt(svg).shouldBeNull()
    }

    @Test
    fun buildArt_givenNoRenderablePath_expectNull() {
        InboxBellSvg.buildArt("""<svg viewBox="0 0 24 24"></svg>""").shouldBeNull()
        InboxBellSvg.buildArt("not an svg").shouldBeNull()
    }
}
