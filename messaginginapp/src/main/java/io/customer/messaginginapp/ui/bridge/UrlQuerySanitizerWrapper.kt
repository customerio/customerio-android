package io.customer.messaginginapp.ui.bridge

import android.net.UrlQuerySanitizer
import io.customer.base.internal.InternalCustomerIOApi

/**
 * Abstraction for reading query parameter values from URL.
 * Allows decoupling from Android specific implementations for easier testing.
 */
@InternalCustomerIOApi
interface UrlQuerySanitizerWrapper {
    fun getValue(key: String): String
}

/**
 * Android specific implementation of [UrlQuerySanitizerWrapper] using [UrlQuerySanitizer].
 *
 * The one-arg [UrlQuerySanitizer] convenience constructor uses a default value sanitizer that
 * treats space (U+0020) as illegal and replaces it with '_'. This is correct for strict URI
 * parsing but breaks in-app "Open URL" actions where the destination URL may contain
 * percent-decoded spaces in query values (e.g. `name=A%20B` -> `name=A B`).
 *
 * Fix: use the no-arg constructor, enable unregistered parameters, and set the value sanitizer
 * to [UrlQuerySanitizer.getUrlAndSpaceLegal], which permits URL-legal characters plus space.
 * Base64 characters ([A-Za-z0-9+/=]) are all URL-legal, so the `showMessage` branch's
 * `properties` and `messageId` values continue to round-trip unchanged.
 */
internal class AndroidUrlQuerySanitizer(
    url: String
) : UrlQuerySanitizerWrapper {
    private val sanitizer = UrlQuerySanitizer().apply {
        // Allow parameters whose names have not been registered explicitly.
        allowUnregisteredParamaters(true)
        // Use a sanitizer that keeps space characters intact in decoded values.
        // getUrlAndSpaceLegal() = URL_LEGAL | SPACE_OK, which covers all printable ASCII
        // URL characters (including base64 chars: A-Z, a-z, 0-9, +, /, =) plus space.
        setDefaultValueSanitizer(UrlQuerySanitizer.getUrlAndSpaceLegal())
        parseUrl(url)
    }

    // UrlQuerySanitizer.getValue returns null for unknown keys; return empty string instead
    // so callers can rely on the non-null contract declared by UrlQuerySanitizerWrapper.
    override fun getValue(key: String): String = sanitizer.getValue(key) ?: ""
}
