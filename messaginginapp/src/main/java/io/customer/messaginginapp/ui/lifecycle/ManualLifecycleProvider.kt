package io.customer.messaginginapp.ui.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import io.customer.sdk.core.di.SDKComponent

/**
 * Fallback implementation of [LifecycleProvider] that doesn't rely on a real lifecycle.
 * Used when no lifecycle owner is available, providing a manual way to manage WebView interface.
 */
internal class ManualLifecycleProvider : LifecycleProvider {

    private val logger = SDKComponent.logger

    override fun addObserver(observer: LifecycleObserver): Boolean {
        logger.debug("ManualLifecycleProvider: No lifecycle available, observer not added")
        return false
    }

    override fun removeObserver(observer: LifecycleObserver) {
        // No-op since we don't have a real lifecycle to manage
    }

    override fun getLifecycleState(): Lifecycle.State? {
        return null
    }
}
