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

    override val eventBusScope: CoroutineScope by lazy { createScope() }
    override val lifecycleListenerScope: CoroutineScope by lazy { createScope() }
    override val inAppLifecycleScope: CoroutineScope by lazy { createScope() }
    override val locationScope: CoroutineScope by lazy { createScope() }
    override val geofenceScope: CoroutineScope by lazy { createScope() }

    private fun createScope(): CoroutineScope =
        CoroutineScope(dispatchers.default + sdkJob + exceptionHandler)

    /** Cancels all SDK-owned work and waits for cancellation before DI teardown. */
    internal fun shutdown() {
        runBlocking {
            sdkJob.cancelAndJoin()
        }
    }
}
