package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putInContext
import com.segment.analytics.kotlin.core.utilities.putInContextUnderKey
import com.segment.analytics.kotlin.core.utilities.removeFromContext
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.DeviceTokenManager

/**
 * Plugin class responsible for updating the context properties in events
 * tracked by Customer.io SDK.
 */
internal class ContextPlugin(
    private val deviceStore: DeviceStore,
    private val deviceTokenManager: DeviceTokenManager,
    private val eventProcessor: ContextPluginEventProcessor = DefaultContextPluginEventProcessor()
) : Plugin {
    override val type: Plugin.Type = Plugin.Type.Before
    override lateinit var analytics: Analytics

    /**
     * Current device token. Delegates to DeviceTokenManager for single source of truth.
     */
    internal var deviceToken: String?
        get() = deviceTokenManager.deviceToken
        set(value) = deviceTokenManager.setDeviceToken(value)

    override fun execute(event: BaseEvent): BaseEvent {
        return eventProcessor.execute(event, deviceStore) { deviceToken }
    }
}

/**
 * Interface to handle the processing of events inside [ContextPlugin].
 * Allows custom logic to be injected for testing or extension.
 */
internal interface ContextPluginEventProcessor {
    fun execute(event: BaseEvent, deviceStore: DeviceStore, deviceTokenProvider: () -> String?): BaseEvent
}

/**
 * Default implementation of [ContextPluginEventProcessor] that sets the user agent
 * in the context and ensures the device token is added if not already present.
 */
internal class DefaultContextPluginEventProcessor : ContextPluginEventProcessor {
    override fun execute(event: BaseEvent, deviceStore: DeviceStore, deviceTokenProvider: () -> String?): BaseEvent {
        // Set user agent in context as it is required by Customer.io Data Pipelines
        event.putInContext("userAgent", deviceStore.buildUserAgent())
        // Remove analytics library information from context as Customer.io
        // SDK information is being sent through user-agent
        event.removeFromContext("library")

        // In case of migration from older versions, the token might already be present in context
        // We need to ensure that the token is not overridden to avoid corruption of data
        // So we add current token to context only if context does not have any token already
        event.findInContextAtPath("device.token").firstOrNull()?.content ?: deviceTokenProvider()?.let { token ->
            // Device token is expected to be attached to device in context
            event.putInContextUnderKey("device", "token", token)
        }

        return event
    }
}
