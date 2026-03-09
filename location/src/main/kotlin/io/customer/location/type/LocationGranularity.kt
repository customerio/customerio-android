package io.customer.location.type

/**
 * Semantic accuracy for location requests.
 * Mapped internally to system accuracy settings (e.g. FusedLocationProviderClient priority).
 */
internal enum class LocationGranularity {
    /**
     * Reduced precision (city or timezone level).
     * Maps to [com.google.android.gms.location.Priority.PRIORITY_LOW_POWER] (~10 km accuracy).
     */
    COARSE_CITY_OR_TIMEZONE;

    companion object {
        val DEFAULT: LocationGranularity = COARSE_CITY_OR_TIMEZONE
    }
}
