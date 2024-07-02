package io.customer.sdk.lifecycle

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.Lifecycle

/**
 * Represents an event in the lifecycle of an Activity.
 *
 * @property activity The Activity where the lifecycle event occurred
 * @property event The specific lifecycle event that occurred
 * @property bundle Optional data associated with the lifecycle event
 */
data class LifecycleStateChange(
    val activity: Activity,
    val event: Lifecycle.Event,
    val bundle: Bundle?
)
