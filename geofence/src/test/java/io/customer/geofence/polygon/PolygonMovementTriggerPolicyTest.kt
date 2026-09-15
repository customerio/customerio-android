package io.customer.geofence.polygon

import io.customer.geofence.GeofenceConstants
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

        radius.shouldNotBeNull().shouldBeInRange(895f, 905f)
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

        radius.shouldNotBeNull().shouldBeInRange(495f, 505f)
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
    fun safeRadiusMeters_givenBoundaryCloserThanTheFloor_expectTheFloorRatherThanNoShrink() {
        // Previously this returned null, and every caller reads null as "leave the trigger at its
        // 1000 m default". A device 30 m from a ring therefore kept a kilometre-wide trigger and
        // could not reach the safely-passive state at all.
        policy.safeRadiusMeters(
            regions = listOf(rectangle(id = "east", westMeters = 30.0, eastMeters = 230.0)),
            committedInsideIds = emptySet(),
            sample = sampleAtOrigin(accuracyMeters = 10.0),
            normalRadiusMeters = 1_000f
        ) shouldBeEqualTo GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS
    }

    @Test
    fun safeRadiusMeters_givenRetailSizedFence_expectATightTriggerNotTheCatalogDefault() {
        // The three real retail polygons are 24-42 m inradius. Standing in one of them, the old
        // rule needed `edge >= accuracy + 200` to shrink, which no such fence can satisfy.
        val radius = policy.safeRadiusMeters(
            regions = listOf(rectangle(id = "shop", westMeters = -40.0, eastMeters = 40.0, southMeters = -40.0, northMeters = 40.0)),
            committedInsideIds = setOf("shop"),
            sample = sampleAtOrigin(accuracyMeters = 19.5),
            normalRadiusMeters = 1_000f
        )

        radius.shouldNotBeNull() shouldBeEqualTo GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS
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
