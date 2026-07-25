package io.customer.geofence.api

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceConfig
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.serialization.json.JsonPrimitive
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldThrow
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
                  "last_updated": 1778760000,
                  "geoset_ids": [7, 8, 9]
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
            lastUpdated = 1_778_760_000L,
            geosetIds = listOf("7", "8", "9")
        )
    }

    @Test
    fun parseAndMap_givenFractionalRadius_expectDecodeSucceeds() {
        // A backend sending 150.5 (or 150.0) must not fail the whole response —
        // the domain radius is a Float either way.
        val regions = parseRegions(
            """{ "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 150.5 } ] }"""
        )

        regions[0].radius shouldBeEqualTo 150.5f
    }

    @Test
    fun parseAndMap_givenInvalidRegions_expectDroppedAndValidKept() {
        // GMS would throw for these at registration — each drops alone, with a log.
        val regions = parseRegions(
            """
            {
              "geofences": [
                { "id": "zero-radius", "latitude": 0.0, "longitude": 0.0, "radius": 0 },
                { "id": "negative-radius", "latitude": 0.0, "longitude": 0.0, "radius": -5 },
                { "id": "bad-lat", "latitude": 91.0, "longitude": 0.0, "radius": 100 },
                { "id": "bad-lng", "latitude": 0.0, "longitude": 181.0, "radius": 100 },
                { "id": "valid", "latitude": 40.7, "longitude": -74.0, "radius": 100 }
              ]
            }
            """.trimIndent()
        )

        regions.map { it.id } shouldBeEqualTo listOf("valid")
        verify(exactly = 4) { mockLogger.logInvalidRegionDropped(any()) }
        verify { mockLogger.logInvalidRegionDropped("zero-radius") }
    }

    @Test
    fun parseAndMap_givenOneRegionMappingThrows_expectOthersKept() {
        // One region's unexpected mapper throw must cost only itself.
        val response = parseResponse(twoValidRegionsJson())
        mockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        try {
            every { any<GeofenceApiRegion>().toDomain() } answers {
                val region = firstArg<GeofenceApiRegion>()
                if (region.id == "1") throw IllegalStateException("metadata defect") else callOriginal()
            }

            val regions = response.toDomainRegions()

            regions.map { it.id } shouldBeEqualTo listOf("2")
            verify { mockLogger.logRegionMappingFailed("1", "metadata defect") }
        } finally {
            unmockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        }
    }

    @Test
    fun parseAndMap_givenAllRegionsMappingThrow_expectThrowsNotEmptyList() {
        // All regions dropping = unusable response; an empty "success" would wipe live registrations.
        val response = parseResponse(twoValidRegionsJson())
        mockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        try {
            every { any<GeofenceApiRegion>().toDomain() } throws IllegalStateException("mapper defect")

            invoking { response.toDomainRegions() } shouldThrow IllegalStateException::class
        } finally {
            unmockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        }
    }

    @Test
    fun parseAndMap_givenAllRegionsInvalid_expectThrowsNotEmptyList() {
        // Same guard for all-invalid values (no exceptions involved) — only a genuinely
        // empty response may produce an empty result.
        val raw = """
            {
              "geofences": [
                { "id": "zero-radius", "latitude": 0.0, "longitude": 0.0, "radius": 0 },
                { "id": "bad-lat", "latitude": 91.0, "longitude": 0.0, "radius": 100 }
              ]
            }
        """.trimIndent()

        invoking { parseRegions(raw) } shouldThrow IllegalStateException::class
        verify(exactly = 2) { mockLogger.logInvalidRegionDropped(any()) }
    }

    @Test
    fun parse_givenNaNOrInfinityValues_expectDecodeFails() {
        // Pins the assumption that lets toDomain skip isFinite checks: the serializer has
        // no allowSpecialFloatingPointValues, so NaN/Infinity can never reach mapping —
        // even via lenient-mode quoted strings. Decode failure -> Result.failure upstream.
        val nanRadius = """{ "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": "NaN" } ] }"""
        val infLatitude = """{ "geofences": [ { "id": 1, "latitude": "Infinity", "longitude": 0.0, "radius": 100 } ] }"""

        invoking { parseResponse(nanRadius) } shouldThrow Exception::class
        invoking { parseResponse(infLatitude) } shouldThrow Exception::class
    }

    @Test
    fun parseAndMap_givenBoundaryCoordinates_expectKept() {
        // Poles and the antimeridian are valid registerable values — the validation is
        // inclusive at the boundaries.
        val regions = parseRegions(
            """
            {
              "geofences": [
                { "id": "pole", "latitude": -90.0, "longitude": 180.0, "radius": 0.5 }
              ]
            }
            """.trimIndent()
        )

        regions.map { it.id } shouldBeEqualTo listOf("pole")
    }

    @Test
    fun parseAndMap_givenNoGeosetIds_expectEmpty() {
        val regions = parseRegions(
            """{ "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100 } ] }"""
        )

        regions[0].geosetIds.shouldBeEmpty()
    }

    @Test
    fun parseAndMap_givenNumericGeosetIdsOnWire_expectStringsInOrder() {
        // Server contract: geoset_ids arrive as JSON numbers ([]int64). The SDK treats them as opaque
        // string identifiers (like `id`), coercing each element without reordering.
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100, "geoset_ids": [1, 3, 7] } ] }
            """.trimIndent()
        )

        regions[0].geosetIds shouldBeEqualTo listOf("1", "3", "7")
    }

    @Test
    fun parseAndMap_givenQuotedStringGeosetIdsOnWire_expectStringsInOrder() {
        // Defensive: geoset_ids are typed as strings, so a quoted form decodes identically to the numeric contract.
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100, "geoset_ids": ["1", "3", "7"] } ] }
            """.trimIndent()
        )

        regions[0].geosetIds shouldBeEqualTo listOf("1", "3", "7")
    }

    @Test
    fun parseAndMap_givenMetadata_expectPreservedWithPrimitiveTypes() {
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 100, "latitude": 0.0, "longitude": 0.0, "radius": 250,
              "metadata": { "category": "office", "priority": 3, "vip": true } } ] }
            """.trimIndent()
        )

        val metadata = regions[0].metadata
        metadata["category"] shouldBeEqualTo JsonPrimitive("office")
        metadata["priority"] shouldBeEqualTo JsonPrimitive(3)
        metadata["vip"] shouldBeEqualTo JsonPrimitive(true)
    }

    @Test
    fun parseAndMap_givenNoMetadata_expectEmptyMap() {
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100 } ] }
            """.trimIndent()
        )

        regions[0].metadata.shouldBeEmpty()
    }

    @Test
    fun parseAndMap_givenNonScalarMetadataValues_expectDroppedAtParseScalarsKept() {
        // Non-scalar values (object/array/null) can't be emitted, so they're dropped at parse rather
        // than stored — and one bad value must not fail the whole region parse.
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100,
              "metadata": { "category": "office", "nested": { "x": 1 }, "tags": ["a", "b"], "missing": null } } ] }
            """.trimIndent()
        )

        val metadata = regions[0].metadata
        metadata.keys shouldContainSame listOf("category")
        metadata["category"] shouldBeEqualTo JsonPrimitive("office")
    }

    @Test
    fun parseAndMap_givenMalformedMetadataType_expectEmptyMetadataAndRegionStillParses() {
        // `metadata` sent as a non-object (here a string) must not fail the region/response decode —
        // it degrades to empty metadata while every other field parses normally.
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 1, "latitude": 1.5, "longitude": 2.5, "radius": 100, "metadata": "oops" } ] }
            """.trimIndent()
        )

        regions.size shouldBeEqualTo 1
        regions[0].id shouldBeEqualTo "1"
        regions[0].latitude shouldBeEqualTo 1.5
        regions[0].metadata.shouldBeEmpty()
    }

    @Test
    fun parseAndMap_givenMoreThanMaxAttributes_expectCappedToMax() {
        val attrs = (1..150).joinToString(",") { "\"k$it\": \"v$it\"" }
        val regions = parseRegions(
            """{ "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100, "metadata": { $attrs } } ] }"""
        )

        regions[0].metadata.size shouldBeEqualTo 100
    }

    @Test
    fun parseAndMap_givenOversizedTotalPayload_expectTrimmed() {
        // No per-value cap (left to the server); the total-payload backstop drops the runaway entry.
        val huge = "x".repeat(200 * 1024)
        val regions = parseRegions(
            """{ "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100,
              "metadata": { "a": "small", "z": "$huge" } } ] }"""
        )

        val metadata = regions[0].metadata
        metadata.keys shouldContainSame listOf("a")
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
        regions[0].name.shouldBeNull()
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
                "max_monitoring_distance": 500000,
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
            maxBusinessGeofences = 25,
            maxMonitoringDistance = 500_000f
        )
    }

    @Test
    fun toDomainConfig_givenAllFieldsMissing_expectAllFallbacks() {
        // Empty config object — every field-level fallback fires.
        val response = parseResponse("""{ "config": {}, "geofences": [] }""")

        response.toDomainConfig() shouldBeEqualTo fallbackConfig()
    }

    @Test
    fun toDomainConfig_givenPartialConfig_expectPresentFieldsUsedAndRestFallback() {
        // Backend rolling fields out gradually: present fields are used (and coerced), each absent
        // field falls back independently.
        val response = parseResponse(
            """
            {
              "config": {
                "local_refresh_trigger_radius": 2000,
                "android": { "max_business_geofence": 10 }
              },
              "geofences": []
            }
            """.trimIndent()
        )

        response.toDomainConfig() shouldBeEqualTo GeofenceConfig(
            localRefreshTriggerRadius = 2000f,
            remoteFetchRefreshTriggerRadius = GeofenceConstants.FALLBACK_REMOTE_FETCH_RADIUS_METERS,
            remoteFetchRefreshExpiry = GeofenceConstants.STALE_THRESHOLD_MS,
            duplicateEventsExpiry = GeofenceConstants.DEDUPE_COOLDOWN_MS,
            maxBusinessGeofences = 10,
            maxMonitoringDistance = GeofenceConstants.FALLBACK_MAX_MONITORING_DISTANCE_METERS
        )
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
                "max_monitoring_distance": -1,
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

    @Test
    fun toDomainConfig_givenLocalRefreshRadiusOutOfRange_expectClampedToBounds() {
        // Positive but absurd radii clamp to the sane bounds instead of being used as-is.
        val belowMin = parseResponse("""{ "config": { "local_refresh_trigger_radius": 10 }, "geofences": [] }""")
        val aboveMax = parseResponse("""{ "config": { "local_refresh_trigger_radius": 999999 }, "geofences": [] }""")

        belowMin.toDomainConfig()?.localRefreshTriggerRadius shouldBeEqualTo
            GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS
        aboveMax.toDomainConfig()?.localRefreshTriggerRadius shouldBeEqualTo
            GeofenceConstants.MAX_LOCAL_REFRESH_RADIUS_METERS
    }

    @Test
    fun toDomainConfig_givenExpiriesOutOfRange_expectClampedToBounds() {
        val response = parseResponse(
            """
            {
              "config": {
                "remote_fetch_refresh_expiry_time": 1,
                "duplicate_events_expiry_time": 999999999999
              },
              "geofences": []
            }
            """.trimIndent()
        )

        val config = response.toDomainConfig()
        config?.remoteFetchRefreshExpiry shouldBeEqualTo GeofenceConstants.MIN_REMOTE_FETCH_REFRESH_EXPIRY_MS
        config?.duplicateEventsExpiry shouldBeEqualTo GeofenceConstants.MAX_DUPLICATE_EVENTS_EXPIRY_MS
    }

    @Test
    fun toDomainConfig_givenMaxMonitoringDistanceBelowTriggerRadius_expectFallback() {
        // A cap below the trigger radius would create a dead-zone, so it falls back to the default.
        val response = parseResponse(
            """
            {
              "config": {
                "local_refresh_trigger_radius": 3000,
                "max_monitoring_distance": 1000
              },
              "geofences": []
            }
            """.trimIndent()
        )

        response.toDomainConfig()?.maxMonitoringDistance shouldBeEqualTo
            GeofenceConstants.FALLBACK_MAX_MONITORING_DISTANCE_METERS
    }

    @Test
    fun toDomainConfig_givenMaxMonitoringDistanceZero_expectNoCap() {
        // 0 is the explicit "disable the cap" signal — register regardless of distance.
        val response = parseResponse(
            """{ "config": { "max_monitoring_distance": 0 }, "geofences": [] }"""
        )

        response.toDomainConfig()?.maxMonitoringDistance shouldBeEqualTo
            GeofenceConstants.NO_MONITORING_DISTANCE_CAP_METERS
    }

    // ---------- helpers ----------

    private val jsonSerializer = GeofenceJsonSerializer()

    // Mirrors GeofenceApiServiceImpl's call site so tests exercise the same
    // decode path (lenient at the wire boundary).
    private fun parseResponse(raw: String): GeofenceApiResponse =
        jsonSerializer.decode(GeofenceApiResponse.serializer(), raw, lenient = true)

    private fun parseRegions(raw: String): List<GeofenceRegion> =
        parseResponse(raw).toDomainRegions()

    private fun twoValidRegionsJson(): String = """
        {
          "geofences": [
            { "id": 1, "latitude": 10.0, "longitude": 10.0, "radius": 100 },
            { "id": 2, "latitude": 20.0, "longitude": 20.0, "radius": 100 }
          ]
        }
    """.trimIndent()

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
        maxBusinessGeofences = GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES,
        maxMonitoringDistance = GeofenceConstants.FALLBACK_MAX_MONITORING_DISTANCE_METERS
    )
}
