package io.customer.sdk.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
     * The activities that are resumed, and whether a resume or pause has been seen since registration.
     * Tracked by identity, so a pause of an activity the SDK never saw resume, as after a late
     * initialization, leaves the others counted. Held weakly, so a leaked activity is never kept alive.
     * Updated synchronously on the main thread and readable from any thread, unlike the process
     * lifecycle, which reports that the app left the foreground about 700 ms after the last pause.
     */
    private val resumedActivities: MutableSet<Activity> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val hasSeenResumeOrPause = AtomicBoolean(false)

    /**
     * Whether an activity of this app is resumed right now: true while one is in the foreground, false
     * from the moment the last one pauses. Returns null until the first resume or pause is seen, for
     * example when the SDK was initialized after the first activity had already resumed. The caller
     * then has no answer and should fall back to another signal.
     */
    @InternalCustomerIOApi
    fun hasResumedActivity(): Boolean? =
        if (hasSeenResumeOrPause.get()) resumedActivities.isNotEmpty() else null

    /** One-shot listeners waiting for the next resume, see [runOnNextResume]. Guarded by itself. */
    private val resumeListeners = mutableListOf<suspend CoroutineScope.() -> Unit>()

    /**
     * Runs [block] once, the next time an activity resumes, or right away when one is already resumed.
     *
     * Unlike a [subscribe]r, it never misses the resume: the subscriber flow is non-suspending and drops an
     * event while a collector is still processing the previous one, which a start followed by a resume,
     * called back to back on the main thread, makes likely. This hook is called synchronously from the
     * resume callback and then runs [block] on the subscriber scope.
     */
    @InternalCustomerIOApi
    fun runOnNextResume(block: suspend CoroutineScope.() -> Unit) {
        synchronized(resumeListeners) { resumeListeners.add(block) }
        // The activity may have resumed between the caller's check and this registration
        if (hasResumedActivity() == true) {
            runResumeListeners()
        }
    }

    private fun runResumeListeners() {
        val ready = synchronized(resumeListeners) {
            resumeListeners.toList().also { resumeListeners.clear() }
        }
        ready.forEach { listener -> subscriberScope.launch { listener() } }
    }

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
        synchronized(resumeListeners) { resumeListeners.clear() }
        resumedActivities.clear()
        hasSeenResumeOrPause.set(false)
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
        resumedActivities.add(activity)
        hasSeenResumeOrPause.set(true)
        sendEventToCallbacks(activity, Lifecycle.Event.ON_RESUME)
        runResumeListeners()
    }

    override fun onActivityPaused(activity: Activity) {
        // The first pause seen can belong to an activity that resumed before registration, which is not in the set
        resumedActivities.remove(activity)
        hasSeenResumeOrPause.set(true)
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
