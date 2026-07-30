package io.customer.messaginginapp.inbox.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test

/**
 * Parse coverage for [parseBrandingJson] using a trimmed but representative copy of
 * a real captured branding response. Asserts the floating bell + inbox chrome parse,
 * and that an ABSENT `patterns.modes.dark` (which is how this workspace's response
 * actually looks) yields null — never an assumed-present object.
 */
class BrandingParseTest {

    private val gson = Gson()

    private fun parse(json: String): Branding =
        parseBrandingJson(gson.fromJson(json, JsonObject::class.java), gson)

    // Trimmed copy of a real branding response: real chrome values, and NO
    // patterns.modes block at all (mirrors the captured response).
    private val representativeJson = """
        {
          "theme": { "text": { "color": "#000000" } },
          "patterns": {
            "inbox": {
              "floatingIcon": {
                "background": "#000000",
                "color": "#ffffff"
              },
              "background": "#ffffff",
              "cornerRadius": 8,
              "borderColor": "#d9d9d9",
              "dividerColor": "#d9d9d9",
              "shadow": { "color": "#00000026", "offsetX": 0, "offsetY": 2, "blur": 8 },
              "position": "bottom-right",
              "hoverBackground": "#f5f5f5",
              "unreadIndicator": {
                "showAlert": true,
                "text": { "fontSize": 8, "color": "#ffffff" },
                "background": "#e00000"
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun parse_givenRepresentativeBranding_expectFloatingIconBellParsed() {
        val branding = parse(representativeJson)

        val icon = branding.floatingIcon
        icon.shouldNotBeNull()
        icon.background shouldBeEqualTo "#000000"
        icon.color shouldBeEqualTo "#ffffff"
    }

    @Test
    fun parse_givenRepresentativeBranding_expectInboxChromeParsed() {
        val chrome = parse(representativeJson).inboxChrome
        chrome.shouldNotBeNull()

        chrome.background shouldBeEqualTo "#ffffff"
        chrome.cornerRadius shouldBeEqualTo 8.0
        chrome.borderColor shouldBeEqualTo "#d9d9d9"
        chrome.dividerColor shouldBeEqualTo "#d9d9d9"
        chrome.position shouldBeEqualTo "bottom-right"
        chrome.hoverBackground shouldBeEqualTo "#f5f5f5"
    }

    @Test
    fun parse_givenRepresentativeBranding_expectShadowParsed() {
        val shadow = parse(representativeJson).inboxChrome?.shadow
        shadow.shouldNotBeNull()

        shadow.color shouldBeEqualTo "#00000026"
        shadow.offsetX shouldBeEqualTo 0.0
        shadow.offsetY shouldBeEqualTo 2.0
        shadow.blur shouldBeEqualTo 8.0
    }

    @Test
    fun parse_givenRepresentativeBranding_expectUnreadIndicatorParsed() {
        val unread = parse(representativeJson).inboxChrome?.unreadIndicator
        unread.shouldNotBeNull()

        unread.showAlert shouldBeEqualTo true
        unread.background shouldBeEqualTo "#e00000"
        // Text tokens are preserved as a raw, nested map.
        unread.text.shouldNotBeNull()
        unread.text["color"] shouldBeEqualTo "#ffffff"
    }

    // --- The crux: patterns.modes.dark is ABSENT in this workspace -> null ---

    @Test
    fun parse_givenNoModesBlock_expectModesNull() {
        // representativeJson has no patterns.modes at all.
        parse(representativeJson).patterns.modes.shouldBeNull()
    }

    @Test
    fun parse_givenModesPresentButNoDark_expectDarkNull() {
        val json = """
            { "patterns": { "inbox": {}, "modes": { "light": { "x": 1 } } } }
        """.trimIndent()

        val modes = parse(json).patterns.modes
        modes.shouldNotBeNull()
        modes.dark.shouldBeNull()
    }

    @Test
    fun parse_givenModesWithDark_expectDarkParsedAsRawMap() {
        val json = """
            { "patterns": { "modes": { "dark": { "background": "#111111" } } } }
        """.trimIndent()

        val dark = parse(json).patterns.modes?.dark
        dark.shouldNotBeNull()
        dark["background"] shouldBeEqualTo "#111111"
    }

    @Test
    fun parse_givenMissingKeysThroughout_expectTolerantNulls() {
        // Entirely empty object: nothing assumed present.
        val branding = parse("{}")

        branding.theme shouldBeEqualTo emptyMap()
        branding.inboxChrome.shouldBeNull()
        branding.floatingIcon.shouldBeNull()
        branding.patterns.modes.shouldBeNull()
    }
}
