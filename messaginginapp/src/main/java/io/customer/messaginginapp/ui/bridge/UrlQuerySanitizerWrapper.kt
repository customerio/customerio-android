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
 * The one-argument [UrlQuerySanitizer] convenience constructor uses a default value sanitizer
 * that treats space as an illegal character and silently rewrites it to '_'. Query values that
 * contain spaces (e.g. a destination URL with `name=A B, Inc`) would therefore arrive at the
 * caller corrupted.
 *
 * Instead, we construct the sanitizer explicitly and register [UrlQuerySanitizer.getUrlAndSpaceLegal]
 * as the unregistered-parameter value sanitizer. That sanitizer accepts all URL-legal characters
 * plus space, so spaces are preserved while all other printable-ASCII characters (including the
 * base64 alphabet used by the `showMessage` properties parameter) continue to pass through
 * unchanged.
 */
internal class AndroidUrlQuerySanitizer(
    url: String
) : UrlQuerySanitizerWrapper {
    private val sanitizer = UrlQuerySanitizer().apply {
        allowUnregisteredParamaters = true
        unregisteredParameterValueSanitizer = UrlQuerySanitizer.getUrlAndSpaceLegal()
        parseUrl(url)
    }
    override fun getValue(key: String): String = sanitizer.getValue(key)
}
