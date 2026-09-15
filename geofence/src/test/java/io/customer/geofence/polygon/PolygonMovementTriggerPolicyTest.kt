package io.customer.geofence.polygon

import io.customer.geofence.GeofenceRegion
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test

class PolygonMovementTriggerPolicyTest {
    private val policy = PolygonMovementTriggerPolicy()

    @Test
    fun safeRadiusMeters_givenNoPolygons_expectNormalCatalogRadius() {
        policy.safeRadiusMeters(
            regions = emptyList(),
            committedInsideIds = emptySet(),
            sample = sampleAtOrigin(),
            normalRadiusMeters = 1_000f
        ) shouldBeEqualTo 1_000f
    }

    @Test
    fun safeRadiusMeters_givenBoundaryNineHundredMetersAway_expectShrinksMovementTrigger() {
        val radius = policy.safeRadiusMeters(
            regions = listOf(rectangle(id = "east", westMeters = 900.0, eastMeters = 1_100.0)),
            committedInsideIds = emptySet(),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 1_000f
        )

        radius.shouldNotBeNull().shouldBeInRange(785f, 795f)
    }

    @Test
    fun safeRadiusMeters_givenSeveralPolygons_expectUsesClosestBoundary() {
        val radius = policy.safeRadiusMeters(
            regions = listOf(
                rectangle(id = "east", westMeters = 900.0, eastMeters = 1_100.0),
                rectangle(id = "north", southMeters = 500.0, northMeters = 700.0)
            ),
            committedInsideIds = emptySet(),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 1_000f
        )

        radius.shouldNotBeNull().shouldBeInRange(385f, 395f)
    }

    @Test
    fun safeRadiusMeters_givenCommittedStateDisagreesWithFix_expectNoSafeBubble() {
        policy.safeRadiusMeters(
            regions = listOf(rectangle(id = "east", westMeters = 900.0, eastMeters = 1_100.0)),
            committedInsideIds = setOf("east"),
            sample = sampleAtOrigin(),
            normalRadiusMeters = 1_000f
        ).shouldBeNull()
    }

    @Test
    fun safeRadiusMeters_givenApproachWithBoundaryTooClose_expectNoSafeBubble() {
        // Approaching, a floored trigger would extend past the ring the device is walking towards,
        // so stopping here would lose the arrival with no backstop. Refusing keeps it sampling.
        policy.safeRadiusMeters(
            regions = listOf(rectangle(id = "east", westMeters = 150.0, eastMeters = 350.0)),
            committedInsideIds = emptySet(),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 1_000f
        ).shouldBeNull()
    }

    @Test
    fun safeRadiusMeters_givenApproachToARetailSizedFence_expectStillNoSafeBubble() {
        // The same holds however close the fence is. This is the case that must not be floored:
        // 30 m out, a 250 m trigger reaches 220 m past the boundary and the walk-in wakes nothing.
        policy.safeRadiusMeters(
            regions = listOf(rectangle(id = "shop", westMeters = 30.0, eastMeters = 110.0)),
            committedInsideIds = emptySet(),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 1_000f
        ).shouldBeNull()
    }

    @Test
    fun safeRadiusMeters_givenDepartureFromARetailSizedFence_expectTheFloorNotTheCatalogDefault() {
        // The three real retail polygons are 24-42 m inradius. Standing committed inside one, the
        // clearance is negative, and a refusal falls the caller back to a 1000 m trigger — so the
        // departure is only seen once the device has moved a kilometre.
        val radius = policy.safeRadiusMeters(
            regions = listOf(
                rectangle(
                    id = "shop",
                    westMeters = -40.0,
                    eastMeters = 40.0,
                    southMeters = -40.0,
                    northMeters = 40.0
                )
            ),
            committedInsideIds = setOf("shop"),
            sample = sampleAtOrigin(accuracyMeters = 19.5),
            normalRadiusMeters = 1_000f
        )

        radius.shouldNotBeNull() shouldBeEqualTo
            PolygonMovementTriggerPolicy.MIN_DEPARTURE_TRIGGER_RADIUS_METERS.toFloat()
    }

    private fun sampleAtOrigin(accuracyMeters: Double = 5.0) = PolygonLocationSample(
        coordinate = meters(),
        horizontalAccuracyMeters = accuracyMeters
    )

    private fun rectangle(
        id: String,
        westMeters: Double = -100.0,
        eastMeters: Double = 100.0,
        southMeters: Double = -100.0,
        northMeters: Double = 100.0
    ) = GeofenceRegion(
        id = id,
        latitude = 0.0,
        longitude = 0.0,
        radius = 2_000f,
        polygonVertices = listOf(
            meters(southMeters, westMeters),
            meters(southMeters, eastMeters),
            meters(northMeters, eastMeters),
            meters(northMeters, westMeters)
        )
    )

    private fun meters(north: Double = 0.0, east: Double = 0.0) = PolygonCoordinate(
        latitude = north / METERS_PER_DEGREE,
        longitude = east / METERS_PER_DEGREE
    )

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
    // The reject path had no test at all: a fix too coarse to place the device must not be allowed
    // to shrink the movement trigger, because a trigger shrunk on a bad fix stops firing.

    @Test
    fun safeRadiusMeters_givenFixCoarserThanTheCeiling_expectNoShrinkAtAll() {
        policy.safeRadiusMeters(
            regions = listOf(rectangle(id = "east", westMeters = 900.0, eastMeters = 1_100.0)),
            committedInsideIds = emptySet(),
            sample = sampleAtOrigin(accuracyMeters = 51.0),
            normalRadiusMeters = 1_000f
        ).shouldBeNull()
    }

    @Test
    fun safeRadiusMeters_givenFixJustInsideTheCeiling_expectItStillShrinks() {
        policy.safeRadiusMeters(
            regions = listOf(rectangle(id = "east", westMeters = 900.0, eastMeters = 1_100.0)),
            committedInsideIds = emptySet(),
            sample = sampleAtOrigin(accuracyMeters = 49.0),
            normalRadiusMeters = 1_000f
        ).shouldNotBeNull()
    }
}
