package io.customer.sdk

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.lifecycle.LifecycleCallback

class CustomerIOActivityLifecycleCallbacks internal constructor(
    private val config: CustomerIOConfig
) : ActivityLifecycleCallbacks {
    private val customerIO: CustomerIO by lazy { CustomerIO.instance() }
    private val eventCallbacks = mutableMapOf<Lifecycle.Event, MutableList<LifecycleCallback>>()

    fun registerCallback(callback: LifecycleCallback) {
        for (event in callback.eventsToObserve) {
            eventCallbacks.getOrPut(event) { mutableListOf() }.add(callback)
        }
    }

    /**
     * In non-native/wrapper SDKs, module listeners are attached after the
     * activity has been created which results in missing initial lifecycle
     * events. This method helps completing any pending actions by emitting
     * those events manually. This method should be called as soon as SDK is
     * initialized in wrapper SDKs by client app.
     */
    @InternalCustomerIOApi
    fun postDelayedEventsForNonNativeActivity(activity: Activity) {
        val bundle = activity.intent?.extras
        sendEventToCallbacks(
            event = Lifecycle.Event.ON_CREATE,
            activity = activity,
            bundle = bundle
        )
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

        if (config.autoTrackScreenViews) {
            customerIO.screen(activity)
        }
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
