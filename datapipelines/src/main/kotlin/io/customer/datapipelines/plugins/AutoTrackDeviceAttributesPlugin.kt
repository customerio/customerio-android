package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Plugin class responsible for updating the device attributes in device update events.
 */
class AutoTrackDeviceAttributesPlugin : Plugin {
    override val type: Plugin.Type = Plugin.Type.Before
    override lateinit var analytics: Analytics

    override fun execute(event: BaseEvent): BaseEvent {
        if ((event as? TrackEvent)?.event != "Device Created or Updated") {
            return event
        }

        // Extract device attributes from context and merge them into event
        // properties so they can be reflected on Dashboard

        val context = event.context
        val properties = event.properties

        event.properties = buildJsonObject {
            putAll(properties)
            context["network"]?.jsonObject?.let { network ->
                network["bluetooth"]?.let { value -> put("network_bluetooth", value) }
                network["cellular"]?.let { value -> put("network_cellular", value) }
                network["wifi"]?.let { value -> put("network_wifi", value) }
            }
            context["screen"]?.jsonObject?.let { screen ->
                screen["width"]?.let { value -> put("screen_width", value) }
                screen["height"]?.let { value -> put("screen_height", value) }
            }
            context["ip"]?.let { value -> put("ip", value) }
            context["timezone"]?.let { value -> put("timezone", value) }
        }

        return event
    }
}
