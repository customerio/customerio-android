package io.customer.commontest.util

import io.customer.sdk.core.util.ScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

class ScopeProviderStub : ScopeProvider {

    private var overrideEventBusScope: CoroutineScope? = null

    override val eventBusScope: CoroutineScope
        get() = overrideEventBusScope ?: TestScope(UnconfinedTestDispatcher())
}
