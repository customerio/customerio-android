package io.customer.messaginginapp.ui.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver

/**
 * Interface for providing lifecycle observation capabilities to UI components.
 * This allows different view types (modal, inline) to provide appropriate lifecycles
 * based on their host context (Activity vs View).
 */
internal interface LifecycleProvider {
    /**
     * Add a lifecycle observer to be notified of lifecycle events.
     *
     * @param observer The lifecycle observer to add
     * @return True if the observer was successfully added, false otherwise
     */
    fun addObserver(observer: LifecycleObserver): Boolean

    /**
     * Remove a previously added lifecycle observer.
     *
     * @param observer The lifecycle observer to remove
     */
    fun removeObserver(observer: LifecycleObserver)

    /**
     * Get the current lifecycle state.
     *
     * @return The current lifecycle state, or null if no lifecycle is available
     */
    fun getLifecycleState(): Lifecycle.State?
}
