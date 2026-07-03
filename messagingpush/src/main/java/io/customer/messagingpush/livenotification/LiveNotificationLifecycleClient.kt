package io.customer.messagingpush.livenotification

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.pipeline.DataPipeline

/**
 * Reports live-notification lifecycle to Customer.io as CDP track events.
 *
 * Replaces the former direct REST client: every edge operation is expressed as
 * one of two track events — [EVENT_LIVE_NOTIFICATION] (start/update/end) and
 * [EVENT_LIVE_NOTIFICATION_TOKEN] (push-to-start registration) — with the
 * contract fields carried under the event's `properties` (the mobile SDKs can
 * only attach custom data there; the edge reads them from `properties`). The
 * data pipeline owns batching, retry and flush, so this type is a thin mapper.
 *
 * Live notifications require an identified user: events emitted while anonymous
 * are dropped (logged at debug). Android has no per-instance push token like
 * iOS, so the instance-token registration flow is intentionally absent; the
 * FCM token travels as `deviceId` on the lifecycle events instead, and doubles
 * as the `pushToStartToken` on registration.
 */
internal interface LiveNotificationLifecycleClient {
    /**
     * `start` edge op: a live activity was started locally on this device.
     * Carries the static [attributes] and dynamic [contentState] as separate
     * event properties (matching iOS's ActivityKit envelope).
     */
    fun reportStart(
        instanceUUID: String,
        activityType: String,
        deviceId: String,
        attributes: Map<String, Any?>,
        contentState: Map<String, Any?>
    )

    /**
     * `update` edge op: the activity's content changed — either an `update` push
     * arrived from the server or the host app called `updateLiveNotification`.
     * Carries only the new dynamic content as [contentState] (attributes are
     * static and set at start).
     */
    fun reportUpdate(instanceUUID: String, activityType: String, deviceId: String, contentState: Map<String, Any?>)

    /**
     * `end` edge op: the user dismissed the live notification. May carry a final
     * dynamic [contentState] (the backend supports an end content-state); omitted
     * when empty.
     */
    fun reportEnd(
        instanceUUID: String,
        activityType: String,
        deviceId: String,
        contentState: Map<String, Any?> = emptyMap()
    )

    /**
     * `register_push_to_start` edge op. On Android the FCM token is sent as both
     * [PROP_DEVICE_ID] and [PROP_PUSH_TO_START_TOKEN]. Returns true if the event
     * was emitted (used by the registrar to decide whether to mark the type as
     * registered).
     */
    fun registerPushToStart(activityType: String, deviceId: String): Boolean
}

@OptIn(InternalCustomerIOApi::class)
internal class LiveNotificationLifecycleClientImpl(
    private val dataPipelineProvider: () -> DataPipeline? = { SDKComponent.getOrNull<DataPipeline>() }
) : LiveNotificationLifecycleClient {

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
                put(PROP_INSTANCE_UUID, instanceUUID)
                put(PROP_DEVICE_ID, deviceId)
                put(PROP_PLATFORM, PLATFORM_ANDROID)
                put(PROP_NOTIFICATION_TYPE, activityType)
                // Start carries both the static `attributes` and dynamic `contentState`;
                // both optional per the contract (omitted when empty).
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
                put(PROP_INSTANCE_UUID, instanceUUID)
                put(PROP_DEVICE_ID, deviceId)
                put(PROP_PLATFORM, PLATFORM_ANDROID)
                put(PROP_NOTIFICATION_TYPE, activityType)
                // Update carries only the new dynamic `contentState`; optional per the contract.
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
                put(PROP_INSTANCE_UUID, instanceUUID)
                put(PROP_DEVICE_ID, deviceId)
                put(PROP_PLATFORM, PLATFORM_ANDROID)
                put(PROP_NOTIFICATION_TYPE, activityType)
                // End may carry an optional final dynamic `contentState`.
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
            )
        )

    /** Emits [event]; returns true if it was sent (pipeline ready + user identified). */
    private fun track(event: String, properties: Map<String, Any?>): Boolean {
        val pipeline = dataPipelineProvider()
        if (pipeline == null) {
            SDKComponent.logger.debug("Data pipeline unavailable; dropping live notification event '$event'.")
            return false
        }
        if (!pipeline.isUserIdentified) {
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
        const val PROP_INSTANCE_UUID = "instanceUUID"
        const val PROP_DEVICE_ID = "deviceId"
        const val PROP_PLATFORM = "platform"

        // Wire key is `notificationType` (the CDP/edge "live activity → live notification"
        // rename); the Android-side parameters keep the `activityType` domain name.
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
