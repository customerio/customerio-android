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

    // Event bus, location and geofence work blocks on disk and Keystore calls, so it runs on the
    // background (IO) dispatcher. On Default, which has as few threads as the device has cores, a
    // slow Keystore would stall event delivery, including the metrics carried by the bus.
    override val eventBusScope: CoroutineScope
        get() = CoroutineScope(dispatchers.background + SupervisorJob() + exceptionHandler)
    override val lifecycleListenerScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob() + exceptionHandler)
    override val inAppLifecycleScope: CoroutineScope
        get() = CoroutineScope(dispatchers.default + SupervisorJob() + exceptionHandler)
    override val locationScope: CoroutineScope
        get() = CoroutineScope(dispatchers.background + SupervisorJob() + exceptionHandler)
    override val geofenceScope: CoroutineScope
        get() = CoroutineScope(dispatchers.background + SupervisorJob() + exceptionHandler)
}
