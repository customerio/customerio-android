package io.customer.sdk.communication

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.events.Metric
import java.util.Date
import java.util.UUID

/**
 * Class to represent an event that can be published and subscribed to using [EventBus].
 */
sealed class Event {
    // Unique identifier for the event
    open val storageId: String = UUID.randomUUID().toString()

    // Additional metadata associated with the event
    open val params: Map<String, String> = emptyMap()

    // Timestamp of the event
    open val timestamp: Date = Date()

    /**
     * Event published when user identity changes (identify or clearIdentify).
     *
     * @param userId The user ID if identified, null if anonymous
     * @param anonymousId The anonymous ID (always present)
     */
    data class UserChangedEvent(
        val userId: String?,
        val anonymousId: String
    ) : Event()

    data class ScreenViewedEvent(
        val name: String
    ) : Event()

    object ResetEvent : Event()

    /** Published after all configured SDK modules have finished initializing. */
    @InternalCustomerIOApi
    object SdkInitializedEvent : Event()

    data class TrackPushMetricEvent(
        val deliveryId: String,
        val event: Metric,
        val deviceToken: String
    ) : Event()

    data class TrackInAppMetricEvent(
        val deliveryID: String,
        val event: Metric,
        override val params: Map<String, String> = emptyMap()
    ) : Event()

    data class RegisterDeviceTokenEvent(
        val token: String
    ) : Event()

    /**
     * Published by the location module on every fresh location fix. Other modules
     * subscribe to react to location updates without depending on its internals.
     */
    data class LocationAcquired(
        val latitude: Double,
        val longitude: Double
    ) : Event()

    /**
     * The same fix as [LocationAcquired], plus when it was taken and how accurate it was, for
     * subscribers that judge geometry. [fixElapsedRealtimeMillis] is on the boot clock. For a host
     * fix stamped only on wall time it is an estimate that a clock step can shift, so such a fix
     * carries no [horizontalAccuracyMeters] and cannot prove the device outside a fence. Null means
     * the source reported no time.
     * [horizontalAccuracyMeters] is the radius of 68% confidence; null means unavailable for geometry
     * proof, never 0 standing in for "unset".
     *
     * The three-property constructor, `copy` and `copy$default` let older modules link against
     * upgraded core. That constructor sets no accuracy; either copy keeps an existing accuracy.
     */
    @InternalCustomerIOApi
    data class LocationFixAcquired(
        val latitude: Double,
        val longitude: Double,
        val fixElapsedRealtimeMillis: Long?,
        val horizontalAccuracyMeters: Float?
    ) : Event() {
        constructor(
            latitude: Double,
            longitude: Double,
            fixElapsedRealtimeMillis: Long? = null
        ) : this(latitude, longitude, fixElapsedRealtimeMillis, null)

        /**
         * The earlier shape's copy. Without defaults, so a call that omits an argument binds to the
         * generated four-property copy; either way the accuracy is kept.
         */
        fun copy(
            latitude: Double,
            longitude: Double,
            fixElapsedRealtimeMillis: Long?
        ): LocationFixAcquired = copy(latitude, longitude, fixElapsedRealtimeMillis, horizontalAccuracyMeters)

        @InternalCustomerIOApi
        companion object {
            /**
             * What a module compiled against the earlier shape calls for a `copy` that omits
             * arguments. Hidden from source; the mask bits match the compiler's for that shape.
             */
            @JvmStatic
            @JvmSynthetic
            @Suppress("FunctionName", "FunctionNaming", "ktlint:standard:function-naming")
            fun `copy$default`(
                source: LocationFixAcquired,
                latitude: Double,
                longitude: Double,
                fixElapsedRealtimeMillis: Long?,
                mask: Int,
                @Suppress("UNUSED_PARAMETER", "UnusedParameter") unused: Any?
            ): LocationFixAcquired = source.copy(
                if (mask and 1 != 0) source.latitude else latitude,
                if (mask and 2 != 0) source.longitude else longitude,
                if (mask and 4 != 0) source.fixElapsedRealtimeMillis else fixElapsedRealtimeMillis
            )
        }
    }

    class DeleteDeviceTokenEvent : Event()

    enum class GeofenceTransition {
        ENTER,
        DWELL,
        EXIT
    }

    /**
     * Event published by the Location module when a geofence transition is received from the OS.
     * Subscribers (e.g. data pipelines) translate this into a tracked event.
     *
     * [userId] is snapshotted at queue time, not the SDK's current identity — non-null means the
     * subscriber must attribute the resulting track event to this userId (not whoever is identified
     * at flush time), so a sign-out + sign-in between queue and delivery cannot reattribute. Null
     * means anonymous at queue time; the subscriber falls back to the pipeline's anonymousId path.
     *
     * [timestamp] is the moment the transition fired (captured at receiver time), not the publish
     * time. Subscribers stamp this onto the outgoing CDP event so a delayed flush still attributes
     * the transition to when it happened, not when it was sent.
     */
    data class GeofenceTransitionEvent(
        val geofenceId: String,
        val transition: GeofenceTransition,
        val properties: Map<String, Any>,
        val userId: String?,
        override val timestamp: Date
    ) : Event()
}
