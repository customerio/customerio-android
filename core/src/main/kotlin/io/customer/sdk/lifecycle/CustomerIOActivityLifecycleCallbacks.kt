package io.customer.sdk.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import io.customer.sdk.core.di.SDKComponent
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * SDK component to listen to lifecycle events of all activities.
 * The class is responsible for registering and unregistering the lifecycle callbacks.
 * SDK clients can subscribe to lifecycle events of all activities using the [subscribe] function
 * and should avoid listening to events from ActivityLifecycleCallbacks directly.
 */
class CustomerIOActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    /**
     * remits the last emitted lifecycle state to provide last state to new subscribers.
     * */
    private val lifecycleEvents = MutableSharedFlow<LifecycleStateChange>(replay = 1)

    private val subscriberScope = SDKComponent.scopeProvider.lifecycleListenerScope

    /**
     * Register the lifecycle callbacks to start receiving events.
     */
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    /**
     * Unregister the lifecycle callbacks and cancel the subscriber scope to stop receiving events.
     */
    fun unregister(application: Application) {
        application.unregisterActivityLifecycleCallbacks(this)
        subscriberScope.cancel()
    }

    /**
     * Subscribe to lifecycle events of all activities.
     * The function receives lambda so subscribers can apply operations like
     * filtering, mapping, etc. before collecting the events.
     */
    fun subscribe(block: suspend CoroutineScope.(SharedFlow<LifecycleStateChange>) -> Unit) {
        subscriberScope.launch { block(lifecycleEvents) }
    }

    /**
     * Sends event received from activity to all subscribers.
     */
    private fun sendEventToCallbacks(
        activity: Activity,
        event: Lifecycle.Event,
        bundle: Bundle? = null
    ): Boolean = lifecycleEvents.tryEmit(
        LifecycleStateChange(activity = WeakReference(activity), event = event, bundle = bundle)
    )

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
        sendEventToCallbacks(activity, Lifecycle.Event.ON_CREATE, bundle)
    }

    override fun onActivityStarted(activity: Activity) {
        sendEventToCallbacks(activity, Lifecycle.Event.ON_START)
    }

    override fun onActivityResumed(activity: Activity) {
        sendEventToCallbacks(activity, Lifecycle.Event.ON_RESUME)
    }

    override fun onActivityPaused(activity: Activity) {
        sendEventToCallbacks(activity, Lifecycle.Event.ON_PAUSE)
    }

    override fun onActivityStopped(activity: Activity) {
        sendEventToCallbacks(activity, Lifecycle.Event.ON_STOP)
    }

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {
        // Not yet required by SDK
    }

    override fun onActivityDestroyed(activity: Activity) {
        sendEventToCallbacks(activity, Lifecycle.Event.ON_DESTROY)
    }
}
