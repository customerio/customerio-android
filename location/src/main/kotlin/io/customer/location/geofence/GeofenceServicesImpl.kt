package io.customer.location.geofence

import io.customer.sdk.core.util.Logger

/**
 * Implementation of [GeofenceServices] that manages geofence regions
 * and coordinates with the Android geofencing system.
 */
internal class GeofenceServicesImpl(
    private val isEnabled: Boolean,
    private val geofenceManager: GeofenceManager,
    private val preferenceStore: GeofencePreferenceStore,
    private val logger: Logger
) : GeofenceServices {

    companion object {
        /**
         * Maximum number of geofences allowed by Android per app.
         */
        private const val MAX_GEOFENCES = 100
    }

    /**
     * In-memory cache of active geofences.
     */
    @Volatile
    private var activeGeofences: MutableMap<String, GeofenceRegion> = mutableMapOf()

    init {
        if (isEnabled) {
            // Restore persisted geofences on initialization
            restoreGeofences()
        }
    }

    override fun addGeofences(regions: List<GeofenceRegion>) {
        if (!isEnabled) {
            logger.error("Cannot add geofences: location tracking is disabled")
            return
        }

        if (regions.isEmpty()) {
            logger.debug("No geofences to add")
            return
        }

        // Filter out geofences with duplicate lat/long coordinates
        val regionsToAdd = mutableListOf<GeofenceRegion>()
        regions.forEach { region ->
            // Check if we're updating an existing geofence with the same ID
            val existingWithSameId = activeGeofences[region.id]
            if (existingWithSameId != null) {
                // Updating existing geofence - always allowed
                regionsToAdd.add(region)
                return@forEach
            }

            // Check if another geofence exists at the exact same location
            val duplicateLocation = activeGeofences.values.find { existing ->
                existing.id != region.id &&
                    existing.latitude == region.latitude &&
                    existing.longitude == region.longitude
            }

            if (duplicateLocation != null) {
                logger.error(
                    "Geofence '${region.id}' has duplicate coordinates (${region.latitude}, ${region.longitude}) " +
                        "as existing geofence '${duplicateLocation.id}'. Skipping to prevent duplicate location monitoring."
                )
            } else {
                regionsToAdd.add(region)
            }
        }

        if (regionsToAdd.isEmpty()) {
            logger.debug("No valid geofences to add after filtering duplicates")
            return
        }

        // Update in-memory cache, replacing duplicates by ID
        regionsToAdd.forEach { region ->
            activeGeofences[region.id] = region
        }

        // Enforce Android's 100 geofence limit by removing oldest if needed
        if (activeGeofences.size > MAX_GEOFENCES) {
            val toRemove = activeGeofences.keys.take(activeGeofences.size - MAX_GEOFENCES)
            logger.debug("Exceeded max geofences ($MAX_GEOFENCES), removing oldest: $toRemove")
            toRemove.forEach { activeGeofences.remove(it) }
        }

        val allRegions = activeGeofences.values.toList()

        // Persist updated geofences
        preferenceStore.saveGeofences(allRegions)

        // Register with Android geofencing system
        geofenceManager.addGeofences(regionsToAdd) { success ->
            if (!success) {
                logger.error("Failed to register geofences with Android system")
            }
        }

        logger.debug("Added ${regionsToAdd.size} geofences. Total active: ${activeGeofences.size}")
    }

    override fun removeGeofences(ids: List<String>) {
        if (ids.isEmpty()) {
            logger.debug("No geofences to remove")
            return
        }

        // Remove from in-memory cache
        ids.forEach { activeGeofences.remove(it) }

        // Persist updated geofences
        preferenceStore.saveGeofences(activeGeofences.values.toList())

        // Remove from Android geofencing system
        geofenceManager.removeGeofences(ids) { success ->
            if (!success) {
                logger.error("Failed to remove geofences from Android system")
            }
        }

        logger.debug("Removed ${ids.size} geofences. Total active: ${activeGeofences.size}")
    }

    override fun removeAllGeofences() {
        activeGeofences.clear()
        preferenceStore.clearGeofences()

        geofenceManager.removeAllGeofences { success ->
            if (!success) {
                logger.error("Failed to remove all geofences from Android system")
            }
        }

        logger.debug("Removed all geofences")
    }

    override fun getActiveGeofences(): List<GeofenceRegion> {
        return activeGeofences.values.toList()
    }

    /**
     * Restores geofences from persistent storage and re-registers them
     * with the Android geofencing system.
     */
    private fun restoreGeofences() {
        val restoredRegions = preferenceStore.loadGeofences()
        if (restoredRegions.isEmpty()) {
            logger.debug("No geofences to restore")
            return
        }

        activeGeofences = restoredRegions.associateBy { it.id }.toMutableMap()

        // Re-register with Android system
        geofenceManager.addGeofences(restoredRegions) { success ->
            if (success) {
                logger.debug("Restored ${restoredRegions.size} geofences")
            } else {
                logger.error("Failed to restore geofences with Android system")
            }
        }
    }

    /**
     * Returns the geofence region for a given ID, if it exists.
     */
    fun getGeofenceById(id: String): GeofenceRegion? {
        return activeGeofences[id]
    }
}
