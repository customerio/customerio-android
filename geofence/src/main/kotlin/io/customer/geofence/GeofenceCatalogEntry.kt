package io.customer.geofence

import io.customer.geofence.polygon.PolygonCoordinate

/**
 * One fetched record as the server described it, built before validation. Not the mapped region,
 * because a malformed record never becomes one and is the one a capture most needs named; fields
 * are nullable so a row carries whatever survived.
 */
internal data class GeofenceCatalogEntry(
    val id: String,
    val name: String?,
    val geosetIds: List<String>,
    /** The shape the server claimed, which for an unsupported value is that value verbatim. */
    val shape: String,
    val latitude: Double?,
    val longitude: Double?,
    /** The backend's radius; for a polygon, its enclosing circle's. */
    val radiusMeters: Double?,
    /** Canonical ring, or null when the ring does not build; absent beats a ring we cannot trust. */
    val vertices: List<PolygonCoordinate>?,
    val transitionTypes: List<String>
)
