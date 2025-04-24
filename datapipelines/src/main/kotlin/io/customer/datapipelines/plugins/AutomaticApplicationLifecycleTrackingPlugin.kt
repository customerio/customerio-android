package io.customer.datapipelines.plugins

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.datapipelines.util.UiThreadRunner
import io.customer.sdk.CustomerIO

/**
 * Plugin that automatically tracks application lifecycle.
 *
 * At the moment, this plugin only tracks 'Application Foregrounded' event because it's the only one missing from Segment SDK
 */
internal class AutomaticApplicationLifecycleTrackingPlugin : Plugin {

    constructor() : this(ProcessLifecycleOwner.get(), UiThreadRunner())

    @VisibleForTesting
    constructor(processLifecycleOwner: LifecycleOwner, uiThreadRunner: UiThreadRunner) {
        this.processLifecycleOwner = processLifecycleOwner
        this.uiThreadRunner = uiThreadRunner
    }

    override val type: Plugin.Type = Plugin.Type.Utility
    override lateinit var analytics: Analytics

    private val processLifecycleOwner: LifecycleOwner
    private val uiThreadRunner: UiThreadRunner

    override fun setup(analytics: Analytics) {
        super.setup(analytics)
        uiThreadRunner.run {
            registerProcessLifecycleObserver()
        }
    }

    private fun registerProcessLifecycleObserver() {
        processLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                runCatching { CustomerIO.instance().track("Application Foregrounded") }.onFailure { analytics.track("Application Foregrounded") }
            }
        })
    }
}
