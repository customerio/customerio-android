package io.customer.geofence.polygon

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeLessThan
import org.junit.Test

class PolygonShapeAdversarialIntegrationTest {
    @Test
    fun route_whenInsideConcaveEnclosingCircleDeadSpace_thenDoesNotEnterPolygon() {
        val geometry = geometry(
            point(-0.002, -0.002),
            point(-0.002, 0.002),
            point(-0.0005, 0.002),
            point(-0.0005, -0.0005),
            point(0.002, -0.0005),
            point(0.002, -0.002)
        )
        val deadSpace = point(0.001, 0.001)
        val triggerCircle = PolygonEnclosingCircle().calculate(geometry)
        val route = RouteHarness(listOf(PolygonFence("l-shape", geometry)))

        geometry.relationTo(deadSpace) shouldBeEqualTo PolygonPointRelation.OUTSIDE
        distanceMeters(triggerCircle.center, deadSpace) shouldBeLessThan triggerCircle.radiusMeters.toDouble()

        val events = (1..3).flatMap {
            route.process(deadSpace.latitude, deadSpace.longitude, accuracy = 5.0)
        }

        events shouldBeEqualTo emptyList()
    }

    @Test
    fun route_whenEnteringTriangle_thenEmitsEntry() {
        val triangle = PolygonFence(
            id = "triangle",
            geometry = geometry(
                point(0.0, -0.002),
                point(-0.002, 0.002),
                point(0.002, 0.002)
            )
        )
        val route = RouteHarness(listOf(triangle))

        val events = (1..3).flatMap {
            route.process(0.0, 0.0005, accuracy = 5.0)
        }

        events shouldBeEqualTo listOf(
            PolygonTransitionDetection("triangle", PolygonTransition.ENTER)
        )
    }

    @Test
    fun route_whenEnteringSixteenVertexCircleLikePolygon_thenEmitsEntry() {
        val vertices = List(16) { index ->
            val angle = 2.0 * PI * index / 16.0
            point(
                latitude = 0.002 * sin(angle),
                longitude = 0.002 * cos(angle)
            )
        }
        val route = RouteHarness(
            listOf(PolygonFence("circle-like", PolygonGeometry.from(vertices)))
        )

        val events = (1..3).flatMap {
            route.process(0.0, 0.0, accuracy = 5.0)
        }

        events shouldBeEqualTo listOf(
            PolygonTransitionDetection("circle-like", PolygonTransition.ENTER)
        )
    }

    @Test
    fun route_whenCrossingNarrowCorridorWithClearFixes_thenEmitsEntryAndExit() {
        val corridor = PolygonFence(
            id = "corridor",
            geometry = geometry(
                point(-0.00006, -0.002),
                point(-0.00006, 0.002),
                point(0.00006, 0.002),
                point(0.00006, -0.002)
            )
        )
        val route = RouteHarness(listOf(corridor))

        val events = buildList {
            repeat(3) { addAll(route.process(0.0, 0.0, accuracy = 2.0)) }
            repeat(3) { addAll(route.process(0.0002, 0.0, accuracy = 2.0)) }
        }

        events shouldBeEqualTo listOf(
            PolygonTransitionDetection("corridor", PolygonTransition.ENTER),
            PolygonTransitionDetection("corridor", PolygonTransition.EXIT)
        )
    }

    @Test
    fun route_whenFixesGrazeEdgeAndVertex_thenWaitsForClearInsideEvidence() {
        val square = PolygonFence(
            id = "square",
            geometry = geometry(
                point(-0.001, -0.001),
                point(-0.001, 0.001),
                point(0.001, 0.001),
                point(0.001, -0.001)
            )
        )
        val route = RouteHarness(listOf(square))

        val grazingEvents = listOf(
            route.process(0.001, 0.001, accuracy = 0.0),
            route.process(0.00099, 0.001, accuracy = 12.0),
            route.process(0.00101, 0.001, accuracy = 12.0),
            route.process(0.001, 0.00099, accuracy = 12.0)
        ).flatten()
        val confirmedEvents = (1..3).flatMap {
            route.process(0.0, 0.0, accuracy = 5.0)
        }

        grazingEvents shouldBeEqualTo emptyList()
        confirmedEvents shouldBeEqualTo listOf(
            PolygonTransitionDetection("square", PolygonTransition.ENTER)
        )
    }

