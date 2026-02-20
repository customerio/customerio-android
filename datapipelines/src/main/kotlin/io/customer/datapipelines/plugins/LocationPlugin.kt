package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putInContext
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Logger
import kotlinx.serialization.json.JsonPrimitive

/**
 * Plugin that enriches identify events with the last known location in context,
 * so Customer.io knows where the user is when their profile is identified.
 */
internal class LocationPlugin(private val logger: Logger) : EventPlugin {
    override val type: Plugin.Type = Plugin.Type.Enrichment
    override lateinit var analytics: Analytics

    @Volatile
    internal var lastLocation: Event.LocationData? = null

    override fun identify(payload: IdentifyEvent): BaseEvent {
        val location = lastLocation ?: return payload
        payload.putInContext("location_latitude", JsonPrimitive(location.latitude))
        payload.putInContext("location_longitude", JsonPrimitive(location.longitude))
        return payload
    }
}
