package io.customer.sdk.communication

import java.util.Date
import java.util.UUID

sealed class Event {
    val storageId: String = UUID.randomUUID().toString()
    val params: Map<String, String> = emptyMap()
    val timestamp: Date = Date()

    data class ProfileIdentifiedEvent(
        val identifier: String
    ) : Event()

    data class ScreenViewedEvent(
        val name: String
    ) : Event()

    object ResetEvent : Event()

    data class TrackPushMetricEvent(
        val deliveryId: String,
        val event: String,
        val deviceToken: String
    ) : Event()

    data class TrackInAppMetricEvent(
        val deliveryID: String,
        val event: String
    ) : Event()

    data class RegisterDeviceTokenEvent(
        val token: String
    ) : Event()

    class DeleteDeviceTokenEvent : Event()
}
