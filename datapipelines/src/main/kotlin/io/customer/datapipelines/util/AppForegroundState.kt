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
 * [AppForegroundState] backed by [ProcessLifecycleOwner]. The `@MainThread`
 * `currentState` getter is invoked via `withContext(dispatchers.main)` so the
 * read happens on the main thread regardless of where the caller is suspended.
 */
internal class ProcessLifecycleForegroundState(
    private val processLifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
    private val dispatchersProvider: DispatchersProvider = SDKComponent.dispatchersProvider
) : AppForegroundState {

    override suspend fun isInForeground(): Boolean = withContext(dispatchersProvider.main) {
        processLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
