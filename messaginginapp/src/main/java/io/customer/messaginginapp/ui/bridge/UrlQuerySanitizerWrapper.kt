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
 * The one-arg [UrlQuerySanitizer] convenience constructor uses a default unregistered-value
 * sanitizer that treats spaces as illegal and replaces them with '_'. This corrupts destination
 * URLs that contain spaces (e.g. query values like "A B, Inc" become "A_B,_Inc").
 *
 * Using the no-arg constructor with [UrlQuerySanitizer.getUrlAndSpaceLegal] preserves spaces
 * while keeping all other URL and base64 characters (used by the showMessage branch) unchanged.
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
