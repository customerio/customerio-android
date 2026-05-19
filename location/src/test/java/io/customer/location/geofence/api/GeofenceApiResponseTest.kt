package io.customer.location.geofence.api

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.GeofenceConfig
import io.customer.location.geofence.GeofenceConstants
import io.customer.location.geofence.GeofenceJsonSerializer
import io.customer.location.geofence.GeofenceLogger
import io.customer.location.geofence.GeofenceRegion
import io.customer.location.geofence.GeofenceTransitionType
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceApiResponseTest : RobolectricTest() {

    // 'mock' prefix to avoid shadowing SDKComponent.geofenceLogger inside `sdk { ... }`.
    private val mockLogger: GeofenceLogger = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk { overrideDependency<GeofenceLogger>(mockLogger) }
                }
            }
        )
    }

    // ---------- region shape ----------

    @Test
    fun parseAndMap_givenFullSampleRegion_expectDomainValues() {
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": 42,
                  "name": "NYC Store",
                  "latitude": 40.7128,
                  "longitude": -74.0060,
                  "radius": 500,
                  "external_id": "ext-abc",
                  "transition_types": ["enter", "exit"],
                  "last_updated": 1778760000
                }
              ]
            }
            """.trimIndent()
        )

        regions.size shouldBeEqualTo 1
        regions[0] shouldBeEqualTo GeofenceRegion(
            id = "42",
            name = "NYC Store",
            latitude = 40.7128,
            longitude = -74.0060,
            radius = 500f,
            externalId = "ext-abc",
            transitionTypes = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT),
            lastUpdated = 1_778_760_000L
        )
    }

    @Test
    fun parseAndMap_givenMinimalRegion_expectDefaultsForOptionalFields() {
        // Only required fields present; nullable / defaulted fields use SDK defaults.
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 9, "latitude": 1.0, "longitude": 2.0, "radius": 100 } ] }
            """.trimIndent()
        )

        regions[0].id shouldBeEqualTo "9"
        regions[0].name shouldBeEqualTo ""
        regions[0].externalId.shouldBeNull()
        regions[0].lastUpdated shouldBeEqualTo 0L
        regions[0].transitionTypes shouldContainSame listOf(
            GeofenceTransitionType.ENTER,
            GeofenceTransitionType.EXIT
        )
    }

    @Test
    fun parseAndMap_givenEmptyGeofenceList_expectEmpty() {
        parseRegions("""{ "geofences": [] }""").shouldBeEmpty()
    }

    @Test
    fun parseAndMap_givenQuotedStringId_expectDecodedAsString() {
        // Forward-compat: if backend ever ships ids as opaque strings (UUIDs etc.),
        // the SDK consumes them unchanged.
        val regions = parseRegions(
            """{ "geofences": [ { "id": "abc-123", "latitude": 0.0, "longitude": 0.0, "radius": 100 } ] }"""
        )

        regions[0].id shouldBeEqualTo "abc-123"
    }

    @Test
    fun parseAndMap_givenUnknownTopLevelField_expectIgnoredAndParses() {
        // Forward-compat: future top-level additions don't break decoding.
        val regions = parseRegions(
            """
            {
              "version": "2",
              "future_field": { "anything": 42 },
              "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100 } ]
            }
            """.trimIndent()
        )

        regions.size shouldBeEqualTo 1
    }

    // ---------- external_id nullability ----------

    @Test
    fun parseAndMap_givenExplicitNullExternalId_expectNull() {
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100, "external_id": null } ] }
            """.trimIndent()
        )

        regions[0].externalId.shouldBeNull()
    }

    @Test
    fun parseAndMap_givenEmptyExternalId_expectPreserved() {
        // Empty string is preserved separately from null — "explicitly empty"
        // is distinct from "never set."
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100, "external_id": "" } ] }
            """.trimIndent()
        )

        regions[0].externalId shouldBeEqualTo ""
    }

    // ---------- transition_types fallback rules ----------

    @Test
    fun parseAndMap_givenTransitionTypesEnterOnly_expectEnter() {
        val regions = parseRegions(regionJsonWith(transitionTypes = """["enter"]"""))
        regions[0].transitionTypes shouldContainSame listOf(GeofenceTransitionType.ENTER)
    }

    @Test
    fun parseAndMap_givenTransitionTypesEmpty_expectDefault() {
        val regions = parseRegions(regionJsonWith(transitionTypes = "[]"))
        regions[0].transitionTypes shouldContainSame listOf(
            GeofenceTransitionType.ENTER,
            GeofenceTransitionType.EXIT
        )
    }

    @Test
    fun parseAndMap_givenAllUnknownTransitionTypes_expectDefaultAndAllLogged() {
        // `[dwell]` → all unknown → fall back to [ENTER, EXIT], log each unknown.
        val regions = parseRegions(regionJsonWith(transitionTypes = """["dwell"]"""))

        regions[0].transitionTypes shouldContainSame listOf(
            GeofenceTransitionType.ENTER,
            GeofenceTransitionType.EXIT
        )
        verify { mockLogger.logUnknownApiTransitionType("dwell") }
    }

    @Test
    fun parseAndMap_givenMixedValidAndUnknownTransitionTypes_expectOnlyValidKept() {
        // `[enter, dwell]` → keep ENTER, drop "dwell", log it.
        val regions = parseRegions(regionJsonWith(transitionTypes = """["enter", "dwell"]"""))

        regions[0].transitionTypes shouldContainSame listOf(GeofenceTransitionType.ENTER)
        verify { mockLogger.logUnknownApiTransitionType("dwell") }
    }

    @Test
    fun parseAndMap_givenTransitionTypesCaseInsensitive_expectParsed() {
        val regions = parseRegions(regionJsonWith(transitionTypes = """["ENTER", "Exit"]"""))
        regions[0].transitionTypes shouldContainSame listOf(
            GeofenceTransitionType.ENTER,
            GeofenceTransitionType.EXIT
        )
    }

    @Test
    fun parseAndMap_givenOnlyValidTransitionTypes_expectNoUnknownLogged() {
        // Inverse of the unknown-log test: no spurious logs on the happy path.
        parseRegions(regionJsonWith(transitionTypes = """["enter", "exit"]"""))
        verify(exactly = 0) { mockLogger.logUnknownApiTransitionType(any()) }
    }

    // ---------- last_updated ----------

    @Test
    fun parseAndMap_givenLastUpdatedNull_expectZero() {
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100, "last_updated": null } ] }
            """.trimIndent()
        )

        regions[0].lastUpdated shouldBeEqualTo 0L
    }

    // ---------- config block (nullable, field-level fallbacks) ----------

    @Test
    fun toDomainConfig_givenNoConfigBlock_expectNull() {
        // When backend doesn't ship a config block, the SDK keeps using the
        // last cached value (or constants). `null` is the signal that drives
        // the cache-save gating in the repository.
        val response = parseResponse("""{ "geofences": [] }""")
        response.toDomainConfig().shouldBeNull()
    }

    @Test
    fun toDomainConfig_givenFullConfig_expectDomainValues() {
        val response = parseResponse(
            """
            {
              "config": {
                "local_refresh_trigger_radius": 1500,
                "remote_fetch_refresh_trigger_radius": 7500,
                "remote_fetch_refresh_expiry_time": 86400000,
                "duplicate_events_expiry_time": 3600000,
                "android": { "max_business_geofence": 25 }
              },
              "geofences": []
            }
            """.trimIndent()
        )

        response.toDomainConfig() shouldBeEqualTo GeofenceConfig(
            localRefreshTriggerRadius = 1500f,
            remoteFetchRefreshTriggerRadius = 7500f,
            remoteFetchRefreshExpiry = 86_400_000L,
            duplicateEventsExpiry = 3_600_000L,
            maxBusinessGeofences = 25
        )
    }

    @Test
    fun toDomainConfig_givenAllFieldsMissing_expectAllFallbacks() {
        // Empty config object — every field-level fallback fires.
        val response = parseResponse("""{ "config": {}, "geofences": [] }""")

        response.toDomainConfig() shouldBeEqualTo fallbackConfig()
    }

    @Test
    fun toDomainConfig_givenZeroOrNegativeNumericFields_expectFallbacks() {
        // Radii / expiry fields: `takeIf { it > 0 }` rejects 0 and negative.
        // `max_business_geofence = 0` is a valid kill switch (covered separately).
        val response = parseResponse(
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

        response.toDomainConfig() shouldBeEqualTo fallbackConfig()
    }

    @Test
    fun toDomainConfig_givenMaxBusinessGeofenceZero_expectRespected() {
        // Zero is a valid server-side kill switch — "register no business
        // geofences." Distinct from missing / out-of-range (which fall back).
        val response = parseResponse(configJsonWithMax(0))
        response.toDomainConfig()?.maxBusinessGeofences shouldBeEqualTo 0
    }

    @Test
    fun toDomainConfig_givenMaxBusinessGeofenceAtOrAboveOsLimit_expectFallback() {
        // OS hard-caps at 100 geofences per app (movement trigger + business);
        // business cap of 100 would push the total to 101 and the OS rejects.
        // 99 is the highest accepted value.
        val atLimit = parseResponse(configJsonWithMax(100)).toDomainConfig()
        val above = parseResponse(configJsonWithMax(500)).toDomainConfig()

        atLimit?.maxBusinessGeofences shouldBeEqualTo GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
        above?.maxBusinessGeofences shouldBeEqualTo GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
    }

    // ---------- helpers ----------

    private val jsonSerializer = GeofenceJsonSerializer()

    // Mirrors GeofenceApiServiceImpl's call site so tests exercise the same
    // decode path (lenient at the wire boundary).
    private fun parseResponse(raw: String): GeofenceApiResponse =
        jsonSerializer.decode(GeofenceApiResponse.serializer(), raw, lenient = true)

    private fun parseRegions(raw: String): List<GeofenceRegion> =
        parseResponse(raw).toDomainRegions()

    private fun regionJsonWith(transitionTypes: String): String = """
        {
          "geofences": [
            {
              "id": 1,
              "latitude": 0.0,
              "longitude": 0.0,
              "radius": 100,
              "transition_types": $transitionTypes
            }
          ]
        }
    """.trimIndent()

    private fun configJsonWithMax(max: Int): String = """
        {
          "config": { "android": { "max_business_geofence": $max } },
          "geofences": []
        }
    """.trimIndent()

    private fun fallbackConfig(): GeofenceConfig = GeofenceConfig(
        localRefreshTriggerRadius = GeofenceConstants.FALLBACK_LOCAL_REFRESH_RADIUS_METERS,
        remoteFetchRefreshTriggerRadius = GeofenceConstants.FALLBACK_REMOTE_FETCH_RADIUS_METERS,
        remoteFetchRefreshExpiry = GeofenceConstants.STALE_THRESHOLD_MS,
        duplicateEventsExpiry = GeofenceConstants.DEDUPE_COOLDOWN_MS,
        maxBusinessGeofences = GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
    )
}
