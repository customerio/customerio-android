package io.customer.android.sample.kotlin_compose

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOShared

class ActivityLifecycleEventsListener : Application.ActivityLifecycleCallbacks {
    private val lifecycleEventKey = "ActivityLifecycleEvent"
    private val logger = CustomerIOShared.instance().diStaticGraph.logger

    fun logEvent(eventName: String, activity: Activity) {
        logger.debug(
            String.format(
                "%s: %s %s",
                lifecycleEventKey,
                eventName,
                activity.javaClass.simpleName
            )
        )
        try {
            val sdkInstance = CustomerIO.instance()
            val extras: MutableMap<String, String> = HashMap()
            extras["EventName"] = eventName
            extras["ActivityName"] = activity.javaClass.simpleName
            sdkInstance.track(lifecycleEventKey, extras)
        } catch (e: Exception) {
            logger.debug("SDK not yet initialized")
        }
    }

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
        logEvent("onActivityCreated", activity)
    }

    override fun onActivityStarted(activity: Activity) {
        logEvent("onActivityStarted", activity)
    }

    override fun onActivityResumed(activity: Activity) {
        logEvent("onActivityResumed", activity)
    }

    override fun onActivityPaused(activity: Activity) {
        logEvent("onActivityPaused", activity)
    }

    override fun onActivityStopped(activity: Activity) {
        logEvent("onActivityStopped", activity)
    }

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {
        logEvent("onActivitySaveInstanceState", activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        logEvent("onActivityDestroyed", activity)
    }
}
