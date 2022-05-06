package io.customer.common_test.util

import io.customer.sdk.util.DispatchersProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineDispatcher

class DispatchersProviderStub : DispatchersProvider {
    override val background: CoroutineDispatcher
        get() = TestCoroutineDispatcher()
    override val main: CoroutineDispatcher
        get() = TestCoroutineDispatcher()
}
