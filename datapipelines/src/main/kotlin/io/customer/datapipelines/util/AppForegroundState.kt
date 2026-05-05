package io.customer.datapipelines.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.DispatchersProvider
import kotlinx.coroutines.withContext

/** Whether the app process is currently in foreground. */
internal interface AppForegroundState {
    suspend fun isInForeground(): Boolean
}

/**
 * [AppForegroundState] backed by [ProcessLifecycleOwner]. The `LifecycleOwner`
 * is resolved lazily on first read of [isInForeground], which always happens
 * inside `withContext(dispatchers.main)` — so `ProcessLifecycleOwner.get()`
 * and the `@MainThread` `currentState` getter both run on the main thread
 * regardless of where the SDK was initialized.
 */
internal class ProcessLifecycleForegroundState(
    processLifecycleOwnerProvider: () -> LifecycleOwner = { ProcessLifecycleOwner.get() },
    private val dispatchersProvider: DispatchersProvider = SDKComponent.dispatchersProvider
) : AppForegroundState {

    private val processLifecycleOwner: LifecycleOwner by lazy(processLifecycleOwnerProvider)

    override suspend fun isInForeground(): Boolean = withContext(dispatchersProvider.main) {
        processLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
