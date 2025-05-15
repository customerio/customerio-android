package io.customer.messaginginapp.testutils.fakes

import io.customer.messaginginapp.ui.bridge.UrlQuerySanitizerWrapper

/**
 * Fake implementation of [UrlQuerySanitizerWrapper] for testing purposes.
 */
class FakeQuerySanitizer(
    private val params: Map<String, String> = emptyMap()
) : UrlQuerySanitizerWrapper {
    override fun getValue(key: String): String {
        return params[key].orEmpty()
    }
}
