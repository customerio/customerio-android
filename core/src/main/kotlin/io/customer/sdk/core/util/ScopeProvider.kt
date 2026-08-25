package io.customer.sdk.core.util

import io.customer.sdk.core.di.SDKComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

interface ScopeProvider {
    val eventBusScope: CoroutineScope
    val lifecycleListenerScope: CoroutineScope
    val inAppLifecycleScope: CoroutineScope
    val locationScope: CoroutineScope
    val geofenceScope: CoroutineScope
}

class SdkScopeProvider(private val dispatchers: DispatchersProvider) : ScopeProvider {

    // Last-resort net: an exception escaping a coroutine on an SDK scope would crash the
    // host app. This only logs and drops — call sites still own their error handling.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        // Exception spelled into the message: custom log dispatchers (wrapper SDKs) receive only
        // the message string, not the throwable.
        SDKComponent.logger.error("Uncaught exception in SDK coroutine: $throwable", throwable = throwable)
    }

    override val eventBusScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob() + exceptionHandler)
    override val lifecycleListenerScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob() + exceptionHandler)
    override val inAppLifecycleScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob() + exceptionHandler)
    override val locationScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob() + exceptionHandler)
    override val geofenceScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob() + exceptionHandler)
}
