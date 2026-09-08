package io.customer.messaginginapp.ui.bridge

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that [AndroidUrlQuerySanitizer] preserves spaces in decoded query values.
 *
 * The one-arg [android.net.UrlQuerySanitizer] constructor uses a default value sanitizer that
 * treats space (U+0020) as illegal and replaces it with '_'. The fix switches to the no-arg
 * constructor with an explicit [android.net.UrlQuerySanitizer.getUrlAndSpaceLegal] sanitizer so
 * spaces survive decoding intact.
 *
 * Coverage targets (impl-robust):
 *   - single %20 space in a "url" parameter value
 *   - multiple consecutive and scattered %20 spaces
 *   - leading and trailing spaces in a decoded value
 *   - base64 "properties" parameter (regression guard for the showMessage branch)
 *   - base64 with padding '=' character
 *   - URL-legal characters (commas, hyphens) that must remain unchanged
 *   - missing key returns empty string, not null or NPE
 *   - key present with an empty value
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUrlQuerySanitizerTest {

    // ---- Open-URL (loadPage) branch ---------------------------------------------------------

    @Test
    fun getValue_givenUrlParamWithSingleEncodedSpace_preservesSpace() {
        // Raw action: gist://loadPage?url=https://example.com/?name=A%20B%2C%20Inc
        // %20 decodes to space; %2C decodes to comma.
        // Before fix: getValue("url") == "https://example.com/?name=A_B,_Inc"
        // After fix:  getValue("url") == "https://example.com/?name=A B, Inc"
        val sanitizer = AndroidUrlQuerySanitizer(
            "gist://loadPage?url=https://example.com/?name=A%20B%2C%20Inc"
        )
        sanitizer.getValue("url") shouldBeEqualTo "https://example.com/?name=A B, Inc"
    }

    @Test
    fun getValue_givenUrlParamWithMultipleEncodedSpaces_preservesAllSpaces() {
        val sanitizer = AndroidUrlQuerySanitizer(
            "gist://loadPage?url=https://example.com/?q=hello%20world%20foo%20bar"
        )
        sanitizer.getValue("url") shouldBeEqualTo "https://example.com/?q=hello world foo bar"
    }

    @Test
    fun getValue_givenUrlParamWithLeadingAndTrailingEncodedSpaces_preservesSpaces() {
        val sanitizer = AndroidUrlQuerySanitizer(
            "gist://loadPage?url=https://example.com/?q=%20hello%20"
        )
        sanitizer.getValue("url") shouldBeEqualTo "https://example.com/?q= hello "
    }

    // ---- showMessage branch (base64 regression guard) ----------------------------------------

    @Test
    fun getValue_givenBase64PropertiesParam_roundTripsUnchanged() {
        // Base64 for {"key":"value"} = eyJrZXkiOiJ2YWx1ZSJ9 -- no padding, no space
        val base64Props = "eyJrZXkiOiJ2YWx1ZSJ9"
        val sanitizer = AndroidUrlQuerySanitizer(
            "gist://showMessage?messageId=msg-abc-123&properties=$base64Props"
        )
        sanitizer.getValue("properties") shouldBeEqualTo base64Props
        sanitizer.getValue("messageId") shouldBeEqualTo "msg-abc-123"
    }

    @Test
    fun getValue_givenBase64PropertiesWithPaddingEquals_roundTripsUnchanged() {
        // Base64 for {"ab":"cd"} = eyJhYiI6ImNkIn0= -- one padding '=' char
        val base64WithPadding = "eyJhYiI6ImNkIn0="
        val sanitizer = AndroidUrlQuerySanitizer(
            "gist://showMessage?messageId=msg-456&properties=$base64WithPadding"
        )
        sanitizer.getValue("properties") shouldBeEqualTo base64WithPadding
        sanitizer.getValue("messageId") shouldBeEqualTo "msg-456"
    }

    // ---- URL-legal chars (must stay unchanged under the new sanitizer) -----------------------

    @Test
    fun getValue_givenUrlParamWithCommasAndHyphens_preservesChars() {
        val sanitizer = AndroidUrlQuerySanitizer(
            "gist://loadPage?url=https://example.com/?q=hello-world,test"
        )
        sanitizer.getValue("url") shouldBeEqualTo "https://example.com/?q=hello-world,test"
    }

    // ---- Missing / empty key handling --------------------------------------------------------

    @Test
    fun getValue_givenMissingKey_returnsEmptyString() {
        val sanitizer = AndroidUrlQuerySanitizer(
            "gist://loadPage?url=https://example.com/"
        )
        sanitizer.getValue("missing") shouldBeEqualTo ""
        sanitizer.getValue("alsoMissing") shouldBeEqualTo ""
    }

    @Test
    fun getValue_givenKeyWithEmptyValue_returnsEmptyString() {
        val sanitizer = AndroidUrlQuerySanitizer(
            "gist://loadPage?url="
        )
        sanitizer.getValue("url") shouldBeEqualTo ""
    }
}
