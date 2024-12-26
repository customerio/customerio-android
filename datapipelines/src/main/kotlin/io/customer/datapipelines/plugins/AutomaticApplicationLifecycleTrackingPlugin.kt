package io.customer.datapipelines.plugins

import android.app.Activity
import com.segment.analytics.kotlin.android.plugins.AndroidLifecycle
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.platform.Plugin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plugin that automatically tracks application lifecycle.
 *
 * At the moment, this plugin only tracks 'Application Foregrounded' event because it's the only one missing from Segment SDK
 */
internal class AutomaticApplicationLifecycleTrackingPlugin : Plugin, AndroidLifecycle {

    override val type: Plugin.Type = Plugin.Type.Utility
    override lateinit var analytics: Analytics

    private val startedActivitiesCounter = AtomicInteger(0)
    private val isChangingConfigurations = AtomicBoolean(false)

    override fun onActivityStarted(activity: Activity?) {
        if (startedActivitiesCounter.incrementAndGet() == 1 && !isChangingConfigurations.get()) {
            analytics.track("Application Foregrounded")
        }
    }

    override fun onActivityStopped(activity: Activity?) {
        isChangingConfigurations.set(activity?.isChangingConfigurations == true)
        startedActivitiesCounter.decrementAndGet() // Return is ignored
    }
}
