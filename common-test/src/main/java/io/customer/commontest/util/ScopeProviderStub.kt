package io.customer.commontest.util

import io.customer.sdk.core.util.ScopeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

class ScopeProviderStub private constructor(
    override val eventBusScope: TestScope,
    override val lifecycleListenerScope: TestScope,
    override val inAppLifecycleScope: TestScope,
    override val globalPreferenceStoreScope: TestScope
) : ScopeProvider {

    @Suppress("FunctionName")
    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun Unconfined(): ScopeProviderStub = ScopeProviderStub(
            eventBusScope = TestScope(UnconfinedTestDispatcher()),
            lifecycleListenerScope = TestScope(UnconfinedTestDispatcher()),
            inAppLifecycleScope = TestScope(UnconfinedTestDispatcher()),
            globalPreferenceStoreScope = TestScope(UnconfinedTestDispatcher())
        )

        fun Standard(): ScopeProviderStub = ScopeProviderStub(
            eventBusScope = TestScope(StandardTestDispatcher()),
            lifecycleListenerScope = TestScope(StandardTestDispatcher()),
            inAppLifecycleScope = TestScope(StandardTestDispatcher()),
            globalPreferenceStoreScope = TestScope(StandardTestDispatcher())
        )
    }
}
