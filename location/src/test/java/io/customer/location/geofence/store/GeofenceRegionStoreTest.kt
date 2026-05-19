package io.customer.location.geofence.store

import android.content.Context
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.GeofenceConfig
import io.customer.location.geofence.GeofenceJsonSerializer
import io.customer.location.geofence.GeofenceLocation
import io.customer.location.geofence.GeofenceRegion
import io.customer.location.geofence.GeofenceTransitionType
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceRegionStoreTest : RobolectricTest() {

    private lateinit var store: GeofenceRegionStoreImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
            }
        )
        store = GeofenceRegionStoreImpl(applicationMock, GeofenceJsonSerializer())
        store.clearAll()
    }

    // --- Cached regions (full backend response) ---

    @Test
    fun getCachedRegions_givenNothingStored_expectEmpty() {
        store.getCachedRegions().shouldBeEmpty()
    }

    @Test
    fun saveCachedRegions_thenGet_expectRoundTrip() {
        val regions = listOf(
            GeofenceRegion("biz-1", 37.7749, -122.4194, 100f, name = "Coffee"),
            GeofenceRegion(
                id = "biz-2",
                latitude = 51.5074,
                longitude = -0.1278,
                radius = 250f,
                name = "Office",
                transitionTypes = listOf(GeofenceTransitionType.ENTER),
                lastUpdated = 1_700_000_000L
            )
        )

        store.saveCachedRegions(regions)

        store.getCachedRegions() shouldBeEqualTo regions
    }

    @Test
    fun saveCachedRegions_givenSubsequentSave_expectOverwrite() {
        store.saveCachedRegions(listOf(GeofenceRegion("biz-1", 0.0, 0.0, 50f)))
        val replacement = listOf(GeofenceRegion("biz-2", 1.0, 2.0, 75f))

        store.saveCachedRegions(replacement)

        store.getCachedRegions() shouldBeEqualTo replacement
    }

    // --- Registered IDs (subset currently live in OS) ---

    @Test
    fun getRegisteredIds_givenNothingStored_expectEmpty() {
        store.getRegisteredIds().shouldBeEmpty()
    }

    @Test
    fun saveRegisteredIds_thenGet_expectRoundTrip() {
        val ids = setOf("cio_movement_trigger", "biz-1", "biz-2")

        store.saveRegisteredIds(ids)

        store.getRegisteredIds() shouldContainSame ids
    }

    @Test
    fun saveRegisteredIds_givenEmptySet_expectGetReturnsEmpty() {
        store.saveRegisteredIds(setOf("biz-1"))
        store.saveRegisteredIds(emptySet())

        store.getRegisteredIds().shouldBeEmpty()
    }

    // --- Cached config ---

    @Test
    fun getCachedConfig_givenNothingStored_expectNull() {
        store.getCachedConfig().shouldBeNull()
    }

    @Test
    fun saveCachedConfig_thenGet_expectRoundTrip() {
        val config = GeofenceConfig(
            localRefreshTriggerRadius = 1_000f,
            remoteFetchRefreshTriggerRadius = 5_000f,
            remoteFetchRefreshExpiryMs = 86_400_000L,
            duplicateEventsExpiryMs = 3_600_000L,
            maxBusinessGeofences = 19
        )

        store.saveCachedConfig(config)

        store.getCachedConfig() shouldBeEqualTo config
    }

    // --- API anchor location ---

    @Test
    fun getLastApiFetchLocation_givenNothingStored_expectNull() {
        store.getLastApiFetchLocation().shouldBeNull()
    }

    @Test
    fun saveLastApiFetchLocation_thenGet_expectRoundTrip() {
        val location = GeofenceLocation(latitude = 37.7749, longitude = -122.4194)

        store.saveLastApiFetchLocation(location)

        store.getLastApiFetchLocation() shouldBeEqualTo location
    }

    // --- Last-sync timestamp ---

    @Test
    fun lastSyncTimestamp_givenNothingStored_expectNull() {
        store.getLastSyncTimestamp().shouldBeNull()
    }

    @Test
    fun setLastSyncTimestamp_thenGet_expectStoredValue() {
        store.setLastSyncTimestamp(1_700_000_000L)

        store.getLastSyncTimestamp() shouldBeEqualTo 1_700_000_000L
    }

    @Test
    fun setLastSyncTimestamp_givenSubsequentSet_expectOverwrite() {
        store.setLastSyncTimestamp(100L)
        store.setLastSyncTimestamp(200L)

        store.getLastSyncTimestamp() shouldBeEqualTo 200L
    }

    // --- clearAll wipes everything ---

    @Test
    fun clearAll_expectEverythingRemoved() {
        store.saveCachedRegions(listOf(GeofenceRegion("biz-1", 0.0, 0.0, 50f)))
        store.saveRegisteredIds(setOf("biz-1"))
        store.saveCachedConfig(
            GeofenceConfig(
                localRefreshTriggerRadius = 1_000f,
                remoteFetchRefreshTriggerRadius = 5_000f,
                remoteFetchRefreshExpiryMs = 1L,
                duplicateEventsExpiryMs = 1L,
                maxBusinessGeofences = 1
            )
        )
        store.saveLastApiFetchLocation(GeofenceLocation(1.0, 2.0))
        store.setLastSyncTimestamp(12_345L)

        store.clearAll()

        store.getCachedRegions().shouldBeEmpty()
        store.getRegisteredIds().shouldBeEmpty()
        store.getCachedConfig().shouldBeNull()
        store.getLastApiFetchLocation().shouldBeNull()
        store.getLastSyncTimestamp().shouldBeNull()
    }

    // --- Schema-drift / corruption safety ---

    @Test
    fun getCachedRegions_givenCorruptedJson_expectEmptyAndKeyCleared() {
        writeRaw("cached_regions", "this is not valid json")

        store.getCachedRegions().shouldBeEmpty()
        // Re-read after the failed parse should see "no value" (key was wiped),
        // so writing fresh data works without leftover corruption.
        store.getCachedRegions().shouldBeEmpty()
    }

    @Test
    fun getCachedConfig_givenCorruptedJson_expectNullAndKeyCleared() {
        writeRaw("cached_config", "{ broken")

        store.getCachedConfig().shouldBeNull()
        store.getCachedConfig().shouldBeNull()
    }

    @Test
    fun getLastApiFetchLocation_givenCorruptedJson_expectNullAndKeyCleared() {
        writeRaw("last_api_fetch_location", "}{not json{")

        store.getLastApiFetchLocation().shouldBeNull()
    }

    // --- @SerialName key pinning ---
    // Pin the persisted JSON keys against accidental @SerialName removal or Kotlin renames.

    @Test
    fun saveCachedConfig_expectStableJsonKeys() {
        store.saveCachedConfig(
            GeofenceConfig(
                localRefreshTriggerRadius = 1_000f,
                remoteFetchRefreshTriggerRadius = 5_000f,
                remoteFetchRefreshExpiryMs = 86_400_000L,
                duplicateEventsExpiryMs = 3_600_000L,
                maxBusinessGeofences = 19
            )
        )

        val raw = readRaw("cached_config")
        listOf(
            "localRefreshTriggerRadius",
            "remoteFetchRefreshTriggerRadius",
            "remoteFetchRefreshExpiryMs",
            "duplicateEventsExpiryMs",
            "maxBusinessGeofences"
        ).forEach { key -> raw shouldContain "\"$key\"" }
    }

    @Test
    fun saveLastApiFetchLocation_expectStableJsonKeys() {
        store.saveLastApiFetchLocation(GeofenceLocation(latitude = 12.34, longitude = 56.78))

        val raw = readRaw("last_api_fetch_location")
        raw shouldContain "\"latitude\""
        raw shouldContain "\"longitude\""
    }

    @Test
    fun saveCachedRegions_expectStableJsonKeys() {
        store.saveCachedRegions(
            listOf(
                GeofenceRegion(
                    id = "biz-1",
                    latitude = 1.0,
                    longitude = 2.0,
                    radius = 100f,
                    name = "Coffee",
                    transitionTypes = listOf(GeofenceTransitionType.ENTER),
                    lastUpdated = 1_700_000_000L
                )
            )
        )

        val raw = readRaw("cached_regions")
        listOf("id", "latitude", "longitude", "radius", "name", "transitionTypes", "lastUpdated").forEach { key ->
            raw shouldContain "\"$key\""
        }
        // Enum value serialized as the pinned name — lowercase to match the API
        // wire format (otherwise the cache and the API speak different dialects).
        raw shouldContain "\"enter\""
    }

    @Test
    fun getCachedConfig_givenJsonWithUnknownFields_expectDecodesIgnoringUnknown() {
        // Forward-compat: a future SDK adding new fields to GeofenceConfig must
        // still be able to read a JSON payload that has extra fields it doesn't know.
        writeRaw(
            "cached_config",
            """{
              "localRefreshTriggerRadius": 1000.0,
              "remoteFetchRefreshTriggerRadius": 5000.0,
              "remoteFetchRefreshExpiryMs": 86400000,
              "duplicateEventsExpiryMs": 3600000,
              "maxBusinessGeofences": 19,
              "future_field_we_dont_know": "ignore me"
            }
            """.trimIndent()
        )

        store.getCachedConfig() shouldBeEqualTo GeofenceConfig(
            localRefreshTriggerRadius = 1_000f,
            remoteFetchRefreshTriggerRadius = 5_000f,
            remoteFetchRefreshExpiryMs = 86_400_000L,
            duplicateEventsExpiryMs = 3_600_000L,
            maxBusinessGeofences = 19
        )
    }

    private fun writeRaw(key: String, value: String) {
        applicationMock.getSharedPreferences(
            "io.customer.sdk.geofence_regions.${applicationMock.packageName}",
            Context.MODE_PRIVATE
        ).edit().putString(key, value).commit()
    }

    private fun readRaw(key: String): String =
        applicationMock.getSharedPreferences(
            "io.customer.sdk.geofence_regions.${applicationMock.packageName}",
            Context.MODE_PRIVATE
        ).getString(key, "") ?: ""
}
