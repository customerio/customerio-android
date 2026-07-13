package io.customer.messaginginbox

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test

/**
 * Unit tests for [InboxBellSvg] — the pure SVG extraction (viewBox + `<path d>`) used by the
 * branding bell glyph. Building the actual Path is Compose/`android.graphics`-bound and covered on
 * device, so only the JVM-safe extraction is tested here.
 */
class InboxBellSvgTest {

    @Test
    fun parse_givenViewBoxAndPath_extractsBoxAndData() {
        val svg = """<svg viewBox="0 0 24 25"><path d="M0 0 L24 0 L24 25 Z"/></svg>"""
        val parsed = InboxBellSvg.parse(svg)
        parsed.shouldNotBeNull()
        parsed.minX shouldBeEqualTo 0f
        parsed.minY shouldBeEqualTo 0f
        parsed.width shouldBeEqualTo 24f
        parsed.height shouldBeEqualTo 25f
        parsed.pathData shouldBeEqualTo listOf("M0 0 L24 0 L24 25 Z")
    }

    @Test
    fun parse_givenMultiplePaths_extractsAll() {
        val svg = """<svg viewBox="0 0 24 25"><path d="M0 0 L10 0 Z"/><path fill-rule="evenodd" d="M12 12 L20 20 Z"/></svg>"""
        val parsed = InboxBellSvg.parse(svg)
        parsed.shouldNotBeNull()
        parsed.pathData shouldBeEqualTo listOf("M0 0 L10 0 Z", "M12 12 L20 20 Z")
    }

    @Test
    fun parse_givenNegativeAndDecimalViewBox_parses() {
        // The real branding bell has a viewBox origin at 0 0 but decimal sizes are common; also verify
        // comma-separated viewBox values parse.
        val svg = """<svg viewBox="0,0,24,24.5"><path d="M0 0 Z"/></svg>"""
        val parsed = InboxBellSvg.parse(svg)
        parsed.shouldNotBeNull()
        parsed.width shouldBeEqualTo 24f
        parsed.height shouldBeEqualTo 24.5f
    }

    @Test
    fun parse_givenNoViewBox_defaultsTo24x24() {
        val parsed = InboxBellSvg.parse("""<svg><path d="M0 0 Z"/></svg>""")
        parsed.shouldNotBeNull()
        parsed.width shouldBeEqualTo 24f
        parsed.height shouldBeEqualTo 24f
    }

    @Test
    fun parse_givenNoPaths_returnsNull() {
        InboxBellSvg.parse("<svg viewBox=\"0 0 24 24\"></svg>").shouldBeNull()
        InboxBellSvg.parse("not an svg").shouldBeNull()
        InboxBellSvg.parse("").shouldBeNull()
    }

    @Test
    fun parse_reflectsRenderability() {
        InboxBellSvg.parse("""<svg viewBox="0 0 24 25"><path d="M0 0 Z"/></svg>""").shouldNotBeNull()
        InboxBellSvg.parse("<svg></svg>").shouldBeNull()
    }

    @Test
    fun parse_givenRealBrandingBell_extractsThreePaths() {
        // The actual branding floatingIcon.svg (evenodd bell with clapper cut-out) has 3 paths.
        val svg = """<svg width="24" height="25" viewBox="0 0 24 25" fill="none">""" +
            """<path fill-rule="evenodd" d="M9.05 19.1V19.9C9.05 21.3 10.1 22.4 11.5 22.4Z"/>""" +
            """<path fill-rule="evenodd" d="M6.26 6.41C7.69 5.17 9.61 4.49 11.5 4.49Z"/>""" +
            """<path fill-rule="evenodd" d="M11.5 1.68C10.5 1.68 9.62 2.56 9.62 3.65Z"/></svg>"""
        val parsed = InboxBellSvg.parse(svg)
        parsed.shouldNotBeNull()
        parsed.pathData.size shouldBeEqualTo 3
    }
}
