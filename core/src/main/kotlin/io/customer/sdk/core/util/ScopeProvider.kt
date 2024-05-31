package io.customer.sdk.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

interface ScopeProvider {
    val eventBusScope: CoroutineScope
}

class SdkScopeProvider(private val dispatchers: DispatchersProvider) : ScopeProvider {
    override val eventBusScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob())
}
