package io.customer.datapipelines.plugins

import android.app.Activity
import android.content.pm.PackageManager
import com.segment.analytics.kotlin.android.plugins.AndroidLifecycle
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger

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
    private val logger: Logger = SDKComponent.logger

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
            // If screen name is null or blank, we do not track the screen
            if (!screenName.isNullOrBlank()) {
                analytics.screen(screenName)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            logger.error(e.message ?: "Unable to activity screen NameNotFoundException, $activity")
        } catch (e: Exception) {
            logger.error(e.message ?: "Unable to activity screen, $activity")
        }
    }
}
