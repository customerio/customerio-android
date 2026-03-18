package io.customer.location.geofence

import android.content.Context
import android.content.SharedPreferences
import io.customer.location.geofence.GeofenceConstants.PREF_KEY_GEOFENCES
import io.customer.sdk.core.util.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles persistence of geofence regions to SharedPreferences.
 */
internal class GeofencePreferenceStore(
    context: Context,
    private val logger: Logger
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "io.customer.location.geofence.prefs",
        Context.MODE_PRIVATE
    )

    /**
     * Saves geofence regions to persistent storage.
     */
    fun saveGeofences(regions: List<GeofenceRegion>) {
        try {
            val jsonArray = JSONArray()
            regions.forEach { region ->
                val jsonObject = JSONObject().apply {
                    put("id", region.id)
                    put("latitude", region.latitude)
                    put("longitude", region.longitude)
                    put("radius", region.radius)
                    put("dwellTimeMs", region.dwellTimeMs)
                    region.name?.let { put("name", it) }
                    region.customData?.let { data ->
                        put("customData", JSONObject(data))
                    }
                }
                jsonArray.put(jsonObject)
            }
            prefs.edit().putString(PREF_KEY_GEOFENCES, jsonArray.toString()).apply()
            logger.debug("Saved ${regions.size} geofences to storage")
        } catch (e: Exception) {
            logger.error("Failed to save geofences: ${e.message}")
        }
    }

    /**
     * Loads geofence regions from persistent storage.
     */
    fun loadGeofences(): List<GeofenceRegion> {
        try {
            val jsonString = prefs.getString(PREF_KEY_GEOFENCES, null) ?: return emptyList()
            val jsonArray = JSONArray(jsonString)
            val regions = mutableListOf<GeofenceRegion>()

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val customData = if (jsonObject.has("customData")) {
                    val customDataJson = jsonObject.getJSONObject("customData")
                    customDataJson.keys().asSequence().associateWith { key ->
                        customDataJson.get(key)
                    }
                } else {
                    null
                }

                val region = GeofenceRegion(
                    id = jsonObject.getString("id"),
                    latitude = jsonObject.getDouble("latitude"),
                    longitude = jsonObject.getDouble("longitude"),
                    radius = jsonObject.getDouble("radius"),
                    name = jsonObject.optString("name").takeIf { it.isNotEmpty() },
                    customData = customData,
                    dwellTimeMs = jsonObject.getLong("dwellTimeMs")
                )
                regions.add(region)
            }

            logger.debug("Loaded ${regions.size} geofences from storage")
            return regions
        } catch (e: Exception) {
            logger.error("Failed to load geofences: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Clears all saved geofences from storage.
     */
    fun clearGeofences() {
        prefs.edit().remove(PREF_KEY_GEOFENCES).apply()
        logger.debug("Cleared all geofences from storage")
    }
}
