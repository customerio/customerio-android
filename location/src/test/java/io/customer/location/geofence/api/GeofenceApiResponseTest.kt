package io.customer.location.geofence.api

import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.GeofenceConfig
import io.customer.location.geofence.GeofenceRegion
import io.customer.location.geofence.GeofenceTransitionType
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceApiResponseTest : RobolectricTest() {

    @Test
    fun parseAndMap_givenFullSampleResponse_expectDomainValues() {
        val (config, regions) = parseAndMap(
            """
            {
              "config": {
                "movement_trigger_radius": 1000,
                "local_refresh_trigger_radius": 5000,
                "remote_fetch_refresh_trigger_radius": 10000,
                "remote_fetch_refresh_expiry_time": 86400,
                "duplicate_events_expiry_time": 3600,
                "android": { "max_business_geofence": 19 },
                "ios": { "max_business_geofence": 7 }
              },
              "geofences": [
                {
                  "id": "id-123",
                  "name": "name-123",
                  "latitude": 12.34,
                  "longitude": 56.78,
                  "radius": 1000,
                  "transition_types": ["enter", "exit"],
                  "last_updated": 1778760000
                }
              ]
            }
            """.trimIndent()
        )

        config shouldBeEqualTo GeofenceConfig(
            movementTriggerRadius = 1000f,
            localRefreshTriggerRadius = 5000f,
            remoteFetchRefreshTriggerRadius = 10000f,
            remoteFetchRefreshExpiryMs = 86_400_000L,
            duplicateEventsExpiryMs = 3_600_000L,
            maxBusinessGeofences = 19
        )

        regions.size shouldBeEqualTo 1
        regions[0] shouldBeEqualTo GeofenceRegion(
            id = "id-123",
            name = "name-123",
            latitude = 12.34,
            longitude = 56.78,
            radius = 1000f,
            transitionTypes = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT),
            lastUpdated = 1_778_760_000L
        )
    }

    @Test
    fun parseAndMap_givenUnknownTopLevelField_expectIgnoredAndParses() {
        // Validates Json { ignoreUnknownKeys = true } — if a future backend adds new fields
        // we keep decoding rather than throwing.
        val (config, regions) = parseAndMap(
            """
            {
              "version": "2",
              "config": {
                "movement_trigger_radius": 1000,
                "local_refresh_trigger_radius": 5000,
                "remote_fetch_refresh_trigger_radius": 10000,
                "remote_fetch_refresh_expiry_time": 86400,
                "duplicate_events_expiry_time": 3600,
                "android": { "max_business_geofence": 19 }
              },
              "geofences": []
            }
            """.trimIndent()
        )

        config.movementTriggerRadius shouldBeEqualTo 1000f
        regions.shouldBeEmpty()
    }

    @Test
    fun parseAndMap_givenMixedValidAndInvalidTransitionTypes_expectOnlyValidKept() {
        val (_, regions) = parseAndMap(geofencesJsonWith(transitionTypes = listOf("enter", "dwell", "unknown")))

        regions.size shouldBeEqualTo 1
        regions[0].transitionTypes shouldContainSame listOf(GeofenceTransitionType.ENTER)
    }

    @Test
    fun parseAndMap_givenAllInvalidTransitionTypes_expectRegionSkipped() {
        val (_, regions) = parseAndMap(geofencesJsonWith(transitionTypes = listOf("dwell")))

        regions.shouldBeEmpty()
    }

    private val parser = GeofenceApiResponseParser()

    private fun parseAndMap(raw: String): Pair<GeofenceConfig, List<GeofenceRegion>> {
        val response = parser.parse(raw)
        return response.toDomainConfig() to response.toDomainRegions()
    }

    private fun geofencesJsonWith(transitionTypes: List<String>): String {
        val typesJson = transitionTypes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        return """
            {
              "config": {
                "movement_trigger_radius": 1000,
                "local_refresh_trigger_radius": 5000,
                "remote_fetch_refresh_trigger_radius": 10000,
                "remote_fetch_refresh_expiry_time": 86400,
                "duplicate_events_expiry_time": 3600,
                "android": { "max_business_geofence": 19 }
              },
              "geofences": [
                {
                  "id": "biz-1",
                  "name": "name",
                  "latitude": 0.0,
                  "longitude": 0.0,
                  "radius": 100,
                  "transition_types": $typesJson,
                  "last_updated": 0
                }
              ]
            }
        """.trimIndent()
    }
}
