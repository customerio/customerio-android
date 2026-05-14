package io.customer.datapipelines.plugins

import android.app.Activity
import android.content.pm.PackageManager
import com.segment.analytics.kotlin.android.plugins.AndroidLifecycle
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.sdk.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.sdk.tracking.TrackableScreen

/**
 * Plugin that automatically tracks screens via ActivityLifecycleCallbacks `onActivityStarted` event of an activity.
 *
 * Emissions are deduped in-memory per plugin instance by screen name only (case-sensitive),
 * mirroring the iOS `InMemoryAutoTrackingScreenViewStore` (see `AutoTrackingScreenViews.swift`
 * lines ~250-268). Two consecutive `onActivityStarted` callbacks resolving to the same
 * screen name produce only one emission; navigating away and back re-emits.
 */
class AutomaticActivityScreenTrackingPlugin : Plugin, AndroidLifecycle {

    override val type: Plugin.Type = Plugin.Type.Utility
    override lateinit var analytics: Analytics
    private val logger: Logger = SDKComponent.logger

    // Last screen name emitted by this plugin instance. Guarded by `@Synchronized` on
    // the dedup helper below — the codebase idiom (see CustomerIO.createInstance,
    // IdentifyHookRegistry, PreferenceCrypto) is `@Synchronized` rather than ReentrantLock.
    // Scope: per-plugin-instance, lifetime of the process; not persisted across launches.
    private var lastScreenTracked: String? = null

    @Synchronized
    private fun shouldSkipDuplicate(screenName: String): Boolean {
        if (lastScreenTracked == screenName) return true
        lastScreenTracked = screenName
        return false
    }

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
                // Dedup by name only (case-sensitive), parity with iOS.
                if (shouldSkipDuplicate(screenName)) return
                runCatching { CustomerIO.instance().screen(screenName) }.onFailure { analytics.screen(screenName) }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            logger.error(e.message ?: "Unable to activity screen NameNotFoundException, $activity")
        } catch (e: Exception) {
            logger.error(e.message ?: "Unable to activity screen, $activity")
        }
    }
}
