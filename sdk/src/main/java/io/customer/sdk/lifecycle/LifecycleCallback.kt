package io.customer.sdk.lifecycle

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.Lifecycle

interface LifecycleCallback {
    // Events which the callback wants to observe on
    // If it contains [Lifecycle.Event.ON_ANY], it should be the only event
    // to avoid repetitive callbacks
    val eventsToObserve: List<Lifecycle.Event>

    fun onEventChanged(event: Lifecycle.Event, activity: Activity, extras: Bundle? = null) {}
}
