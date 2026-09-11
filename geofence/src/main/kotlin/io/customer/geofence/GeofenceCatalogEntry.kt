package io.customer.geofence

import io.customer.geofence.polygon.PolygonCoordinate

/**
 * One fetched record as the server described it, built before validation decides whether it can be
 * monitored.
 *
 * Deliberately not the mapped region: a malformed record is the one a capture most needs named, and
 * it never becomes a region. Fields are nullable because this is the wire's account of a fence
 * rather than a usable one, so a row carries whatever survived and omits the rest.
 */
internal data class GeofenceCatalogEntry(
    val id: String,
    val name: String?,
    val geosetIds: List<String>,
    /** The shape the server claimed, which for an unsupported value is that value verbatim. */
    val shape: String,
    val latitude: Double?,
    val longitude: Double?,
    /** The backend's own radius — for a polygon its enclosing circle, never the padded one. */
    val radiusMeters: Double?,
    /** Canonical ring, or null when the ring does not build; absent beats a ring we cannot trust. */
    val vertices: List<PolygonCoordinate>?,
    val transitionTypes: List<String>
)
