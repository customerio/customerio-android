package io.customer.geofence.api

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLogger
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A `POST /v2/geofences/nearest` payload decoded by the shipping path.
 *
 * Synthetic coordinates, names and addresses. The wire SHAPE is what these tests are for and is
 * copied from a real 2026-09-11 v2 response: numeric `id` and `geoset_ids`, `enclosing_circle` on
 * polygons only, a repeated closing vertex, absent `transition_types` and `last_updated`, and the
 * `metadata` keys the backend actually sends. Do not restore a real capture here.
 */
@RunWith(RobolectricTestRunner::class)
class GeofenceV2PayloadTest : RobolectricTest() {

    private val mockLogger: GeofenceLogger = mockk(relaxed = true)

    private val json = GeofenceJsonSerializer()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk { overrideDependency<GeofenceLogger>(mockLogger) }
                }
            }
        )
    }

    private fun decode(): GeofenceApiResponse =
        json.decode(GeofenceApiResponse.serializer(), PAYLOAD, lenient = true)

    @Test
    fun v2Payload_decodesEveryRegion() {
        val response = decode()
        assertEquals(9, response.geofences.size)
    }

    @Test
    fun v2Payload_coercesNumericIdsAndGeosetIds() {
        val market = decode().geofences.first { it.name == "Northgate Market" }
        assertEquals("18", market.id)
        assertEquals(listOf("1"), market.geosetIds)
    }

    @Test
    fun v2Payload_readsPolygonGeometryAndEnclosingCircle() {
        val response = decode()
        val polygons = response.geofences.filter { it.shape == "polygon" }
        assertEquals(4, polygons.size)

        val market = polygons.first { it.id == "18" }
        market.enclosingCircle?.baseRadiusMeters shouldBeEqualTo 101.0
        // 5 wire positions in, 4 out: the canonicaliser drops the repeated closing vertex.
        market.geometry?.toPolygonGeometryOrNull()?.vertices?.size shouldBeEqualTo 4
    }

    @Test
    fun v2Payload_everyPolygonRingBuilds() {
        decode().geofences.filter { it.shape == "polygon" }.forEach { region ->
            assertNotNull("ring did not build for ${region.name}", region.geometry?.toPolygonGeometryOrNull())
        }
    }

    @Test
    fun v2Payload_configParsesAndroidBlock() {
        val config = decode().toDomainConfig().shouldNotBeNull()
        assertEquals(19, config.maxBusinessGeofences)
        assertEquals(1000f, config.localRefreshTriggerRadius, 0.01f)
        assertEquals(5000f, config.remoteFetchRefreshTriggerRadius, 0.01f)
        assertEquals(86_400_000L, config.remoteFetchRefreshExpiry)
        assertEquals(3_600_000L, config.duplicateEventsExpiry)
    }

    @Test
    fun v2Payload_absentTransitionTypesFallBackToEnterAndExit() {
        val catalog = decode().toCatalogEntries()
        catalog.forEach { entry ->
            assertEquals(listOf("enter", "exit"), entry.transitionTypes)
        }
    }

    @Test
    fun v2Payload_absentLastUpdatedIsAbsentOnTheWire() {
        decode().geofences.forEach { assertNull(it.lastUpdated) }
    }

    /** This build has no polygon runtime, so every polygon must drop and every circle survive. */
    @Test
    fun v2Payload_mapsCirclesAndDropsPolygonsWithoutPolygonRuntime() {
        val regions = decode().toDomainRegions()
        assertEquals(5, regions.size)
        assertEquals(
            listOf("10", "12", "19", "4", "9"),
            regions.map { it.id }.sorted()
        )
    }

    /** The catalog is the record of what the server sent, so it must keep the polygons. */
    @Test
    fun v2Payload_catalogKeepsEveryRecordIncludingPolygons() {
        val catalog = decode().toCatalogEntries()
        assertEquals(9, catalog.size)
        assertEquals(4, catalog.count { it.shape == "polygon" })
        assertEquals(5, catalog.count { it.shape == "circle" })
        // A polygon's catalog radius is the backend's enclosing circle, never a padded one.
        catalog.first { it.id == "18" }.radiusMeters shouldBeEqualTo 101.0
    }

    private companion object {
        val PAYLOAD: String = GeofenceV2PayloadTest::class.java
            .getResourceAsStream("/v2_nearest_response.json")
            .shouldNotBeNull()
            .bufferedReader().use { it.readText() }
    }
}
