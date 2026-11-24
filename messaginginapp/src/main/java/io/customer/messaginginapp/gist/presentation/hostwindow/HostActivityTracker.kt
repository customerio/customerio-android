package io.customer.messaginginapp.gist.presentation.hostwindow

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.customer.messaginginapp.gist.presentation.GistModalActivity
import java.lang.ref.WeakReference

/**
 * Tracks the host app's resumed activity.
 *
 * Uses plain [Application.ActivityLifecycleCallbacks] so callbacks arrive synchronously on the main
 * thread and none is ever dropped. Braze, Firebase In-App Messaging and OneSignal track the resumed
 * activity the same way. The SDK's shared flow-based lifecycle callbacks emit with `tryEmit` into a
 * `replay = 1` flow without extra buffer, which cannot guarantee delivery of every event to a slow
 * collector, and the presenter needs every resume and destroy.
 */
internal class HostActivityTracker(
    private val isModalActivity: (Activity) -> Boolean = { it is GistModalActivity }
) : Application.ActivityLifecycleCallbacks {

    interface Listener {
        fun onHostResumed(activity: Activity)
        fun onHostDestroyed(activity: Activity)
    }

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    @Volatile
    var listener: Listener? = null

    /** The foreground host activity, or null when the app is in the background or the activity is going away. */
    val resumedActivity: Activity?
        get() = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        // The legacy modal activity is never a host for a modal.
        if (isModalActivity(activity)) return
        resumed = WeakReference(activity)
        listener?.onHostResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
        listener?.onHostDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
