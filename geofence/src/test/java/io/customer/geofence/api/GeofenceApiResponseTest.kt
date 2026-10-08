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
import io.customer.geofence.PolygonDropReason
import io.customer.geofence.distanceTo
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonSupport
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
import org.amshove.kluent.shouldBeTrue
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

    // ---------- polygon records: decoded here, monitored only with the runtime opt-in ----------

    @Test
    fun parseAndMap_givenPolygonAndNoOptIn_expectDroppedAndCirclesKept() {
        val regions = parseRegions(polygonAndCircleJson())

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        verify { mockLogger.logPolygonDroppedUnsupportedRuntime("campus") }
        verify(exactly = 0) { mockLogger.logInvalidRegionDropped("campus") }
    }

    @Test
    fun parseAndMap_givenPolygonWithCircleFieldsAndNoOptIn_expectNotDegradedToCircle() {
        // Falling back to the circle fields would register a fence the backend never described.
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "campus",
                  "latitude": 37.775,
                  "longitude": -122.419,
                  "radius": 250,
                  "shape": "polygon",
                  "enclosing_circle": {
                    "latitude": 37.775,
                    "longitude": -122.4194,
                    "base_radius_m": 100
                  },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                      [-122.4200, 37.7745],
                      [-122.4188, 37.7745],
                      [-122.4188, 37.7755],
                      [-122.4200, 37.7755],
                      [-122.4200, 37.7745]
                    ]]
                  }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent()
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
    }

    @Test
    fun parseAndMap_givenOnlyPolygonsAndNoOptIn_expectThrowsSoLiveCatalogSurvives() {
        invoking { parseRegions(polygonOnlyJson()) } shouldThrow IllegalStateException::class
        verify { mockLogger.logPolygonDroppedUnsupportedRuntime("campus") }
    }

    @Test
    fun parseAndMap_givenPolygonAndOptIn_expectValidatedPolygonAndEnclosingCircle() {
        val region = parseRegions(polygonAndCircleJson(), PolygonSupport.Enabled)
            .single { it.id == "campus" }

        region.isPolygon.shouldBeTrue()
        region.latitude shouldBeEqualTo 37.775
        region.longitude shouldBeEqualTo -122.4194
        region.radius shouldBeEqualTo 100f
        region.polygonVertices?.size shouldBeEqualTo 4
        region.polygonVertices?.first() shouldBeEqualTo PolygonCoordinate(37.7745, -122.4200)
        region.polygonVertices.orEmpty().forEach { vertex ->
            (region.distanceTo(vertex.latitude, vertex.longitude) <= region.radius).shouldBeTrue()
        }
    }

    @Test
    fun parseAndMap_givenGeometryWithoutPolygonShape_expectDroppedNotDegradedToCircle() {
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "missing-shape",
                  "latitude": 37.775,
                  "longitude": -122.419,
                  "radius": 250,
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                      [-122.4200, 37.7745],
                      [-122.4188, 37.7745],
                      [-122.4188, 37.7755],
                      [-122.4200, 37.7755],
                      [-122.4200, 37.7745]
                    ]]
                  }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        verify { mockLogger.logPolygonDropped("missing-shape", PolygonDropReason.UNDESCRIBED_SHAPE) }
    }

    @Test
    fun parseAndMap_givenShapePaddedWithWhitespace_expectRoutedNotDroppedAsUnsupported() {
        val regions = parseRegions(
            """
            {
              "geofences": [
                { "id": "padded", "shape": " Circle ", "latitude": 1, "longitude": 2, "radius": 100 },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("padded", "circle")
        verify(exactly = 0) { mockLogger.logUnsupportedGeometryDropped("padded", any()) }
    }

    @Test
    fun parseAndMap_givenBlankShape_expectTreatedAsAbsentNotUnsupported() {
        val regions = parseRegions(
            """
            {
              "geofences": [
                { "id": "blank", "shape": "   ", "latitude": 1, "longitude": 2, "radius": 100 },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("blank", "circle")
        verify(exactly = 0) { mockLogger.logUnsupportedGeometryDropped("blank", any()) }
    }

    @Test
    fun parseAndMap_givenPaddedPolygonShape_expectRoutedToPolygonBranch() {
        val region = parseRegions(
            polygonAndCircleJson().replace("\"shape\": \"polygon\"", "\"shape\": \" Polygon \""),
            PolygonSupport.Enabled
        ).single { it.id == "campus" }

        region.isPolygon.shouldBeTrue()
    }

    @Test
    fun parseAndMap_givenPaddedUnknownShape_expectRawValueReported() {
        parseRegions(
            """
            {
              "geofences": [
                { "id": "odd", "shape": " Hexagon ", "latitude": 1, "longitude": 2, "radius": 100 },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        verify { mockLogger.logUnsupportedGeometryDropped("odd", " Hexagon ") }
    }

    @Test
    fun parseAndMap_givenBlankShapeCarryingGeometry_expectDroppedAsInconsistentNotRegistered() {
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "blank-with-geometry",
                  "shape": " ",
                  "latitude": 37.775,
                  "longitude": -122.419,
                  "radius": 250,
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                      [-122.4200, 37.7745],
                      [-122.4188, 37.7745],
                      [-122.4188, 37.7755],
                      [-122.4200, 37.7755],
                      [-122.4200, 37.7745]
                    ]]
                  }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        verify {
            mockLogger.logPolygonDropped("blank-with-geometry", PolygonDropReason.UNDESCRIBED_SHAPE)
        }
    }

    @Test
    fun parseAndMap_givenPolygonPositionThatIsNotFinite_expectRecordDroppedNotWholeSync() {
        // Positions decode as JsonElement, so "NaN" becomes a Double and passes every comparison-based
        // geometry check.
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "not-a-number",
                  "shape": "polygon",
                  "enclosing_circle": { "latitude": 0.0, "longitude": 0.0, "base_radius_m": 100 },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[0, 0], [1, 0], [1, 1], [0, "NaN"], [0, 0]]]
                  }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        verify { mockLogger.logPolygonDropped("not-a-number", PolygonDropReason.RING_UNBUILDABLE) }
    }

    @Test
    fun parseAndMap_givenPolygonWithOutOfRangeWakeCircleCenter_expectRecordDroppedNotWholeSync() {
        // Geofence.Builder throws on an out-of-range centre, failing the whole registration batch.
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "bad-center",
                  "shape": "polygon",
                  "enclosing_circle": { "latitude": 91.0, "longitude": 0.0, "base_radius_m": 100 },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[0, 0], [0.01, 0], [0.01, 0.01], [0, 0.01], [0, 0]]]
                  }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        verify {
            mockLogger.logPolygonDropped("bad-center", PolygonDropReason.UNUSABLE_CIRCLE)
        }
    }

    @Test
    fun parseAndMap_givenPolygonWithoutBackendWakeCircle_expectDropped() {
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "missing-circle",
                  "shape": "polygon",
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[0, 0], [0.01, 0], [0.01, 0.01], [0, 0.01], [0, 0]]]
                  }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        verify { mockLogger.logPolygonDropped("missing-circle", PolygonDropReason.UNUSABLE_POLYGON) }
    }

    @Test
    fun parseAndMap_givenConcavePolygon_expectMapped() {
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "concave",
                  "shape": "polygon",
                  "enclosing_circle": { "latitude": 0.005, "longitude": 0.005, "base_radius_m": 2000 },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[0, 0], [0.01, 0], [0.004, 0.004], [0.01, 0.01], [0, 0.01], [0, 0]]]
                  }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("concave", "circle")
    }

    @Test
    fun toPolygonGeometryOrNull_givenGeoJsonPolygon_expectDecodedRing() {
        // Decoding needs no opt-in; only turning it into a monitored region is gated.
        val geometry = parseResponse(polygonAndCircleJson()).geofences
            .single { it.id == "campus" }
            .geometry

        geometry?.toPolygonGeometryOrNull()?.vertices shouldBeEqualTo listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        )
    }

    @Test
    fun parseAndMap_givenUnknownGeometryTypeWithCircleFields_expectDroppedNotDegradedToCircle() {
        listOf(PolygonSupport.Disabled, PolygonSupport.Enabled).forEach { support ->
            val regions = parseRegions(
                """
                {
                  "geofences": [
                    {
                      "id": "multi",
                      "latitude": 0, "longitude": 0, "radius": 100,
                      "shape": "polygon",
                      "enclosing_circle": { "latitude": 0, "longitude": 0, "base_radius_m": 1000 },
                      "geometry": { "type": "MultiPolygon", "coordinates": [[[[0, 0], [0, 1], [1, 1], [0, 0]]]] }
                    },
                    { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
                  ]
                }
                """.trimIndent(),
                support
            )

            regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        }
        verify(exactly = 2) { mockLogger.logUnsupportedGeometryDropped("multi", "MultiPolygon") }
    }

    @Test
    fun parseAndMap_givenNullGeometryType_expectRegionDecodesAsCircle() {
        val regions = parseRegions(
            """{ "geofences": [ { "id": "circle", "latitude": 1.5, "longitude": 2.5, "radius": 100, "geometry": null } ] }"""
        )

        regions.single().isPolygon shouldBeEqualTo false
        regions.single().latitude shouldBeEqualTo 1.5
    }

    @Test
    fun parseAndMap_givenPolygonWithHole_expectOnlyInvalidPolygonDropped() {
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "hole",
                  "shape": "polygon",
                  "enclosing_circle": { "latitude": 0.005, "longitude": 0.005, "base_radius_m": 1000 },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [[0, 0], [0.01, 0], [0.01, 0.01], [0, 0.01], [0, 0]],
                      [[0.002, 0.002], [0.003, 0.002], [0.003, 0.003], [0.002, 0.002]]
                    ]
                  }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        verify { mockLogger.logPolygonDropped(eq("hole"), any()) }
    }

    @Test
    fun parseAndMap_givenSelfIntersectingPolygon_expectDropIsIsolatedNotThrown() {
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "bow-tie",
                  "shape": "polygon",
                  "enclosing_circle": { "latitude": 0.005, "longitude": 0.005, "base_radius_m": 1000 },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[0, 0], [0.01, 0.01], [0, 0.01], [0.01, 0], [0, 0]]]
                  }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        verify { mockLogger.logPolygonDropped(eq("bow-tie"), any()) }
        verify(exactly = 0) { mockLogger.logRegionMappingFailed(eq("bow-tie"), any()) }
    }

    @Test
    fun parseAndMap_givenOversizedPolygon_expectDroppedWithoutCostingOtherRegions() {
        // Well past the 100 km OS trigger ceiling.
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "continent",
                  "shape": "polygon",
                  "enclosing_circle": { "latitude": 10, "longitude": 15, "base_radius_m": 2000000 },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[0, 0], [30, 0], [30, 20], [0, 20], [0, 0]]]
                  }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        verify { mockLogger.logPolygonDropped(eq("continent"), any()) }
    }

    @Test
    fun parseAndMap_givenPolygonBeyondTheFormerVertexCap_expectMapped() {
        val ring = (0 until 600).joinToString(",") { index ->
            val angle = 2.0 * Math.PI * index / 600.0
            "[${0.002 * kotlin.math.cos(angle)}, ${0.002 * kotlin.math.sin(angle)}]"
        }
        val regions = parseRegions(
            """
            {
              "geofences": [
                {
                  "id": "too-many",
                  "shape": "polygon",
                  "enclosing_circle": { "latitude": 0, "longitude": 0, "base_radius_m": 1000 },
                  "geometry": { "type": "Polygon", "coordinates": [[$ring]] }
                },
                { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
              ]
            }
            """.trimIndent(),
            PolygonSupport.Enabled
        )

        // Vertex count is the backend's admission policy, not something the ring needs to be usable.
        regions.map(GeofenceRegion::id) shouldBeEqualTo listOf("too-many", "circle")
        regions.first().polygonVertices?.size shouldBeEqualTo 600
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
        val regions = parseRegions(
            """{ "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 150.5 } ] }"""
        )

        regions[0].radius shouldBeEqualTo 150.5f
    }

    @Test
    fun parseAndMap_givenInvalidRegions_expectDroppedAndValidKept() {
        // GMS would throw for each of these at registration.
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
        val response = parseResponse(twoValidRegionsJson())
        mockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        try {
            every { any<GeofenceApiRegion>().toDomain(any()) } answers {
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
        // An empty "success" would wipe live registrations.
        val response = parseResponse(twoValidRegionsJson())
        mockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        try {
            every { any<GeofenceApiRegion>().toDomain(any()) } throws IllegalStateException("mapper defect")

            invoking { response.toDomainRegions() } shouldThrow IllegalStateException::class
        } finally {
            unmockkStatic("io.customer.geofence.api.GeofenceApiResponseKt")
        }
    }

    @Test
    fun parseAndMap_givenAllRegionsInvalid_expectThrowsNotEmptyList() {
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
        // toDomain relies on this instead of isFinite checks, so keep
        // allowSpecialFloatingPointValues off.
        val nanRadius = """{ "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": "NaN" } ] }"""
        val infLatitude = """{ "geofences": [ { "id": 1, "latitude": "Infinity", "longitude": 0.0, "radius": 100 } ] }"""

        invoking { parseResponse(nanRadius) } shouldThrow Exception::class
        invoking { parseResponse(infLatitude) } shouldThrow Exception::class
    }

    @Test
    fun parseAndMap_givenBoundaryCoordinates_expectKept() {
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
        // The server sends geoset_ids as JSON numbers; the SDK treats them as opaque strings.
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100, "geoset_ids": [1, 3, 7] } ] }
            """.trimIndent()
        )

        regions[0].geosetIds shouldBeEqualTo listOf("1", "3", "7")
    }

    @Test
    fun parseAndMap_givenQuotedStringGeosetIdsOnWire_expectStringsInOrder() {
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
        val regions = parseRegions(
            """{ "geofences": [ { "id": "abc-123", "latitude": 0.0, "longitude": 0.0, "radius": 100 } ] }"""
        )

        regions[0].id shouldBeEqualTo "abc-123"
    }

    @Test
    fun parseAndMap_givenUnknownTopLevelField_expectIgnoredAndParses() {
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
        val regions = parseRegions(
            """
            { "geofences": [ { "id": 1, "latitude": 0.0, "longitude": 0.0, "radius": 100, "external_id": "" } ] }
            """.trimIndent()
        )

        regions[0].externalId shouldBeEqualTo ""
    }

    @Test
    fun parseAndMap_givenDwellThreshold_expectItPreserved() {
        val region = parseRegions(
            """{"geofences":[{"id":"circle","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":300}]}"""
        ).single()

        region.dwellThresholdSeconds shouldBeEqualTo 300
    }

    @Test
    fun parseAndMap_givenPositiveDwellThresholdBoundaries_expectPreserved() {
        val regions = parseRegions(
            """{"geofences":[
                {"id":"low","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":1},
                {"id":"high","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":${GeofenceConstants.MAX_DWELL_THRESHOLD_SECONDS}}
            ]}"""
        )

        regions.map { it.dwellThresholdSeconds } shouldBeEqualTo
            listOf(1, GeofenceConstants.MAX_DWELL_THRESHOLD_SECONDS)
    }

    @Test
    fun parseAndMap_givenUnrepresentableDwellThreshold_expectDwellDisabled() {
        val zero = parseRegions(
            """{"geofences":[{"id":"low","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":0}]}"""
        ).single()
        val overflow = parseRegions(
            """{"geofences":[{"id":"high","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":${GeofenceConstants.MAX_DWELL_THRESHOLD_SECONDS + 1}}]}"""
        ).single()
        val beyondInt = parseRegions(
            """{"geofences":[{"id":"very-high","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":3000000000}]}"""
        ).single()

        zero.dwellThresholdSeconds shouldBeEqualTo 0
        overflow.dwellThresholdSeconds shouldBeEqualTo 0
        beyondInt.dwellThresholdSeconds shouldBeEqualTo 0
    }

    @Test
    fun parseAndMap_givenUnsupportedDwellThreshold_expectRegionKeptDwellDisabledAndLogged() {
        val overflow = GeofenceConstants.MAX_DWELL_THRESHOLD_SECONDS + 1L
        val regions = parseRegions(
            """{"geofences":[
                {"id":"negative","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":-1},
                {"id":"overflow","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":$overflow}
            ]}"""
        )

        // Still monitored for enter and exit; only dwell is off.
        regions.map { it.id to it.dwellThresholdSeconds } shouldBeEqualTo
            listOf("negative" to 0, "overflow" to 0)
        verify(exactly = 1) { mockLogger.logUnsupportedDwellThreshold("negative", -1L) }
        verify(exactly = 1) { mockLogger.logUnsupportedDwellThreshold("overflow", overflow) }
    }

    @Test
    fun parseAndMap_givenAbsentDisabledOrSupportedDwellThreshold_expectNoUnsupportedThresholdLog() {
        val regions = parseRegions(
            """{"geofences":[
                {"id":"absent","latitude":1,"longitude":2,"radius":100},
                {"id":"disabled","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":0},
                {"id":"low","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":1},
                {"id":"high","latitude":1,"longitude":2,"radius":100,"dwell_threshold_seconds":${GeofenceConstants.MAX_DWELL_THRESHOLD_SECONDS}}
            ]}"""
        )

        regions.map { it.dwellThresholdSeconds } shouldBeEqualTo
            listOf(0, 0, 1, GeofenceConstants.MAX_DWELL_THRESHOLD_SECONDS)
        verify(exactly = 0) { mockLogger.logUnsupportedDwellThreshold(any(), any()) }
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
        val regions = parseRegions(regionJsonWith(transitionTypes = """["dwell"]"""))

        regions[0].transitionTypes shouldContainSame listOf(
            GeofenceTransitionType.ENTER,
            GeofenceTransitionType.EXIT
        )
        verify { mockLogger.logUnknownApiTransitionType("dwell") }
    }

    @Test
    fun parseAndMap_givenMixedValidAndUnknownTransitionTypes_expectOnlyValidKept() {
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
        // null tells the repository to keep its last cached config.
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
        val response = parseResponse("""{ "config": {}, "geofences": [] }""")

        response.toDomainConfig() shouldBeEqualTo fallbackConfig()
    }

    @Test
    fun toDomainConfig_givenPartialConfig_expectPresentFieldsUsedAndRestFallback() {
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
        // 0 is a valid kill switch for max_business_geofence, so it gets -5 instead.
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
        val response = parseResponse(configJsonWithMax(0))
        response.toDomainConfig()?.maxBusinessGeofences shouldBeEqualTo 0
    }

    @Test
    fun toDomainConfig_givenMaxBusinessGeofenceAtOrAboveOsLimit_expectFallback() {
        // With the movement trigger, 100 business fences would exceed the OS's 100-fence cap.
        val atLimit = parseResponse(configJsonWithMax(100)).toDomainConfig()
        val above = parseResponse(configJsonWithMax(500)).toDomainConfig()

        atLimit?.maxBusinessGeofences shouldBeEqualTo GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
        above?.maxBusinessGeofences shouldBeEqualTo GeofenceConstants.FALLBACK_MAX_BUSINESS_GEOFENCES
    }

    @Test
    fun toDomainConfig_givenLocalRefreshRadiusOutOfRange_expectClampedToBounds() {
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
        val response = parseResponse(
            """{ "config": { "max_monitoring_distance": 0 }, "geofences": [] }"""
        )

        response.toDomainConfig()?.maxMonitoringDistance shouldBeEqualTo
            GeofenceConstants.NO_MONITORING_DISTANCE_CAP_METERS
    }

    // ---------- partial polygon payloads cost only their own record ----------

    @Test
    fun toDomainRegions_givenPolygonMissingCoordinates_expectOnlyThatRecordDropped() {
        // These decode before the per-record try/catch, so a required field would reject the whole
        // response.
        val regions = parseRegions(partialPolygonJson(geometry = """{ "type": "Polygon" }"""), PolygonSupport.Enabled)

        regions.map { it.id } shouldContainSame listOf("plain-circle")
    }

    @Test
    fun toDomainRegions_givenPolygonMissingEnclosingCircleRadius_expectOnlyThatRecordDropped() {
        val json = partialPolygonJson(
            enclosingCircle = """{ "latitude": 37.775, "longitude": -122.4194 }"""
        )

        val regions = parseRegions(json, PolygonSupport.Enabled)

        regions.map { it.id } shouldContainSame listOf("plain-circle")
    }

    @Test
    fun toDomainRegions_givenPolygonWithEmptyGeometryObject_expectOnlyThatRecordDropped() {
        val regions = parseRegions(partialPolygonJson(geometry = "{}"), PolygonSupport.Enabled)

        regions.map { it.id } shouldContainSame listOf("plain-circle")
    }

    @Test
    fun toDomainRegions_givenValidPolygon_expectTheBackendCircleRegisteredAsSent() {
        // The registered radius is the backend's, not a floored or padded one.
        val regions = parseRegions(polygonAndCircleJson(), PolygonSupport.Enabled)
        val polygon = regions.first { it.id == "campus" }

        polygon.baseRadiusMeters shouldBeEqualTo 100.0
        polygon.radius shouldBeEqualTo 100f
    }

    /** One malformed polygon next to a perfectly good circle. */
    private fun partialPolygonJson(
        geometry: String = """{ "type": "Polygon", "coordinates": [[[-122.42,37.77],[-122.41,37.77],[-122.41,37.78]]] }""",
        enclosingCircle: String = """{ "latitude": 37.775, "longitude": -122.4194, "base_radius_m": 100 }"""
    ): String = """
        {
          "geofences": [
            {
              "id": "broken-polygon",
              "shape": "polygon",
              "enclosing_circle": $enclosingCircle,
              "geometry": $geometry,
              "transition_types": ["enter"],
              "last_updated": 1
            },
            {
              "id": "plain-circle",
              "latitude": 37.0,
              "longitude": -122.0,
              "radius": 100,
              "transition_types": ["enter"],
              "last_updated": 1
            }
          ]
        }
    """.trimIndent()

    // ---------- catalog: built from the wire, before anything is dropped ----------

    @Test
    fun toCatalogEntries_expectOneEntryPerWireRecordEvenWhenOneIsDropped() {
        val response = parseResponse(polygonAndCircleJson())

        // The catalog records what the server sent, including records the mapper drops.
        response.toDomainRegions().map(GeofenceRegion::id) shouldBeEqualTo listOf("circle")
        response.toCatalogEntries().map { it.id } shouldBeEqualTo listOf("campus", "circle")
    }

    @Test
    fun toCatalogEntries_givenEmptyResponse_expectNoEntries() {
        parseResponse("""{ "geofences": [] }""").toCatalogEntries().shouldBeEmpty()
    }

    @Test
    fun toCatalogEntries_givenPolygon_expectEnclosingCircleAndCanonicalRing() {
        val entry = parseResponse(polygonOnlyJson()).toCatalogEntries().single()

        entry.shape shouldBeEqualTo "polygon"
        entry.latitude shouldBeEqualTo 37.775
        entry.longitude shouldBeEqualTo -122.4194
        entry.radiusMeters shouldBeEqualTo 100.0
        // Five wire positions, closing vertex dropped.
        entry.vertices?.size shouldBeEqualTo 4
    }

    @Test
    fun toCatalogEntries_givenUnbuildableRing_expectRowKeptWithoutVertices() {
        val response = parseResponse(
            """
            {
              "geofences": [
                {
                  "id": "bad-ring",
                  "shape": "polygon",
                  "enclosing_circle": { "latitude": 37.775, "longitude": -122.4194, "base_radius_m": 100 },
                  "geometry": { "type": "Polygon", "coordinates": [[[-122.42, 37.77], [-122.41, 37.77]]] }
                }
              ]
            }
            """.trimIndent()
        )

        val entry = response.toCatalogEntries().single()
        entry.id shouldBeEqualTo "bad-ring"
        entry.shape shouldBeEqualTo "polygon"
        // Absent, never a ring we could not build: a half-decoded ring answers membership wrongly.
        entry.vertices.shouldBeNull()
        entry.radiusMeters shouldBeEqualTo 100.0
        invoking { response.toDomainRegions(PolygonSupport.Enabled) } shouldThrow Exception::class
    }

    @Test
    fun toCatalogEntries_givenPolygonWithoutEnclosingCircle_expectPlacementAbsentNotSubstituted() {
        val entry = parseResponse(
            """
            {
              "geofences": [
                {
                  "id": "no-circle",
                  "shape": "polygon",
                  "latitude": 1.0,
                  "longitude": 2.0,
                  "radius": 50,
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                      [-122.4200, 37.7745],
                      [-122.4188, 37.7745],
                      [-122.4188, 37.7755],
                      [-122.4200, 37.7745]
                    ]]
                  }
                }
              ]
            }
            """.trimIndent()
        ).toCatalogEntries().single()

        entry.shape shouldBeEqualTo "polygon"
        entry.latitude.shouldBeNull()
        entry.longitude.shouldBeNull()
        entry.radiusMeters.shouldBeNull()
        entry.vertices?.size shouldBeEqualTo 3
    }

    @Test
    fun toCatalogEntries_givenGeometryWithoutDiscriminator_expectCircleClaimAndTheRing() {
        // A row saying only "circle" would be indistinguishable from a plain circle.
        val entry = parseResponse(
            """
            {
              "geofences": [
                {
                  "id": "mismatched",
                  "latitude": 1.0,
                  "longitude": 2.0,
                  "radius": 50,
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                      [-122.4200, 37.7745],
                      [-122.4188, 37.7745],
                      [-122.4188, 37.7755],
                      [-122.4200, 37.7745]
                    ]]
                  }
                }
              ]
            }
            """.trimIndent()
        ).toCatalogEntries().single()

        entry.shape shouldBeEqualTo "circle"
        entry.vertices?.size shouldBeEqualTo 3
        entry.latitude shouldBeEqualTo 1.0
        entry.radiusMeters shouldBeEqualTo 50.0
    }

    @Test
    fun toCatalogEntries_givenUnsupportedShape_expectTheClaimVerbatim() {
        val entry = parseResponse(
            """
            { "geofences": [ { "id": "odd", "shape": "Hexagon", "latitude": 1.0, "longitude": 2.0, "radius": 50 } ] }
            """.trimIndent()
        ).toCatalogEntries().single()

        entry.shape shouldBeEqualTo "hexagon"
        entry.latitude shouldBeEqualTo 1.0
        entry.radiusMeters shouldBeEqualTo 50.0
    }

    @Test
    fun toCatalogEntries_givenLegacyRecord_expectCircleAndResolvedTransitionTypes() {
        val entry = parseResponse(
            """
            { "geofences": [ { "id": "legacy", "latitude": 1.0, "longitude": 2.0, "radius": 50 } ] }
            """.trimIndent()
        ).toCatalogEntries().single()

        entry.shape shouldBeEqualTo "circle"
        entry.vertices.shouldBeNull()
        entry.transitionTypes shouldBeEqualTo listOf("enter", "exit")
    }

    @Test
    fun toCatalogEntries_givenUnknownTransitionType_expectReportedOnceNotTwice() {
        val response = parseResponse(
            """
            { "geofences": [ { "id": "c", "latitude": 1.0, "longitude": 2.0, "radius": 50, "transition_types": ["dwell"] } ] }
            """.trimIndent()
        )

        // Production order. Only the mapper may report an unknown type, or it is counted twice.
        response.toCatalogEntries()
        response.toDomainRegions()

        verify(exactly = 1) { mockLogger.logUnknownApiTransitionType("dwell") }
    }

    // ---------- helpers ----------

    private val jsonSerializer = GeofenceJsonSerializer()

    // Mirrors GeofenceApiServiceImpl's lenient decode.
    private fun parseResponse(raw: String): GeofenceApiResponse =
        jsonSerializer.decode(GeofenceApiResponse.serializer(), raw, lenient = true)

    private fun parseRegions(
        raw: String,
        polygonSupport: PolygonSupport = PolygonSupport.Disabled
    ): List<GeofenceRegion> = parseResponse(raw).toDomainRegions(polygonSupport)

    private fun polygonAndCircleJson(): String = """
        {
          "geofences": [
            {
              "id": "campus",
              "shape": "polygon",
              "enclosing_circle": {
                "latitude": 37.775,
                "longitude": -122.4194,
                "base_radius_m": 100
              },
              "geometry": {
                "type": "Polygon",
                "coordinates": [[
                  [-122.4200, 37.7745],
                  [-122.4188, 37.7745],
                  [-122.4188, 37.7755],
                  [-122.4200, 37.7755],
                  [-122.4200, 37.7745]
                ]]
              }
            },
            { "id": "circle", "latitude": 0, "longitude": 0, "radius": 100 }
          ]
        }
    """.trimIndent()

    private fun polygonOnlyJson(): String = """
        {
          "geofences": [
            {
              "id": "campus",
              "shape": "polygon",
              "enclosing_circle": {
                "latitude": 37.775,
                "longitude": -122.4194,
                "base_radius_m": 100
              },
              "geometry": {
                "type": "Polygon",
                "coordinates": [[
                  [-122.4200, 37.7745],
                  [-122.4188, 37.7745],
                  [-122.4188, 37.7755],
                  [-122.4200, 37.7755],
                  [-122.4200, 37.7745]
                ]]
              }
            }
          ]
        }
    """.trimIndent()

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
