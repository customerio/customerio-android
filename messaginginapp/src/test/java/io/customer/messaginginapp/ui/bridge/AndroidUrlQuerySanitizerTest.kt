package io.customer.messaginginapp.ui.bridge

import android.net.UrlQuerySanitizer
import android.util.Base64
import io.customer.messaginginapp.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [AndroidUrlQuerySanitizer] to verify that query-parameter values round-trip
 * without character substitution.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidUrlQuerySanitizerTest : IntegrationTest() {

    // ---- loadPage branch (Open URL action) ----------------------------------------

    @Test
    fun getValue_givenLoadPageUrlWithSpacesInQueryValue_expectSpacesPreserved() {
        // gist://loadPage?url=<destination>; destination contains a space in a query value
        // encoded as %20. The sanitizer must decode %20 to a space and keep it -- not
        // substitute '_'.
        val action = "gist://loadPage?url=https://example.com/?name=A%20B,%20Inc"
        val sanitizer = AndroidUrlQuerySanitizer(action)

        val url = sanitizer.getValue("url")

        url shouldBeEqualTo "https://example.com/?name=A B, Inc"
    }

    @Test
    fun getValue_givenLoadPageUrlWithCommasAndHyphens_expectPunctuationPreserved() {
        // Commas and hyphens are already legal in the default sanitizer; verify the fix
        // does not regress them.
        val action = "gist://loadPage?url=https://example.com/path?ref=foo-bar,baz"
        val sanitizer = AndroidUrlQuerySanitizer(action)

        val url = sanitizer.getValue("url")

        url shouldBeEqualTo "https://example.com/path?ref=foo-bar,baz"
    }

    // ---- showMessage branch (regression guard for base64 properties) ---------------

    @Test
    fun getValue_givenShowMessageActionWithBase64Properties_expectBase64Unchanged() {
        // gist://showMessage?messageId=<id>&properties=<base64>
        // This sample contains no characters requiring query encoding.
        val messageId = "example-message-id-123"
        val base64Properties = "eyJrZXkiOiJ2YWx1ZSJ9" // {"key":"value"} base64-encoded
        val action = "gist://showMessage?messageId=$messageId&properties=$base64Properties"
        val sanitizer = AndroidUrlQuerySanitizer(action)

        sanitizer.getValue("messageId") shouldBeEqualTo messageId
        sanitizer.getValue("properties") shouldBeEqualTo base64Properties
    }

    @Test
    fun getValue_givenShowMessageActionWithBase64PaddingEquals_expectEqualsPreserved() {
        // Base64 padding '=' must not be dropped or substituted.
        val messageId = "msg-padded"
        val base64WithPadding = "dGVzdA==" // "test" base64-encoded
        val action = "gist://showMessage?messageId=$messageId&properties=$base64WithPadding"
        val sanitizer = AndroidUrlQuerySanitizer(action)

        sanitizer.getValue("properties") shouldBeEqualTo base64WithPadding
    }

    @Test
    fun testGetValue_whenCustomerShelterNameContainsEncodedSpaces_thenPreservesName() {
        val action = "gist://loadPage?url=https://example.com/?name=100Plus%20Animal%20Rescue,%20Inc"

        UrlQuerySanitizer(action).getValue("url") shouldBeEqualTo
            "https://example.com/?name=100Plus_Animal_Rescue,_Inc"
        AndroidUrlQuerySanitizer(action).getValue("url") shouldBeEqualTo
            "https://example.com/?name=100Plus Animal Rescue, Inc"
    }

    @Test
    fun testGetValue_whenDestinationIsQueryEncoded_thenPreservesInnerEscapesAndSeparators() {
        val action = "gist://loadPage?url=https%3A%2F%2Fexample.com%2F%3Fname%3DA%2520B%26next%3DC%252BD%23details"

        AndroidUrlQuerySanitizer(action).getValue("url") shouldBeEqualTo
            "https://example.com/?name=A%20B&next=C%2BD#details"
    }

    @Test
    fun testGetValue_whenBase64ContainsEncodedPlusSlashAndPadding_thenPreservesDecodedBytes() {
        val action = "gist://showMessage?properties=%2B%2F8%3D"

        val value = AndroidUrlQuerySanitizer(action).getValue("properties")

        value shouldBeEqualTo "+/8="
        Base64.decode(value, Base64.DEFAULT).toList() shouldBeEqualTo listOf(0xfb.toByte(), 0xff.toByte())
    }

    @Test
    fun testGetValue_whenDestinationUsesScriptScheme_thenRejectsValue() {
        for (scheme in listOf("javascript", "vbscript")) {
            val action = "gist://loadPage?url=$scheme%3Aalert(1)"

            AndroidUrlQuerySanitizer(action).getValue("url") shouldBeEqualTo ""
        }
    }
}
