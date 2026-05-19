package io.customer.location.geofence.api

import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.GeofenceConfig
import io.customer.location.geofence.GeofenceConstants
import io.customer.location.geofence.GeofenceJsonSerializer
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
                "local_refresh_trigger_radius": 1000,
                "remote_fetch_refresh_trigger_radius": 5000,
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
            localRefreshTriggerRadius = 1000f,
            remoteFetchRefreshTriggerRadius = 5000f,
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
                "local_refresh_trigger_radius": 1000,
                "remote_fetch_refresh_trigger_radius": 5000,
                "remote_fetch_refresh_expiry_time": 86400,
                "duplicate_events_expiry_time": 3600,
                "android": { "max_business_geofence": 19 }
              },
              "geofences": []
            }
            """.trimIndent()
        )

        config.localRefreshTriggerRadius shouldBeEqualTo 1000f
        regions.shouldBeEmpty()
    }

    @Test
    fun parseAndMap_givenConfigFieldsMissing_expectAllFallbacksApplied() {
        // Backend rollout safety: any/all config fields can be absent and the SDK
        // must keep working with SDK-level defaults.
        val (config, _) = parseAndMap(
            """
            {
              "config": {},
              "geofences": []
            }
            """.trimIndent()
        )

        config shouldBeEqualTo GeofenceConfig(
            localRefreshTriggerRadius = GeofenceConstants.FALLBACK_LOCAL_REFRESH_RADIUS_METERS,
            remoteFetchRefreshTriggerRadius = GeofenceConstants.FALLBACK_REMOTE_FETCH_RADIUS_METERS,
            remoteFetchRefreshExpiryMs = GeofenceConstants.STALE_THRESHOLD_MS,
            duplicateEventsExpiryMs = GeofenceConstants.DEDUPE_COOLDOWN_MS,
            maxBusinessGeofences = GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
        )
    }

    @Test
    fun parseAndMap_givenConfigFieldsZeroOrNegative_expectAllFallbacksApplied() {
        // Radii / expiry fields: zero or negative are bogus and fall back via
        // `takeIf { it > 0 }`. `maxBusinessGeofence` has different semantics
        // (zero is a valid kill switch) — covered in a dedicated test below.
        val (config, _) = parseAndMap(
            """
            {
              "config": {
                "local_refresh_trigger_radius": -1,
                "remote_fetch_refresh_trigger_radius": 0,
                "remote_fetch_refresh_expiry_time": -100,
                "duplicate_events_expiry_time": 0,
                "android": { "max_business_geofence": -5 }
              },
              "geofences": []
            }
            """.trimIndent()
        )

        config shouldBeEqualTo GeofenceConfig(
            localRefreshTriggerRadius = GeofenceConstants.FALLBACK_LOCAL_REFRESH_RADIUS_METERS,
            remoteFetchRefreshTriggerRadius = GeofenceConstants.FALLBACK_REMOTE_FETCH_RADIUS_METERS,
            remoteFetchRefreshExpiryMs = GeofenceConstants.STALE_THRESHOLD_MS,
            duplicateEventsExpiryMs = GeofenceConstants.DEDUPE_COOLDOWN_MS,
            maxBusinessGeofences = GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
        )
    }

    @Test
    fun parseAndMap_givenMaxBusinessGeofenceZero_expectRespected() {
        // Zero is a valid server-side kill switch: "register no business
        // geofences for this user." Distinct from missing / out-of-range
        // (which fall back to the SDK default).
        val (config, _) = parseAndMap(geofencesJsonWithMax(0))
        config.maxBusinessGeofences shouldBeEqualTo 0
    }

    @Test
    fun parseAndMap_givenMaxBusinessGeofenceAtOrAboveOsLimit_expectFallback() {
        // OS hard-caps at 100 geofences per app (movement trigger + business).
        // Business cap of 100 would push total to 101 and the OS rejects it.
        // 99 is the highest accepted value.
        val (atLimit, _) = parseAndMap(geofencesJsonWithMax(100))
        val (above, _) = parseAndMap(geofencesJsonWithMax(500))

        atLimit.maxBusinessGeofences shouldBeEqualTo GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
        above.maxBusinessGeofences shouldBeEqualTo GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
    }

    private fun geofencesJsonWithMax(max: Int): String = """
        {
          "config": {
            "local_refresh_trigger_radius": 1000,
            "remote_fetch_refresh_trigger_radius": 5000,
            "remote_fetch_refresh_expiry_time": 86400,
            "duplicate_events_expiry_time": 3600,
            "android": { "max_business_geofence": $max }
          },
          "geofences": []
        }
    """.trimIndent()

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

    private val jsonSerializer = GeofenceJsonSerializer()

    private fun parseAndMap(raw: String): Pair<GeofenceConfig, List<GeofenceRegion>> {
        val response = jsonSerializer.decode(GeofenceApiResponse.serializer(), raw)
        return response.toDomainConfig() to response.toDomainRegions()
    }

    private fun geofencesJsonWith(transitionTypes: List<String>): String {
        val typesJson = transitionTypes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        return """
            {
              "config": {
                "local_refresh_trigger_radius": 1000,
                "remote_fetch_refresh_trigger_radius": 5000,
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
