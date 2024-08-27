package io.customer.commontest.util

import io.customer.sdk.core.util.ScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class ScopeProviderStub : ScopeProvider {
    override val eventBusScope: CoroutineScope = TestScope(UnconfinedTestDispatcher())
    override val lifecycleListenerScope: CoroutineScope = TestScope(UnconfinedTestDispatcher())
    override val inAppLifecycleScope: CoroutineScope = TestScope(UnconfinedTestDispatcher())
}
