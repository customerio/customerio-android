package io.customer.sdk.core.util

import io.customer.sdk.core.di.SDKComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

interface ScopeProvider {
    val eventBusScope: CoroutineScope
    val lifecycleListenerScope: CoroutineScope
    val inAppLifecycleScope: CoroutineScope
    val locationScope: CoroutineScope
    val geofenceScope: CoroutineScope
}

class SdkScopeProvider(private val dispatchers: DispatchersProvider) : ScopeProvider {

    // All SDK-owned work is attached to one lifecycle. Resetting the SDK can
    // therefore cancel and await every child before its dependencies are cleared.
    private val sdkJob = SupervisorJob()

    // Last-resort net: an exception escaping a coroutine on an SDK scope would crash the
    // host app. This only logs and drops — call sites still own their error handling.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        // Exception spelled into the message: custom log dispatchers (wrapper SDKs) receive only
        // the message string, not the throwable.
        SDKComponent.logger.error("Uncaught exception in SDK coroutine: $throwable", throwable = throwable)
    }

    override val eventBusScope: CoroutineScope
        get() = createScope()
    override val lifecycleListenerScope: CoroutineScope
        get() = createScope()
    override val inAppLifecycleScope: CoroutineScope
        get() = createScope()
    override val locationScope: CoroutineScope
        get() = createScope()
    override val geofenceScope: CoroutineScope
        get() = createScope()

    private fun createScope(): CoroutineScope =
        // Keep each returned scope independently cancellable. Some one-shot
        // geofence operations cancel their scope after completion, while the
        // parent job still owns every scope for SDK-wide teardown.
        CoroutineScope(dispatchers.default + SupervisorJob(sdkJob) + exceptionHandler)

    /** Cancels all SDK-owned work and waits for cancellation before DI teardown. */
    internal fun shutdown() {
        runBlocking {
            sdkJob.cancelAndJoin()
        }
    }
}
