package io.customer.location.type

import java.util.Date

/**
 * Framework-agnostic location details. Built from system location inside the provider.
 * Decouples modules from Android's [android.location.Location] class.
 */
internal data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Date,
    /** Radius of 68% confidence in metres, or null when the source reported none. */
    val horizontalAccuracy: Double?,
    val altitude: Double? = null,
    val fixElapsedRealtimeMillis: Long? = null
)
