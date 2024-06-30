package io.customer.datapipelines.testutils.utils

import com.segment.analytics.kotlin.core.AliasEvent
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.GroupEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin

/**
 * A test plugin that can be used to test if the plugin was run
 */
class TestRunPlugin(var closure: (BaseEvent?) -> Unit) : EventPlugin {
    override val type: Plugin.Type = Plugin.Type.Before
    override lateinit var analytics: Analytics
    var ran = false

    override fun reset() {
        ran = false
    }

    fun updateState(ran: Boolean) {
        this.ran = ran
    }

    override fun execute(event: BaseEvent): BaseEvent {
        super.execute(event)
        updateState(true)
        return event
    }

    override fun track(payload: TrackEvent): BaseEvent {
        closure(payload)
        updateState(true)
        return payload
    }

    override fun identify(payload: IdentifyEvent): BaseEvent {
        closure(payload)
        updateState(true)
        return payload
    }

    override fun screen(payload: ScreenEvent): BaseEvent {
        closure(payload)
        updateState(true)
        return payload
    }

    override fun group(payload: GroupEvent): BaseEvent {
        closure(payload)
        updateState(true)
        return payload
    }

    override fun alias(payload: AliasEvent): BaseEvent {
        closure(payload)
        updateState(true)
        return payload
    }
}
