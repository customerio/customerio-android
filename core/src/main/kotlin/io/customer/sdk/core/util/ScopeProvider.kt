package io.customer.sdk.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

interface ScopeProvider {
    val eventBusScope: CoroutineScope
    val lifecycleListenerScope: CoroutineScope
    val inAppLifecycleScope: CoroutineScope
    val locationScope: CoroutineScope
}

class SdkScopeProvider(private val dispatchers: DispatchersProvider) : ScopeProvider {
    override val eventBusScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob())
    override val lifecycleListenerScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob())
    override val inAppLifecycleScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob())
    override val locationScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob())
}
