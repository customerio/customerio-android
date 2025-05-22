package io.customer.messaginginapp.ui.lifecycle

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.customer.sdk.core.di.SDKComponent

/**
 * Implementation of [LifecycleProvider] that uses an Activity's lifecycle.
 * This is appropriate for modal messages that are tied to the activity lifecycle.
 */
internal class ActivityLifecycleProvider(
    private val activity: Activity
) : LifecycleProvider {
    private val logger = SDKComponent.logger

    override fun addObserver(observer: LifecycleObserver): Boolean {
        if (activity is LifecycleOwner) {
            activity.lifecycle.addObserver(observer)
            return true
        }
        // If activity doesn't implement LifecycleOwner, we'll fall back to manual mode
        logger.debug("Activity does not implement LifecycleOwner, using manual attachment")
        return false
    }

    override fun removeObserver(observer: LifecycleObserver) {
        if (activity is LifecycleOwner) {
            activity.lifecycle.removeObserver(observer)
        }
    }

    override fun getLifecycleState(): Lifecycle.State? {
        return if (activity is LifecycleOwner) {
            activity.lifecycle.currentState
        } else {
            null
        }
    }
}
