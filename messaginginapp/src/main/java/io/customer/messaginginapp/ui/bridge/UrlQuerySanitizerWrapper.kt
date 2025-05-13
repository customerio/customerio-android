package io.customer.messaginginapp.ui.bridge

import android.net.UrlQuerySanitizer

/**
 * Abstraction for reading query parameter values from URL.
 * Allows decoupling from Android specific implementations for easier testing.
 */
internal interface UrlQuerySanitizerWrapper {
    fun getValue(key: String): String
}

/**
 * Android specific implementation of [UrlQuerySanitizerWrapper] using [UrlQuerySanitizer].
 */
internal class AndroidUrlQuerySanitizer(
    url: String
) : UrlQuerySanitizerWrapper {
    private val sanitizer = UrlQuerySanitizer(url)
    override fun getValue(key: String): String = sanitizer.getValue(key)
}
