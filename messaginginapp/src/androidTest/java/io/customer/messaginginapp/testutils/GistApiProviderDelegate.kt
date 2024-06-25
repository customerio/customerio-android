package io.customer.messaginginapp.testutils

import io.customer.messaginginapp.provider.GistApi
import io.customer.messaginginapp.provider.GistApiProvider

// Open GistApiProvider delegate to allow for mocking in tests
internal open class GistApiProviderDelegate(
    private val gistApiProvider: GistApi = GistApiProvider()
) : GistApi by gistApiProvider