    @Test
    fun route_whenQuickEntryAndExitMeetMinimumEvidenceCadence_thenEmitsBothEdges() {
        val square = PolygonFence(
            id = "square",
            geometry = geometry(
                point(-0.001, -0.001),
                point(-0.001, 0.001),
                point(0.001, 0.001),
                point(0.001, -0.001)
            )
        )
        val interval = 1_500_000_000L
        val route = RouteHarness(listOf(square), minimumEvidenceIntervalNanos = interval)

        val events = listOf(
            route.process(0.0, 0.0, accuracy = 2.0, elapsedRealtimeNanos = 1L),
            route.process(0.0, 0.0, accuracy = 2.0, elapsedRealtimeNanos = interval + 1L),
            route.process(0.0, 0.0, accuracy = 2.0, elapsedRealtimeNanos = interval * 2 + 1L),
            route.process(0.0, 0.002, accuracy = 2.0, elapsedRealtimeNanos = interval * 3 + 1L),
            route.process(0.0, 0.002, accuracy = 2.0, elapsedRealtimeNanos = interval * 4 + 1L),
            route.process(0.0, 0.002, accuracy = 2.0, elapsedRealtimeNanos = interval * 5 + 1L)
        ).flatten()

        events shouldBeEqualTo listOf(
            PolygonTransitionDetection("square", PolygonTransition.ENTER),
            PolygonTransitionDetection("square", PolygonTransition.EXIT)
        )
    }

    @Test
    fun route_whenLeavingOnlyOneOfTwoOverlappingPolygons_thenExitsOnlyThatPolygon() {
        val west = PolygonFence(
            id = "west",
            geometry = geometry(
                point(-0.001, -0.002),
                point(-0.001, 0.001),
                point(0.001, 0.001),
                point(0.001, -0.002)
            )
        )
        val east = PolygonFence(
            id = "east",
            geometry = geometry(
                point(-0.001, -0.001),
                point(-0.001, 0.002),
                point(0.001, 0.002),
                point(0.001, -0.001)
            )
        )
        val route = RouteHarness(listOf(west, east))

        val events = buildList {
            repeat(3) { addAll(route.process(0.0, 0.0, accuracy = 5.0)) }
            repeat(3) { addAll(route.process(0.0, 0.0015, accuracy = 5.0)) }
        }

        events shouldBeEqualTo listOf(
            PolygonTransitionDetection("west", PolygonTransition.ENTER),
            PolygonTransitionDetection("east", PolygonTransition.ENTER),
            PolygonTransitionDetection("west", PolygonTransition.EXIT)
        )
    }

    private class RouteHarness(
        private val fences: List<PolygonFence>,
        minimumEvidenceIntervalNanos: Long = 0L
    ) {
        private val processor = PolygonRouteProcessor(
            minimumEvidenceIntervalNanos = minimumEvidenceIntervalNanos
        )
        private val committedStates = mutableMapOf<String, PolygonCommittedState>()
        private var elapsedRealtimeNanos = 0L

        fun process(
            latitude: Double,
            longitude: Double,
            accuracy: Double,
            elapsedRealtimeNanos: Long = ++this.elapsedRealtimeNanos
        ): List<PolygonTransitionDetection> {
            this.elapsedRealtimeNanos = maxOf(this.elapsedRealtimeNanos, elapsedRealtimeNanos)
            return processor.process(
                fences = fences,
                sample = PolygonLocationSample(point(latitude, longitude), accuracy),
                elapsedRealtimeNanos = elapsedRealtimeNanos,
                committedStates = committedStates
            ).also { detections ->
                detections.forEach { detection ->
                    committedStates[detection.polygonId] = when (detection.transition) {
                        PolygonTransition.ENTER -> PolygonCommittedState.INSIDE
                        PolygonTransition.EXIT -> PolygonCommittedState.OUTSIDE
                    }
                }
            }
        }
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0

        fun geometry(vararg vertices: PolygonCoordinate): PolygonGeometry =
            PolygonGeometry.from(vertices.toList())

        fun point(latitude: Double, longitude: Double) =
            PolygonCoordinate(latitude = latitude, longitude = longitude)

        fun distanceMeters(first: PolygonCoordinate, second: PolygonCoordinate): Double {
            val firstLatitude = Math.toRadians(first.latitude)
            val secondLatitude = Math.toRadians(second.latitude)
            val latitudeDelta = secondLatitude - firstLatitude
            val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
            val haversine = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
                cos(firstLatitude) * cos(secondLatitude) *
                sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
            val centralAngle = 2.0 * atan2(sqrt(haversine), sqrt(1.0 - haversine))
            return EARTH_RADIUS_METERS * centralAngle
        }
    }
}
