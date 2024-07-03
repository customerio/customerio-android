package io.customer.commontest.util

import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.SdkDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchersProviderStub : DispatchersProvider {
    private var overrideBackground: CoroutineDispatcher? = null
    private var overrideMain: CoroutineDispatcher? = null
    private var overrideDefault: CoroutineDispatcher? = null

    // If your test function requires real dispatchers to be used, call this function.
    // the default behavior is test dispatchers because they are fast and synchronous for more predictable test execution.
    fun setRealDispatchers() {
        SdkDispatchers().also {
            overrideBackground = it.background
            overrideMain = it.main
            overrideDefault = it.default
        }
    }

    override val background: CoroutineDispatcher
        get() = overrideBackground ?: UnconfinedTestDispatcher()

    override val main: CoroutineDispatcher
        get() = overrideMain ?: UnconfinedTestDispatcher()

    override val default: CoroutineDispatcher
        get() = overrideDefault ?: UnconfinedTestDispatcher()
}
