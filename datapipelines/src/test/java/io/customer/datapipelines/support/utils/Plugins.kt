package io.customer.datapipelines.support.utils

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger

class OutputReaderPlugin : Plugin {
    override val type: Plugin.Type = Plugin.Type.After
    override lateinit var analytics: Analytics

    private val logger: Logger = SDKComponent.logger
    val allEvents: MutableList<BaseEvent> = mutableListOf()

    override fun execute(event: BaseEvent): BaseEvent {
        allEvents.add(event)
        logger.debug("[OutputReaderPlugin] Event type: ${event.type}, Event name: ${(event as? TrackEvent)?.event}")
        return event
    }

    fun reset() {
        allEvents.clear()
    }
}

val OutputReaderPlugin.lastEvent: BaseEvent?
    get() = allEvents.lastOrNull()

val OutputReaderPlugin.identifyEvents: List<IdentifyEvent>
    get() = allEvents.filterIsInstance<IdentifyEvent>()
val OutputReaderPlugin.screenEvents: List<ScreenEvent>
    get() = allEvents.filterIsInstance<ScreenEvent>()
val OutputReaderPlugin.trackEvents: List<TrackEvent>
    get() = allEvents.filterIsInstance<TrackEvent>()
