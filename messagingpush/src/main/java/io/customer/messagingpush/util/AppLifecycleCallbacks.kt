package io.customer.messagingpush.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import io.customer.sdk.lifecycle.LifecycleCallback

class AppLifecycleCallbacks internal constructor() : Application.ActivityLifecycleCallbacks {
    private val eventCallbacks = mutableMapOf<Lifecycle.Event, MutableList<LifecycleCallback>>()

    fun registerCallback(callback: LifecycleCallback) {
        for (event in callback.eventsToObserve) {
            eventCallbacks.getOrPut(event) { mutableListOf() }.add(callback)
        }
    }

    private fun sendEventToCallbacks(
        event: Lifecycle.Event,
        activity: Activity,
        bundle: Bundle? = null
    ) {
        listOf(
            eventCallbacks[event].orEmpty(),
            eventCallbacks[Lifecycle.Event.ON_ANY].orEmpty()
        ).flatten().forEach { callback ->
            callback.onEventChanged(
                event,
                activity,
                bundle
            )
        }
    }

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
        sendEventToCallbacks(Lifecycle.Event.ON_CREATE, activity, bundle)
    }

    override fun onActivityStarted(activity: Activity) {
        sendEventToCallbacks(Lifecycle.Event.ON_START, activity)
    }

    override fun onActivityResumed(activity: Activity) {
        sendEventToCallbacks(Lifecycle.Event.ON_RESUME, activity)
    }

    override fun onActivityPaused(activity: Activity) {
        sendEventToCallbacks(Lifecycle.Event.ON_PAUSE, activity)
    }

    override fun onActivityStopped(activity: Activity) {
        sendEventToCallbacks(Lifecycle.Event.ON_STOP, activity)
    }

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {
        // Not yet supported in the SDK
    }

    override fun onActivityDestroyed(activity: Activity) {
        sendEventToCallbacks(Lifecycle.Event.ON_DESTROY, activity)
    }
}
