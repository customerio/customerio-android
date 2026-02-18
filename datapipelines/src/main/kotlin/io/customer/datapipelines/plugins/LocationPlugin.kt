package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putInContext
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Logger
import io.customer.sdk.util.EventNames
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Plugin that handles location-related event processing:
 *
 * 1. Enriches identify events with the last known location in context,
 *    so Customer.io knows where the user is when their profile is identified.
 *
 * 2. Blocks consumer-sent "Location Update" track events to prevent flooding
 *    the backend. Only SDK-internal location events (marked with [INTERNAL_LOCATION_KEY])
 *    are allowed through; the marker is stripped before the event reaches the destination.
 */
internal class LocationPlugin(private val logger: Logger) : EventPlugin {
    override val type: Plugin.Type = Plugin.Type.Enrichment
    override lateinit var analytics: Analytics

    @Volatile
    internal var lastLocation: Event.LocationData? = null

    override fun identify(payload: IdentifyEvent): BaseEvent {
        val location = lastLocation ?: return payload
        payload.putInContext(
            "location",
            buildJsonObject {
                put("latitude", JsonPrimitive(location.latitude))
                put("longitude", JsonPrimitive(location.longitude))
            }
        )
        return payload
    }

    override fun track(payload: TrackEvent): BaseEvent? {
        if (payload.event != EventNames.LOCATION_UPDATE) {
            return payload
        }

        // Check for the internal marker that only the SDK sets
        val isInternal = payload.properties[INTERNAL_LOCATION_KEY]?.let {
            (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
        } ?: false

        if (!isInternal) {
            logger.debug("Blocking consumer-sent \"${EventNames.LOCATION_UPDATE}\" event. Location events are managed by the SDK.")
            return null
        }

        // Strip the internal marker before sending to destination
        payload.properties = buildJsonObject {
            payload.properties.forEach { (key, value) ->
                if (key != INTERNAL_LOCATION_KEY) {
                    put(key, value)
                }
            }
        }
        return payload
    }

    companion object {
        internal const val INTERNAL_LOCATION_KEY = "_cio_internal_location"
    }
}
