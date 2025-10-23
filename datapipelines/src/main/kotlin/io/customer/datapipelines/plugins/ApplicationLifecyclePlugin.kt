package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.android.plugins.AndroidLifecyclePlugin
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.sdk.core.di.SDKComponent

/**
 * Plugin that flushes queued analytics events when the app enters background.
 *
 * This is critical for Android 15+ where background network requests are restricted by the OS.
 * Flushing on background prevents event delays until the next app launch.
 */
internal class ApplicationLifecyclePlugin : EventPlugin {
    override lateinit var analytics: Analytics
    override val type: Plugin.Type = Plugin.Type.After

    private val logger = SDKComponent.logger

    override fun track(payload: TrackEvent): BaseEvent? {
        // Flush events when app backgrounds to send before Android 15+ network restrictions apply
        if (payload.event == EVENT_APPLICATION_BACKGROUNDED) {
            logger.debug("App backgrounded, flushing events queue")
            analytics.flush()
        }
        return super.track(payload)
    }

    private companion object {
        /**
         * Event name fired by [AndroidLifecyclePlugin] when app enters background
         */
        private const val EVENT_APPLICATION_BACKGROUNDED = "Application Backgrounded"
    }
}
