package io.customer.messagingpush.livenotification

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.pipeline.DataPipeline

/**
 * Reports live-notification lifecycle to Customer.io as CDP track events.
 */
internal interface LiveNotificationLifecycleClient {
    /** Reports a `start` event with the static [attributes] and dynamic [contentState]. */
    fun reportStart(
        instanceUUID: String,
        activityType: String,
        deviceId: String,
        attributes: Map<String, Any?>,
        contentState: Map<String, Any?>
    )

    /** Reports an `update` event carrying the new dynamic [contentState]. */
    fun reportUpdate(instanceUUID: String, activityType: String, deviceId: String, contentState: Map<String, Any?>)

    /** Reports an `end` event, optionally carrying a final dynamic [contentState]. */
    fun reportEnd(
        instanceUUID: String,
        activityType: String,
        deviceId: String,
        contentState: Map<String, Any?> = emptyMap()
    )

    /** Reports a `register_push_to_start` event; returns true if it was emitted. */
    fun registerPushToStart(activityType: String, deviceId: String): Boolean

    /**
     * Updates the cached identified-user state used to gate lifecycle events. Fed
     * synchronously from `Event.UserChangedEvent` (via [LiveNotificationRegistrar])
     * rather than the pipeline's own flag, which lags a synchronous `identify()` and
     * would otherwise drop the first start/update/end right after login.
     */
    fun setIdentified(identified: Boolean)
}

@OptIn(InternalCustomerIOApi::class)
internal class LiveNotificationLifecycleClientImpl(
    private val dataPipelineProvider: () -> DataPipeline? = { SDKComponent.getOrNull<DataPipeline>() }
) : LiveNotificationLifecycleClient {

    // Identified-user state, fed synchronously from Event.UserChangedEvent via the
    // registrar. Used instead of pipeline.isUserIdentified, which lags a synchronous
    // identify() and would drop the first lifecycle event right after login.
    @Volatile
    private var isIdentified: Boolean = false

    override fun setIdentified(identified: Boolean) {
        isIdentified = identified
    }

    override fun reportStart(
        instanceUUID: String,
        activityType: String,
        deviceId: String,
        attributes: Map<String, Any?>,
        contentState: Map<String, Any?>
    ) {
        track(
            event = EVENT_LIVE_NOTIFICATION,
            properties = buildMap {
                put(PROP_EVENT_TYPE, EVENT_TYPE_START)
                put(PROP_CIO_INSTANCE_ID, instanceUUID)
                put(PROP_DEVICE_ID, deviceId)
                put(PROP_PLATFORM, PLATFORM_ANDROID)
                put(PROP_NOTIFICATION_TYPE, activityType)
                if (attributes.isNotEmpty()) put(PROP_ATTRIBUTES, attributes)
                if (contentState.isNotEmpty()) put(PROP_CONTENT_STATE, contentState)
            }
        )
    }

    override fun reportUpdate(
        instanceUUID: String,
        activityType: String,
        deviceId: String,
        contentState: Map<String, Any?>
    ) {
        track(
            event = EVENT_LIVE_NOTIFICATION,
            properties = buildMap {
                put(PROP_EVENT_TYPE, EVENT_TYPE_UPDATE)
                put(PROP_CIO_INSTANCE_ID, instanceUUID)
                put(PROP_DEVICE_ID, deviceId)
                put(PROP_PLATFORM, PLATFORM_ANDROID)
                put(PROP_NOTIFICATION_TYPE, activityType)
                if (contentState.isNotEmpty()) put(PROP_CONTENT_STATE, contentState)
            }
        )
    }

    override fun reportEnd(
        instanceUUID: String,
        activityType: String,
        deviceId: String,
        contentState: Map<String, Any?>
    ) {
        track(
            event = EVENT_LIVE_NOTIFICATION,
            properties = buildMap {
                put(PROP_EVENT_TYPE, EVENT_TYPE_END)
                put(PROP_CIO_INSTANCE_ID, instanceUUID)
                put(PROP_DEVICE_ID, deviceId)
                put(PROP_PLATFORM, PLATFORM_ANDROID)
                put(PROP_NOTIFICATION_TYPE, activityType)
                if (contentState.isNotEmpty()) put(PROP_CONTENT_STATE, contentState)
            }
        )
    }

    override fun registerPushToStart(activityType: String, deviceId: String): Boolean =
        track(
            event = EVENT_LIVE_NOTIFICATION_TOKEN,
            properties = mapOf(
                PROP_REGISTRATION_TYPE to REGISTRATION_TYPE_PUSH_TO_START,
                PROP_NOTIFICATION_TYPE to activityType,
                PROP_PLATFORM to PLATFORM_ANDROID,
                PROP_DEVICE_ID to deviceId,
                PROP_PUSH_TO_START_TOKEN to deviceId
            ),
            // Identity is gated upstream by LiveNotificationRegistrar for registration.
            requireIdentifiedUser = false
        )

    /**
     * Emits [event]; returns true if it was sent. Requires a ready pipeline and,
     * unless [requireIdentifiedUser] is false, an identified user.
     */
    private fun track(event: String, properties: Map<String, Any?>, requireIdentifiedUser: Boolean = true): Boolean {
        val pipeline = dataPipelineProvider()
        if (pipeline == null) {
            SDKComponent.logger.debug("Data pipeline unavailable; dropping live notification event '$event'.")
            return false
        }
        if (requireIdentifiedUser && !isIdentified) {
            SDKComponent.logger.debug("Live notifications require an identified user; dropping event '$event'.")
            return false
        }
        pipeline.track(event, properties)
        return true
    }

    companion object {
        const val EVENT_LIVE_NOTIFICATION = "Live Notification Event"
        const val EVENT_LIVE_NOTIFICATION_TOKEN = "Live Notification Token"

        const val PROP_EVENT_TYPE = "eventType"
        const val PROP_REGISTRATION_TYPE = "registrationType"
        const val PROP_CIO_INSTANCE_ID = "cioInstanceId"
        const val PROP_DEVICE_ID = "deviceId"
        const val PROP_PLATFORM = "platform"

        const val PROP_NOTIFICATION_TYPE = "notificationType"
        const val PROP_ATTRIBUTES = "attributes"
        const val PROP_CONTENT_STATE = "contentState"
        const val PROP_PUSH_TO_START_TOKEN = "pushToStartToken"

        const val EVENT_TYPE_START = "start"
        const val EVENT_TYPE_UPDATE = "update"
        const val EVENT_TYPE_END = "end"
        const val REGISTRATION_TYPE_PUSH_TO_START = "push_to_start"
        const val PLATFORM_ANDROID = "android"
    }
}
