package io.customer.geofence.polygon

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class PolygonRouteIntegrationTest {
    private val campus = PolygonFence(
        id = "campus",
        geometry = PolygonGeometry.from(
            listOf(
                point(37.7745, -122.4200),
                point(37.7745, -122.4188),
                point(37.7755, -122.4188),
                point(37.7755, -122.4200)
            )
        )
    )

    @Test
    fun route_whenUserWalksThroughCampus_thenEmitsOneEnterAndOneExit() {
        val route = RouteHarness(listOf(campus))

        val events = listOf(
            route.process(37.7750, -122.4205, accuracy = 5.0),
            route.process(37.7750, -122.4200, accuracy = 8.0),
            route.process(37.7750, -122.4197, accuracy = 5.0),
            route.process(37.7750, -122.4195, accuracy = 5.0),
            route.process(37.7750, -122.4193, accuracy = 5.0),
            route.process(37.7750, -122.4189, accuracy = 8.0),
            route.process(37.7750, -122.4186, accuracy = 5.0),
            route.process(37.7750, -122.4184, accuracy = 5.0),
            route.process(37.7750, -122.4182, accuracy = 5.0)
        ).flatten()

        events shouldBeEqualTo listOf(
            PolygonTransitionDetection("campus", PolygonTransition.ENTER),
            PolygonTransitionDetection("campus", PolygonTransition.EXIT)
        )
    }

    @Test
    fun route_whenBoundaryJitters_thenDoesNotFlicker() {
        val route = RouteHarness(
            fences = listOf(campus),
            initialStates = mapOf("campus" to PolygonCommittedState.INSIDE)
        )

        val events = listOf(
            route.process(37.7750, -122.41881, accuracy = 12.0),
            route.process(37.7750, -122.41879, accuracy = 12.0),
            route.process(37.7750, -122.41882, accuracy = 15.0),
            route.process(37.7750, -122.41878, accuracy = 10.0),
            route.process(37.7750, -122.41883, accuracy = 14.0)
        ).flatten()

        events shouldBeEqualTo emptyList()
    }

    @Test
    fun route_whenLocationFixIsReplayedOrOutOfOrder_thenDoesNotCountItTwice() {
        val route = RouteHarness(listOf(campus))

        route.process(37.7750, -122.4194, accuracy = 5.0, elapsedRealtimeNanos = 2L)
        route.process(37.7750, -122.4194, accuracy = 5.0, elapsedRealtimeNanos = 2L)
        route.process(37.7750, -122.4194, accuracy = 5.0, elapsedRealtimeNanos = 1L)
        route.process(37.7750, -122.4194, accuracy = 5.0, elapsedRealtimeNanos = 3L)

        route.process(
            37.7750,
            -122.4194,
            accuracy = 5.0,
            elapsedRealtimeNanos = 4L
        ) shouldBeEqualTo listOf(PolygonTransitionDetection("campus", PolygonTransition.ENTER))
    }

    @Test
    fun route_whenAccuracyIsPoorNearBoundary_thenWaitsForClearEvidence() {
        val route = RouteHarness(listOf(campus))

        val uncertainEvents = listOf(
            route.process(37.7750, -122.41885, accuracy = 100.0),
            route.process(37.7750, -122.41885, accuracy = 100.0),
            route.process(37.7750, -122.41885, accuracy = 100.0)
        ).flatten()
        val clearEvents = listOf(
            route.process(37.7750, -122.4194, accuracy = 5.0),
            route.process(37.7750, -122.4194, accuracy = 5.0),
            route.process(37.7750, -122.4194, accuracy = 5.0)
        ).flatten()

        uncertainEvents shouldBeEqualTo emptyList()
        clearEvents shouldBeEqualTo listOf(
            PolygonTransitionDetection("campus", PolygonTransition.ENTER)
        )
    }

    @Test
    fun route_whenPolygonsOverlap_thenTracksEachPolygonIndependently() {
        val eastCampus = PolygonFence(
            id = "east-campus",
            geometry = PolygonGeometry.from(
                listOf(
                    point(37.7745, -122.4196),
                    point(37.7745, -122.4184),
                    point(37.7755, -122.4184),
                    point(37.7755, -122.4196)
                )
            )
        )
        val route = RouteHarness(listOf(campus, eastCampus))

        val events = listOf(
            route.process(37.7750, -122.4194, accuracy = 5.0),
            route.process(37.7750, -122.4194, accuracy = 5.0),
            route.process(37.7750, -122.4194, accuracy = 5.0)
        ).flatten()

        events shouldBeEqualTo listOf(
            PolygonTransitionDetection("campus", PolygonTransition.ENTER),
            PolygonTransitionDetection("east-campus", PolygonTransition.ENTER)
        )
    }

    @Test
    fun route_whenProcessRestarts_thenPendingEvidenceIsDiscardedButCommittedStateSurvives() {
        val durableStates = mutableMapOf("campus" to PolygonCommittedState.OUTSIDE)
        var route = RouteHarness(listOf(campus), durableStates)
        route.process(37.7750, -122.4194, accuracy = 5.0)
        route.process(37.7750, -122.4194, accuracy = 5.0)

        route = RouteHarness(listOf(campus), durableStates)
        val eventsAfterRestart = listOf(
            route.process(37.7750, -122.4194, accuracy = 5.0),
            route.process(37.7750, -122.4194, accuracy = 5.0),
            route.process(37.7750, -122.4194, accuracy = 5.0)
        ).flatten()

        eventsAfterRestart shouldBeEqualTo listOf(
            PolygonTransitionDetection("campus", PolygonTransition.ENTER)
        )
        durableStates["campus"] shouldBeEqualTo PolygonCommittedState.INSIDE
    }

    @Test
    fun route_whenActiveSessionIsRearmed_thenDoesNotReusePendingEvidence() {
        val states = mutableMapOf("campus" to PolygonCommittedState.OUTSIDE)
        val processor = PolygonRouteProcessor()

        processor.process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4194), 5.0),
            elapsedRealtimeNanos = 1L,
            committedStates = states
        )
        processor.clear()
        val detections = (1L..3L).flatMap { sequence ->
            processor.process(
                fences = listOf(campus),
                sample = PolygonLocationSample(point(37.7750, -122.4194), 5.0),
                elapsedRealtimeNanos = sequence,
                committedStates = states
            )
        }

        detections shouldBeEqualTo listOf(
            PolygonTransitionDetection("campus", PolygonTransition.ENTER)
        )
    }

    @Test
    fun route_whenSparseFixesJumpAcrossPolygon_thenCannotObserveTransit() {
        val route = RouteHarness(listOf(campus))

        val events = listOf(
            route.process(37.7750, -122.4205, accuracy = 5.0),
            route.process(37.7750, -122.4182, accuracy = 5.0)
        ).flatten()

        events shouldBeEqualTo emptyList()
    }

    @Test
    fun route_whenNarrowPolygonHasOnlyTwoInsideFixes_thenDoesNotConfirmEntry() {
        val route = RouteHarness(listOf(campus))

        val events = listOf(
            route.process(37.7750, -122.4205, accuracy = 5.0),
            route.process(37.7750, -122.4197, accuracy = 5.0),
            route.process(37.7750, -122.4191, accuracy = 5.0),
            route.process(37.7750, -122.4182, accuracy = 5.0)
        ).flatten()

        events shouldBeEqualTo emptyList()
    }

    private class RouteHarness(
        private val fences: List<PolygonFence>,
        initialStates: Map<String, PolygonCommittedState> = emptyMap()
    ) {
        private val processor = PolygonRouteProcessor()
        private val committedStates = if (initialStates is MutableMap) {
            initialStates
        } else {
            initialStates.toMutableMap()
        }
        private var elapsedRealtimeNanos = 0L

        fun process(
            latitude: Double,
            longitude: Double,
            accuracy: Double,
            elapsedRealtimeNanos: Long = ++this.elapsedRealtimeNanos
        ): List<PolygonTransitionDetection> {
            this.elapsedRealtimeNanos = maxOf(this.elapsedRealtimeNanos, elapsedRealtimeNanos)
            val detections = processor.process(
                fences = fences,
                sample = PolygonLocationSample(point(latitude, longitude), accuracy),
                elapsedRealtimeNanos = elapsedRealtimeNanos,
                committedStates = committedStates
            )
            detections.forEach { detection ->
                committedStates[detection.polygonId] = when (detection.transition) {
                    PolygonTransition.ENTER -> PolygonCommittedState.INSIDE
                    PolygonTransition.EXIT -> PolygonCommittedState.OUTSIDE
                }
            }
            return detections
        }
    }

    private companion object {
        fun point(latitude: Double, longitude: Double) =
            PolygonCoordinate(latitude = latitude, longitude = longitude)
    }
}
