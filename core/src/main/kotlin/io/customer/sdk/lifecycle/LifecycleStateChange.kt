package io.customer.sdk.lifecycle

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference

/**
 * Represents an event in the lifecycle of an Activity.
 *
 * @property activity The Activity where the lifecycle event occurred
 * @property event The specific lifecycle event that occurred
 * @property bundle Optional data associated with the lifecycle event
 */
data class LifecycleStateChange(
    // since we are holding a strong reference of LifecycleStateChange in the MutableSharedFlow via reply, we should use WeakReference
    // of activity to avoid memory leaks
    val activity: WeakReference<Activity>,
    val event: Lifecycle.Event,
    val bundle: Bundle?
)
