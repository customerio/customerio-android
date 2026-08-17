package io.customer.geofence.store

import kotlinx.serialization.Serializable

/** Exact location samples awaiting ordered, encrypted polygon evaluation. */
@Serializable
internal data class PendingPolygonApproachBatch(
    val id: String,
    val userStateGeneration: Long,
    val bootSessionId: String,
    val locations: List<PendingPolygonApproachLocation>
)

@Serializable
internal data class PendingPolygonApproachLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val speed: Float?,
    val timestampMillis: Long,
    val elapsedRealtimeNanos: Long
)
