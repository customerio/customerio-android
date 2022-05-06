package io.customer.common_test.util

import io.customer.sdk.util.DispatchersProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineDispatcher

class DispatchersProviderStub : DispatchersProvider {
    var overrideBackground: CoroutineDispatcher? = null
    var overrideMain: CoroutineDispatcher? = null

    override val background: CoroutineDispatcher
        get() = overrideBackground ?: TestCoroutineDispatcher()
    override val main: CoroutineDispatcher
        get() = overrideMain ?: TestCoroutineDispatcher()
}
