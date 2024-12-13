package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.datapipelines.config.ScreenView

/**
 * Plugin to filter screen events based on the configuration provided by customer app.
 * This plugin is used to filter out screen events that should not be processed further.
 */
class ScreenFilterPlugin(private val screenViewUse: ScreenView) : EventPlugin {
    override lateinit var analytics: Analytics
    override val type: Plugin.Type = Plugin.Type.Enrichment

    override fun screen(payload: ScreenEvent): BaseEvent? {
        // Filter out screen events based on the configuration provided by customer app
        // Using when expression so it enforce right check for all possible values of ScreenView in future
        return when (screenViewUse) {
            ScreenView.Analytics -> payload
            // Do not send screen events to server if ScreenView is not Analytics
            ScreenView.InApp -> null
        }
    }
}
