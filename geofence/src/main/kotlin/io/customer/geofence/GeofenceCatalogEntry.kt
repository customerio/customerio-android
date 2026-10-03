package io.customer.geofence

import io.customer.geofence.polygon.PolygonCoordinate

/**
 * A fetched record as the server sent it, built before validation because a malformed record never
 * becomes a mapped region. Fields are nullable so a row carries whatever survived.
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
    /** Canonical ring, or null when it does not build; never a ring we cannot trust. */
    val vertices: List<PolygonCoordinate>?,
    val transitionTypes: List<String>
)
