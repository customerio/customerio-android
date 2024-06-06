package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent

/**
 * Plugin to publish events to the [EventBus] when an event is triggered.
 */
class DataPipelinePublishedEvents : EventPlugin {

    override lateinit var analytics: Analytics
    override val type: Plugin.Type = Plugin.Type.Before

    private val eventBus = SDKComponent.eventBus

    override fun identify(payload: IdentifyEvent): BaseEvent? {
        eventBus.publish(Event.ProfileIdentifiedEvent(identifier = payload.userId))
        return super.identify(payload)
    }

    override fun screen(payload: ScreenEvent): BaseEvent? {
        eventBus.publish(Event.ScreenViewedEvent(name = payload.name))
        return super.screen(payload)
    }

    override fun reset() {
        eventBus.publish(Event.ResetEvent)
        super.reset()
    }
}
