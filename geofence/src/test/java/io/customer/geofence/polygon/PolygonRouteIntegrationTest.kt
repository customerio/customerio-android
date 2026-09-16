package io.customer.geofence.polygon

import io.customer.geofence.GeofenceDiagnostics
import io.customer.geofence.GeofenceLogger
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.junit.After
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
    fun process_givenADepartureFixThatCannotSeparateInsideFromOutside_expectAnUndecidedRecord() {
        // Roughly 18 m outside the nearest edge, with an accuracy circle plus the departure margin
        // that reaches back past it. Evaluated, usable, and still not enough to end the visit.
        val capturing = CapturingLogger()
        val processor = PolygonRouteProcessor(logger = GeofenceLogger(capturing))

        processor.process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4202), 45.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = mapOf("campus" to PolygonCommittedState.INSIDE),
            evidencePolicy = PolygonEvidencePolicy.DECISIVE_SINGLE_FIX
        )

        capturing.messages.count { it.contains("undecided") } shouldBeEqualTo 1
    }

    @Test
    fun process_givenTheSameFixAsAnArrival_expectNoUndecidedRecordBecauseItDecides() {
        // The asymmetry, stated as a pair with the test above: identical geometry, identical
        // accuracy, opposite committed state. Arrival asks only which side the fix is on.
        val capturing = CapturingLogger()
        val processor = PolygonRouteProcessor(logger = GeofenceLogger(capturing))

        val detections = processor.process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4194), 45.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap(),
            evidencePolicy = PolygonEvidencePolicy.DECISIVE_SINGLE_FIX
        )

        detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
        capturing.messages.count { it.contains("undecided") } shouldBeEqualTo 0
    }

    @Test
    fun process_givenAFixTooInaccurateToEvaluate_expectAnUndecidedRecord() {
        val capturing = CapturingLogger()
        val processor = PolygonRouteProcessor(logger = GeofenceLogger(capturing))

        processor.process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4194), 80.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap(),
            evidencePolicy = PolygonEvidencePolicy.DECISIVE_SINGLE_FIX
        )

        capturing.messages.count { it.contains("undecided") } shouldBeEqualTo 1
    }

    @Test
    fun process_givenADecisiveFixThatAgreesWithTheCommittedState_expectNoUndecidedRecord() {
        // A device sitting still inside a polygon produces one of these per fix. They are not
        // undecidable, and recording them would bury the fixes that genuinely could not be judged.
        val capturing = CapturingLogger()
        val processor = PolygonRouteProcessor(logger = GeofenceLogger(capturing))

        processor.process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4194), 30.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = mapOf("campus" to PolygonCommittedState.INSIDE),
            evidencePolicy = PolygonEvidencePolicy.DECISIVE_SINGLE_FIX
        )

        capturing.messages.count { it.contains("undecided") } shouldBeEqualTo 0
    }

    @Test
    fun process_givenAnUndecidedFixInsideThePolygon_expectAPositiveEdge() {
        // Inside the ring but too coarse to judge. Only the accuracy ceiling can produce an
        // undecided record from an interior fix now — arrival itself no longer refuses one.
        val capturing = undecidedRecordsFor(point(37.7750, -122.4194), accuracyMeters = 60.0)

        capturing.undecidedEdgeMeters() shouldBeInRange 45.0..60.0
    }

    @Test
    fun process_givenAnUndecidedFixOutsideThePolygon_expectANegativeEdge() {
        // Roughly 18 m outside the same edge, mid-departure. Unsigned, this row and the one above
        // are indistinguishable, and the margins cannot be calibrated from either.
        val capturing = undecidedRecordsFor(
            point(37.7750, -122.4202),
            committedStates = mapOf("campus" to PolygonCommittedState.INSIDE)
        )

        capturing.undecidedEdgeMeters() shouldBeInRange -25.0..-10.0
    }

    @Test
    fun process_givenAnUndecidedFix_expectTheAgeOfTheFixThatWasJudged() {
        val capturing = undecidedRecordsFor(
            point(37.7750, -122.4202),
            committedStates = mapOf("campus" to PolygonCommittedState.INSIDE),
            fixAgeSeconds = 7.5
        )

        capturing.undecidedField("age") shouldBeEqualTo "7.5"
    }

    @Test
    fun process_givenTwoMarginalFixesMinutesApart_expectNoArrivalFromCombiningThem() {
        // Reported by Shahroz on #882 with a reproduction. The confirmation counts agreeing fixes
        // and stores no time, so a marginal fix on one pass and another on a wake five minutes
        // later completed an arrival that neither pass observed. Walking past a shop twice is not
        // a visit.
        val processor = PolygonRouteProcessor(logger = GeofenceLogger(CapturingLogger()))
        val marginal = PolygonLocationSample(point(37.77452, -122.4194), 30.0)

        processor.process(
            fences = listOf(campus),
            sample = marginal,
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap(),
            evidencePolicy = PolygonEvidencePolicy.DECISIVE_SINGLE_FIX
        ).shouldBeEmpty()

        processor.process(
            fences = listOf(campus),
            sample = marginal,
            elapsedRealtimeNanos = 1L + FIVE_MINUTES_NANOS,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap(),
            evidencePolicy = PolygonEvidencePolicy.DECISIVE_SINGLE_FIX
        ).shouldBeEmpty()
    }

    @Test
    fun process_givenTwoMarginalFixesOneSampleApart_expectTheArrivalStillCommits() {
        // The control for the test above: expiry must not quietly disable corroboration. Two fixes
        // one sampling interval apart are the same visit and must still complete the arrival.
        val processor = PolygonRouteProcessor(logger = GeofenceLogger(CapturingLogger()))
        val marginal = PolygonLocationSample(point(37.77452, -122.4194), 30.0)

        processor.process(
            fences = listOf(campus),
            sample = marginal,
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap(),
            evidencePolicy = PolygonEvidencePolicy.DECISIVE_SINGLE_FIX
        ).shouldBeEmpty()

        val detections = processor.process(
            fences = listOf(campus),
            sample = marginal,
            elapsedRealtimeNanos = 1L + FIFTEEN_SECONDS_NANOS,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap(),
            evidencePolicy = PolygonEvidencePolicy.DECISIVE_SINGLE_FIX
        )

        detections.map(PolygonTransitionDetection::transition) shouldBeEqualTo
            listOf(PolygonTransition.ENTER)
    }

    private fun undecidedRecordsFor(
        coordinate: PolygonCoordinate,
        committedStates: Map<String, PolygonCommittedState> = emptyMap(),
        accuracyMeters: Double = 45.0,
        fixAgeSeconds: Double = 0.0
    ): CapturingLogger {
        GeofenceDiagnostics.setEnabledForTesting(true)
        val capturing = CapturingLogger()
        PolygonRouteProcessor(logger = GeofenceLogger(capturing)).process(
            fences = listOf(campus),
            sample = PolygonLocationSample(coordinate, accuracyMeters),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = fixAgeSeconds,
            committedStates = committedStates,
            evidencePolicy = PolygonEvidencePolicy.DECISIVE_SINGLE_FIX
        )
        return capturing
    }

    private fun CapturingLogger.undecidedEdgeMeters(): Double = undecidedField("edge").toDouble()

    private fun CapturingLogger.undecidedField(key: String): String = messages
        .single { it.contains("ev=polygon.undecided") }
        .substringAfter(" $key=")
        .substringBefore(' ')

    @After
    fun resetDiagnostics() {
        GeofenceDiagnostics.setEnabledForTesting(null)
    }

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
    fun route_whenOlderBatchActivatesAnotherPolygon_thenUsesThatPolygonsOwnTimeline() {
        val eastCampus = campus.copy(id = "east-campus")
        val processor = PolygonRouteProcessor(logger = GeofenceLogger(CapturingLogger()))
        val inside = PolygonLocationSample(point(37.7750, -122.4194), 5.0)

        processor.process(
            fences = listOf(campus),
            sample = inside,
            elapsedRealtimeNanos = 100L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        )
        val detections = listOf(10L, 20L, 30L).flatMap { timestamp ->
            processor.process(
                fences = listOf(campus, eastCampus),
                sample = inside,
                elapsedRealtimeNanos = timestamp,
                fixAgeSeconds = 0.0,
                committedStates = emptyMap()
            )
        }

        detections shouldBeEqualTo listOf(
            PolygonTransitionDetection("east-campus", PolygonTransition.ENTER)
        )
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
        val processor = PolygonRouteProcessor(logger = GeofenceLogger(CapturingLogger()))

        processor.process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4194), 5.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = states
        )
        processor.clear()
        val detections = (1L..3L).flatMap { sequence ->
            processor.process(
                fences = listOf(campus),
                sample = PolygonLocationSample(point(37.7750, -122.4194), 5.0),
                elapsedRealtimeNanos = sequence,
                fixAgeSeconds = 0.0,
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
        private val processor = PolygonRouteProcessor(logger = GeofenceLogger(CapturingLogger()))
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
                fixAgeSeconds = 0.0,
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
        const val FIVE_MINUTES_NANOS = 5L * 60 * 1_000_000_000
        const val FIFTEEN_SECONDS_NANOS = 15L * 1_000_000_000

        fun point(latitude: Double, longitude: Double) =
            PolygonCoordinate(latitude = latitude, longitude = longitude)
    }

    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()
        override var logLevel: CioLogLevel = CioLogLevel.DEBUG

        override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit

        override fun info(message: String, tag: String?) = record(message)
        override fun debug(message: String, tag: String?) = record(message)
        override fun error(message: String, tag: String?, throwable: Throwable?) = record(message)

        private fun record(message: String) {
            messages.add(message)
        }
    }
}
