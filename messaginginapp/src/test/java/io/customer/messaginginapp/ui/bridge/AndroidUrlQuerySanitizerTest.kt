package io.customer.messaginginapp.ui.bridge

import io.customer.messaginginapp.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [AndroidUrlQuerySanitizer] to verify query value parsing preserves
 * spaces and does not corrupt characters in URL or base64 values.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidUrlQuerySanitizerTest : IntegrationTest() {

    // gist://loadPage?url=<percent-encoded destination URL>
    // The space in the destination URL is encoded as %20.
    // Before the fix, UrlQuerySanitizer's default unregistered-value sanitizer would
    // unescape %20 -> space, then replace space with '_', producing "A_B,_Inc" instead of "A B, Inc".
    @Test
    fun getValue_givenLoadPageActionWithSpaceInUrl_expectSpacePreservedNotReplacedWithUnderscore() {
        val destUrl = "https://example.com/?name=A%20B%2C%20Inc"
        val action = "gist://loadPage?url=$destUrl"

        val result = AndroidUrlQuerySanitizer(action).getValue("url")

        result shouldBeEqualTo "https://example.com/?name=A B, Inc"
    }

    // Regression guard: the showMessage branch reads a base64-encoded properties value.
    // Base64 characters (A-Z, a-z, 0-9, +, /, =) must round-trip unchanged.
    @Test
    fun getValue_givenShowMessageActionWithBase64Properties_expectBase64Unchanged() {
        val base64Properties = "eyJrZXkiOiJ2YWx1ZSJ9" // {"key":"value"}
        val action = "gist://showMessage?messageId=test-msg-id&properties=$base64Properties"

        val result = AndroidUrlQuerySanitizer(action).getValue("properties")

        result shouldBeEqualTo base64Properties
    }

    @Test
    fun getValue_givenShowMessageActionWithBase64Properties_expectMessageIdUnchanged() {
        val base64Properties = "eyJrZXkiOiJ2YWx1ZSJ9"
        val action = "gist://showMessage?messageId=test-msg-id&properties=$base64Properties"

        val result = AndroidUrlQuerySanitizer(action).getValue("messageId")

        result shouldBeEqualTo "test-msg-id"
    }
}
