package io.customer.datapipelines.migration

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putInContextUnderKey

/**
 * Plugin responsible for adding device context extras to the events.
 * This is because the device context extras cannot be added directly to the events
 * and need to be added at plugin level.
 */
class TrackingMigrationPlugin : Plugin {
    override val type: Plugin.Type = Plugin.Type.Before
    override lateinit var analytics: Analytics

    private val deviceContextExtras: MutableMap<BaseEvent, Map<String, String>> = mutableMapOf()

    fun addDeviceContextExtras(event: BaseEvent, extras: Map<String, String>) {
        deviceContextExtras[event] = extras
    }

    override fun execute(event: BaseEvent): BaseEvent {
        deviceContextExtras[event]?.forEach { (key, value) ->
            event.putInContextUnderKey("device", key, value)

            // Remove extras from the map after adding them to event to avoid keeping
            // them in memory unnecessarily
            deviceContextExtras.remove(event)
        }

        return event
    }
}
