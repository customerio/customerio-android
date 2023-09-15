package io.customer.commontest.util

import io.customer.sdk.util.DispatchersProvider
import io.customer.sdk.util.SdkDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineDispatcher

class DispatchersProviderStub : DispatchersProvider {
    private var overrideBackground: CoroutineDispatcher? = null
    private var overrideMain: CoroutineDispatcher? = null

    // If your test function requires real dispatchers to be used, call this function.
    // the default behavior is test dispatchers because they are fast and synchronous for more predictable test execution.
    fun setRealDispatchers() {
        SdkDispatchers().also {
            overrideBackground = it.background
            overrideMain = it.main
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val background: CoroutineDispatcher
        get() = overrideBackground ?: TestCoroutineDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val main: CoroutineDispatcher
        get() = overrideMain ?: TestCoroutineDispatcher()
}
