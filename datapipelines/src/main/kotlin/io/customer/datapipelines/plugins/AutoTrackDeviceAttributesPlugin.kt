package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putAll
import com.segment.analytics.kotlin.core.utilities.toJsonElement
import io.customer.datapipelines.extensions.sanitizeForJson
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.util.EventNames
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Plugin class responsible for updating the device attributes in device update events.
 */
class AutoTrackDeviceAttributesPlugin : Plugin {
    override val type: Plugin.Type = Plugin.Type.Before
    override lateinit var analytics: Analytics

    private val deviceStore by lazy { SDKComponent.android().deviceStore }

    override fun execute(event: BaseEvent): BaseEvent {
        if ((event as? TrackEvent)?.event != EventNames.DEVICE_UPDATE) {
            return event
        }

        // Extract device attributes from context and merge them into event
        // properties so they can be reflected on Dashboard

        val context = event.context
        val properties = event.properties

        event.properties = buildJsonObject {
            // Collect system-service-backed attributes on the analytics worker, not the SDK caller.
            putAll(deviceStore.buildDeviceAttributes().sanitizeForJson().toJsonElement().jsonObject)
            // Explicit custom attributes take precedence over the collected defaults.
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
