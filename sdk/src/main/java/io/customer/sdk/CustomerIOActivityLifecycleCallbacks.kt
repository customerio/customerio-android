package io.customer.sdk

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import io.customer.sdk.util.PushTrackingUtil

class CustomerIOActivityLifecycleCallbacks internal constructor(
    private val customerIO: CustomerIO,
    private val config: CustomerIOConfig,
    private val pushTrackingUtil: PushTrackingUtil
) : ActivityLifecycleCallbacks {

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
        val intentArguments = activity.intent.extras ?: return

        pushTrackingUtil.parseLaunchedActivityForTracking(intentArguments)
    }

    override fun onActivityStarted(activity: Activity) {
        if (config.autoTrackScreenViews) {
            customerIO.screen(activity)
        }
    }

    override fun onActivityResumed(activity: Activity) {
    }

    override fun onActivityPaused(activity: Activity) {
    }

    override fun onActivityStopped(activity: Activity) {
    }

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {
    }

    override fun onActivityDestroyed(activity: Activity) {
    }
}
