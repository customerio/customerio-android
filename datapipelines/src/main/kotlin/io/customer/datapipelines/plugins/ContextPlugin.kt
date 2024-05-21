package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putInContextUnderKey
import io.customer.sdk.data.model.CustomAttributes

/**
 * Plugin class responsible for updating the context properties in events
 * tracked by Customer.io SDK.
 */
class ContextPlugin(override var analytics: Analytics) : Plugin {
    override val type: Plugin.Type = Plugin.Type.Before

    internal var deviceToken: String? = null
        private set
    internal var attributes: CustomAttributes = emptyMap()
        private set

    fun updateDeviceProperties(deviceToken: String?, attributes: CustomAttributes) {
        this.deviceToken = deviceToken
        this.attributes = attributes
    }

    override fun execute(event: BaseEvent): BaseEvent {
        deviceToken?.let { token ->
            // Device token is expected to be attached to device in context
            event.putInContextUnderKey("device", "token", token)
        }
        return event
    }
}
