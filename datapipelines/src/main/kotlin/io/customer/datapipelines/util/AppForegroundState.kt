package io.customer.datapipelines.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** Whether the app process is currently in foreground. */
internal interface AppForegroundState {
    val isInForeground: Boolean
}

/** [AppForegroundState] using [ProcessLifecycleOwner]; owner is lazy so the class is safe to construct in pure JVM unit tests. */
internal class ProcessLifecycleForegroundState(
    processLifecycleOwnerProvider: () -> LifecycleOwner = { ProcessLifecycleOwner.get() }
) : AppForegroundState {

    private val processLifecycleOwner: LifecycleOwner by lazy(processLifecycleOwnerProvider)

    override val isInForeground: Boolean
        get() = processLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
