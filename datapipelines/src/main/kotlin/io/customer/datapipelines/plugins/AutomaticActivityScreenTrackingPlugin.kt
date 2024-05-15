package io.customer.datapipelines.plugins

import android.app.Activity
import android.content.pm.PackageManager
import com.segment.analytics.kotlin.android.plugins.AndroidLifecycle
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.platform.Plugin

/**
 * Optional interface for activities that should be tracked using automated screen tracking.
 */
interface TrackableScreen {
    /**
     * Retrieve the name that should be used for tracking the screen. This name
     * should be unique for each screen.
     *
     * @return name for tracking the screen, or null if the screen shouldn't be tracked.
     */
    fun getScreenName(): String?
}

/**
 * Plugin that automatically tracks screens via ActivityLifecycleCallbacks `onActivityStarted` event of an activity.
 */
class AutomaticActivityScreenTrackingPlugin : Plugin, AndroidLifecycle {

    override val type: Plugin.Type = Plugin.Type.Utility
    override lateinit var analytics: Analytics

    override fun onActivityStarted(activity: Activity?) {
        val packageManager = activity?.packageManager
        try {
            val screenName: String? = if (activity is TrackableScreen) {
                // TrackableScreen takes precedence over manifest label
                activity.getScreenName()
            } else {
                val info = packageManager?.getActivityInfo(
                    activity.componentName,
                    PackageManager.GET_META_DATA
                )
                val activityLabel = info?.loadLabel(packageManager)

                activity?.let {
                    activityLabel.toString().ifEmpty {
                        activity::class.java.simpleName.getScreenNameFromActivity()
                    }
                }
            }
            // If screen name is null, we do not track the screen
            screenName?.let {
                analytics.screen(screenName)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // if `PackageManager.NameNotFoundException` is thrown, is that a bug in the SDK or a problem with the customer's app?
            // We may want to decide to log this as an SDK error, log it so customer notices it to fix it themselves, or we do nothing because this exception might not be a big issue.
            // ActionUtils.getErrorAction(ErrorResult(error = ErrorDetail(message = "Activity Not Found: $e")))
        } catch (e: Exception) {
            // Should we log exceptions that happen? Ignore them? How rare is an exception happen in this function?
            // ActionUtils.getErrorAction(ErrorResult(error = ErrorDetail(message = "Unable to track, $activity")))
        }
    }
}
