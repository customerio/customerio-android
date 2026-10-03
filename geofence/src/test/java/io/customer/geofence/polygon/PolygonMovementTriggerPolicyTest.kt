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
        // Must not be floored: 30 m out, a 250 m trigger reaches 220 m past the boundary and the
        // walk-in wakes nothing.
        policy.safeRadiusMeters(
            regions = listOf(rectangle(id = "shop", westMeters = 30.0, eastMeters = 110.0)),
            committedInsideIds = emptySet(),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 1_000f
        ).shouldBeNull()
    }

    @Test
    fun safeRadiusMeters_givenDepartureFromARetailSizedFence_expectTheFloorNotTheCatalogDefault() {
        // Inside a small ring the clearance is negative. A refusal would fall the caller back to
        // the 1000 m catalog trigger, so departure would only be seen a kilometre out.
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

    @Test
    fun safeRadiusMeters_givenDepartureFromALargePolygon_expectItsOwnClearanceNotTheFloor() {
        // 2 km inside a large ring a trigger just under 2 km is still crossed before the boundary;
        // flooring it to 250 m would only add wakes, each a sync and a re-registration.
        val radius = policy.safeRadiusMeters(
            regions = listOf(
                rectangle(
                    id = "campus",
                    westMeters = -2_000.0,
                    eastMeters = 2_000.0,
                    southMeters = -2_000.0,
                    northMeters = 2_000.0
                )
            ),
            committedInsideIds = setOf("campus"),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 5_000f
        )

        radius.shouldNotBeNull().shouldBeInRange(1_885f, 1_895f)
    }

    @Test
    fun safeRadiusMeters_givenDepartureClearanceWiderThanTheCatalogRadius_expectTheCatalogRadius() {
        policy.safeRadiusMeters(
            regions = listOf(
                rectangle(
                    id = "campus",
                    westMeters = -2_000.0,
                    eastMeters = 2_000.0,
                    southMeters = -2_000.0,
                    northMeters = 2_000.0
                )
            ),
            committedInsideIds = setOf("campus"),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 1_000f
        ) shouldBeEqualTo 1_000f
    }

    @Test
    fun safeRadiusMeters_givenDepartureOnlyAndASubClampRefreshRadius_expectNoRefusal() {
        // Below the config clamp's 100 m minimum. A pure departure is still armed: the refusal
        // protects an arrival and there is none here.
        policy.safeRadiusMeters(
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
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 50f
        ) shouldBeEqualTo PolygonMovementTriggerPolicy.MIN_DEPARTURE_TRIGGER_RADIUS_METERS.toFloat()
    }

    @Test
    fun safeRadiusMeters_givenDepartureAndARefreshRadiusBelowTheFloor_expectTheFloorNotTheConfig() {
        // `localRefreshTriggerRadius` is clamped to [100, 5000], so a workspace can configure 150 m.
        // A departure trigger under the floor would fire on coarse containment error instead.
        policy.safeRadiusMeters(
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
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 150f
        ) shouldBeEqualTo PolygonMovementTriggerPolicy.MIN_DEPARTURE_TRIGGER_RADIUS_METERS.toFloat()
    }

    @Test
    fun safeRadiusMeters_givenInsideOnePolygonAndApproachingAnother_expectNoSafeBubble() {
        // A 250 m departure trigger would let sampling stop, and the second shop 60 m away is
        // entered without crossing it.
        policy.safeRadiusMeters(
            regions = listOf(
                rectangle(id = "inside", westMeters = -40.0, eastMeters = 40.0, southMeters = -40.0, northMeters = 40.0),
                rectangle(id = "approaching", westMeters = 60.0, eastMeters = 260.0)
            ),
            committedInsideIds = setOf("inside"),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 1_000f
        ).shouldBeNull()
    }

    @Test
    fun safeRadiusMeters_givenInsideOnePolygonAndTheOtherFarEnoughAway_expectTheDepartureFloor() {
        policy.safeRadiusMeters(
            regions = listOf(
                rectangle(id = "inside", westMeters = -40.0, eastMeters = 40.0, southMeters = -40.0, northMeters = 40.0),
                rectangle(id = "approaching", westMeters = 600.0, eastMeters = 900.0)
            ),
            committedInsideIds = setOf("inside"),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 1_000f
        ) shouldBeEqualTo PolygonMovementTriggerPolicy.MIN_DEPARTURE_TRIGGER_RADIUS_METERS.toFloat()
    }

    @Test
    fun safeRadiusMeters_givenApproachClearanceTighterThanTheDepartureFloor_expectTheClearanceWins() {
        // Widening to the departure floor would reach past the ring being approached.
        val radius = policy.safeRadiusMeters(
            regions = listOf(
                rectangle(id = "inside", westMeters = -40.0, eastMeters = 40.0, southMeters = -40.0, northMeters = 40.0),
                rectangle(id = "approaching", westMeters = 290.0, eastMeters = 490.0)
            ),
            committedInsideIds = setOf("inside"),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 1_000f
        )

        radius.shouldNotBeNull().shouldBeInRange(175f, 185f)
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
    // A fix too coarse to place the device must not size the movement trigger.

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
