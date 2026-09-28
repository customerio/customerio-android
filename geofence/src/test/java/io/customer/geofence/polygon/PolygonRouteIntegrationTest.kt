package io.customer.geofence.polygon

import io.customer.geofence.GeofenceDiagnostics
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.amshove.kluent.shouldNotBeEmpty
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
        val records = PolygonRouteProcessor().process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4202), 45.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = mapOf("campus" to PolygonCommittedState.INSIDE)
        ).records

        records.filterIsInstance<PolygonRouteRecord.Undecided>().size shouldBeEqualTo 1
    }

    @Test
    fun process_givenTheSameFixAsAnArrival_expectNoUndecidedRecordBecauseItDecides() {
        // The asymmetry: at the same 45 m accuracy, a fix 52 m inside decides an arrival even
        // though accuracy plus the departure margin exceeds its clearance.
        val outcome = PolygonRouteProcessor().process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4194), 45.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        )

        outcome.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
        outcome.records.filterIsInstance<PolygonRouteRecord.Undecided>().shouldBeEmpty()
    }

    @Test
    fun process_givenAFixTooInaccurateToEvaluate_expectAnUndecidedRecord() {
        val records = PolygonRouteProcessor().process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4194), 80.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        ).records

        records.filterIsInstance<PolygonRouteRecord.Undecided>().size shouldBeEqualTo 1
    }

    @Test
    fun process_givenADecisiveFixThatAgreesWithTheCommittedState_expectNoUndecidedRecord() {
        // A device sitting still inside a polygon produces one of these per fix. They are not
        // undecidable, and recording them would bury the fixes that genuinely could not be judged.
        val records = PolygonRouteProcessor().process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4194), 30.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = mapOf("campus" to PolygonCommittedState.INSIDE)
        ).records

        records.filterIsInstance<PolygonRouteRecord.Undecided>().shouldBeEmpty()
    }

    @Test
    fun process_givenAnUndecidedFixInsideThePolygon_expectAPositiveEdge() {
        // Inside the ring but too coarse to judge: the accuracy ceiling is the only way an interior
        // fix is undecided.
        val record = undecidedRecordsFor(point(37.7750, -122.4194), accuracyMeters = 60.0)

        record.edgeMeters() shouldBeInRange 45.0..60.0
    }

    @Test
    fun process_givenAnUndecidedFixOutsideThePolygon_expectANegativeEdge() {
        // Roughly 18 m outside, mid-departure. Unsigned, this row and the one above would be
        // indistinguishable.
        val record = undecidedRecordsFor(
            point(37.7750, -122.4202),
            committedStates = mapOf("campus" to PolygonCommittedState.INSIDE)
        )

        record.edgeMeters() shouldBeInRange -25.0..-10.0
    }

    @Test
    fun process_givenAnUndecidedFix_expectTheAgeOfTheFixThatWasJudged() {
        val record = undecidedRecordsFor(
            point(37.7750, -122.4202),
            committedStates = mapOf("campus" to PolygonCommittedState.INSIDE),
            fixAgeSeconds = 7.5
        )

        record.fixAgeSeconds shouldBeEqualTo 7.5
    }

    @Test
    fun process_givenTwoMarginalFixesMinutesApart_expectNoArrivalFromCombiningThem() {
        // Marginal fixes five minutes apart must not combine into an arrival: walking past a shop
        // twice is not a visit.
        val processor = PolygonRouteProcessor()
        val marginal = PolygonLocationSample(point(37.77452, -122.4194), 30.0)

        processor.process(
            fences = listOf(campus),
            sample = marginal,
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        ).detections.shouldBeEmpty()

        // A distinct position, so this is a genuine second measurement and only the window keeps
        // the two passes apart.
        processor.process(
            fences = listOf(campus),
            sample = marginal.resampled(),
            elapsedRealtimeNanos = 1L + FIVE_MINUTES_NANOS,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        ).detections.shouldBeEmpty()
    }

    @Test
    fun process_givenTwoMarginalFixesOneSampleApart_expectTheArrivalStillCommits() {
        // The control for the test above: expiry must not quietly disable corroboration. Two fixes
        // one sampling interval apart are the same visit and must still complete the arrival.
        val processor = PolygonRouteProcessor()
        val marginal = PolygonLocationSample(point(37.77452, -122.4194), 30.0)

        processor.process(
            fences = listOf(campus),
            sample = marginal,
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        ).detections.shouldBeEmpty()

        val detections = processor.process(
            fences = listOf(campus),
            sample = marginal.resampled(),
            elapsedRealtimeNanos = 1L + FIFTEEN_SECONDS_NANOS,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        ).detections

        detections.map(PolygonTransitionDetection::transition) shouldBeEqualTo
            listOf(PolygonTransition.ENTER)
    }

    @Test
    fun process_givenTheSameMarginalFixTwice_expectItCannotCorroborateItself() {
        // An echo under the same stamp is refused by the per-fence monotonic check, so a marginal
        // arrival cannot confirm itself from one observation. In a capture an echo would look like
        // a real second opinion.
        val processor = PolygonRouteProcessor()
        val marginal = PolygonLocationSample(point(37.77452, -122.4194), 30.0)

        repeat(2) {
            processor.process(
                fences = listOf(campus),
                sample = marginal,
                elapsedRealtimeNanos = 1L,
                fixAgeSeconds = 0.0,
                committedStates = emptyMap()
            ).detections.shouldBeEmpty()
        }
    }

    @Test
    fun process_givenAnArrivalThatDecidesAlone_expectADecidedRecordWithItsEvidence() {
        // Counterpart to the undecided records above, so a capture shows the fixes a margin
        // accepted as well as the ones it refused.
        val record = PolygonRouteProcessor().process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4194), 5.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 2.5,
            committedStates = emptyMap()
        ).records.decided()

        record.transitionName shouldBeEqualTo "ENTER"
        record.corroborated shouldBeEqualTo false
        record.horizontalAccuracyMeters shouldBeEqualTo 5.0
        record.fixAgeSeconds shouldBeEqualTo 2.5
        // Positive because the fix is inside, the same sign convention the undecided rows use.
        record.edgeMeters() shouldBeInRange 30.0..60.0
    }

    @Test
    fun process_givenAnArrivalThatNeededASecondFix_expectTheRecordOnlyWithTheTransition() {
        // A marginal arrival: the first fix decides nothing and must not be recorded as a decision,
        // the second commits it. `corroborated` separates the two populations in a capture.
        val processor = PolygonRouteProcessor()
        val marginal = PolygonLocationSample(point(37.77452, -122.4194), 30.0)

        val first = processor.process(
            fences = listOf(campus),
            sample = marginal,
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        ).records
        first.filterIsInstance<PolygonRouteRecord.Decided>().shouldBeEmpty()

        val second = processor.process(
            fences = listOf(campus),
            sample = marginal.resampled(),
            elapsedRealtimeNanos = 2L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        ).records

        second.decided().transitionName shouldBeEqualTo "ENTER"
        second.decided().corroborated shouldBeEqualTo true
    }

    @Test
    fun process_givenADepartureThatClearsTheMargin_expectANegativeEdgeOnTheRecord() {
        val record = PolygonRouteProcessor().process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4230), 5.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = mapOf("campus" to PolygonCommittedState.INSIDE)
        ).records.decided()

        record.transitionName shouldBeEqualTo "EXIT"
        record.edgeMeters() shouldBeInRange -300.0..-1.0
    }

    @Test
    fun process_givenADecisiveFixThatAgreesWithCommittedState_expectItRecordedNotSilent() {
        // The fixes a margin let through, which are most fixes, must leave a record too, not only
        // refusals and transitions.
        val records = PolygonRouteProcessor().process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4230), 5.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 3.5,
            committedStates = emptyMap()
        ).records
        val record = records.filterIsInstance<PolygonRouteRecord.Unchanged>().single()

        record.membership shouldBeEqualTo "OUTSIDE"
        record.horizontalAccuracyMeters shouldBeEqualTo 5.0
        record.fixAgeSeconds shouldBeEqualTo 3.5
        // Signed negative: decisively outside, and committed outside, so nothing changes.
        requireNotNull(record.signedBoundaryDistanceMeters) shouldBeInRange -400.0..-1.0
        // It is not a decision and must not be counted as one.
        records.filterIsInstance<PolygonRouteRecord.Decided>().shouldBeEmpty()
    }

    @Test
    fun process_givenAnArrivalThatCommits_expectNoUnchangedRecordForThatFix() {
        // The control: a fix that changes a belief is a decision, not an agreement. Without this,
        // logging both on every fix would pass the test above just as well.
        val records = PolygonRouteProcessor().process(
            fences = listOf(campus),
            sample = PolygonLocationSample(point(37.7750, -122.4194), 5.0),
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        ).records

        records.filterIsInstance<PolygonRouteRecord.Unchanged>().shouldBeEmpty()
        records.decided().transitionName shouldBeEqualTo "ENTER"
    }

    private fun undecidedRecordsFor(
        coordinate: PolygonCoordinate,
        committedStates: Map<String, PolygonCommittedState> = emptyMap(),
        accuracyMeters: Double = 45.0,
        fixAgeSeconds: Double = 0.0
    ): PolygonRouteRecord.Undecided = PolygonRouteProcessor().process(
        fences = listOf(campus),
        sample = PolygonLocationSample(coordinate, accuracyMeters),
        elapsedRealtimeNanos = 1L,
        fixAgeSeconds = fixAgeSeconds,
        committedStates = committedStates
    ).records.filterIsInstance<PolygonRouteRecord.Undecided>().single()

    private fun List<PolygonRouteRecord>.decided(): PolygonRouteRecord.Decided =
        filterIsInstance<PolygonRouteRecord.Decided>().single()

    /** A record reaching an assertion without an edge distance is a test bug, so fail loudly. */
    private fun PolygonRouteRecord.Decided.edgeMeters(): Double =
        requireNotNull(signedBoundaryDistanceMeters) { "decided record carried no edge distance" }

    private fun PolygonRouteRecord.Undecided.edgeMeters(): Double =
        requireNotNull(signedBoundaryDistanceMeters) { "undecided record carried no edge distance" }

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

        // Marginal fixes: a decisive fix commits at once and the committed state then absorbs every
        // replay, hiding the guard. Here a replay that slipped through would settle the hold into
        // an arrival, so only the monotonic stamp check keeps this empty.
        val detections = listOf(2L to 30.0, 2L to 29.0, 1L to 28.0).flatMap { (elapsed, accuracy) ->
            route.process(37.77452, -122.4194, accuracy = accuracy, elapsedRealtimeNanos = elapsed)
        }
        detections.shouldBeEmpty()

        route.process(
            37.77452 + RESAMPLE_LATITUDE_DELTA,
            -122.4194,
            accuracy = 30.0,
            elapsedRealtimeNanos = 3L
        ) shouldBeEqualTo listOf(PolygonTransitionDetection("campus", PolygonTransition.ENTER))
    }

    @Test
    fun route_whenOlderBatchActivatesAnotherPolygon_thenUsesThatPolygonsOwnTimeline() {
        val eastCampus = campus.copy(id = "east-campus")
        val processor = PolygonRouteProcessor()
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
            ).detections
        }

        // The guarantee is per-fence: campus refuses these fixes as older than the one it already
        // saw at 100, east-campus has never seen one and must not inherit campus's timeline.
        detections.map(PolygonTransitionDetection::polygonId).distinct() shouldBeEqualTo
            listOf("east-campus")
        detections.shouldNotBeEmpty()
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
        // Marginal fixes, because they are the only ones that leave anything pending: a decisive
        // fix commits on its own and has no evidence to carry across a restart.
        val durableStates = mutableMapOf("campus" to PolygonCommittedState.OUTSIDE)
        var route = RouteHarness(listOf(campus), durableStates)
        route.process(37.77452, -122.4194, accuracy = 30.0).shouldBeEmpty()

        route = RouteHarness(listOf(campus), durableStates)
        // If the pending arrival had survived, this single fix would complete it.
        route.process(37.77452, -122.4194, accuracy = 30.0).shouldBeEmpty()
        val eventsAfterRestart =
            route.process(37.77452 + RESAMPLE_LATITUDE_DELTA, -122.4194, accuracy = 30.0)

        eventsAfterRestart shouldBeEqualTo listOf(
            PolygonTransitionDetection("campus", PolygonTransition.ENTER)
        )
        durableStates["campus"] shouldBeEqualTo PolygonCommittedState.INSIDE
    }

    @Test
    fun route_whenActiveSessionIsRearmed_thenDoesNotReusePendingEvidence() {
        val states = mutableMapOf("campus" to PolygonCommittedState.OUTSIDE)
        val processor = PolygonRouteProcessor()

        // Marginal, so the first fix leaves a pending arrival rather than committing outright.
        val marginal = PolygonLocationSample(point(37.77452, -122.4194), 30.0)
        processor.process(
            fences = listOf(campus),
            sample = marginal,
            elapsedRealtimeNanos = 1L,
            fixAgeSeconds = 0.0,
            committedStates = states
        )
        processor.clear()

        fun sample(at: Long, fix: PolygonLocationSample) = processor.process(
            fences = listOf(campus),
            sample = fix,
            elapsedRealtimeNanos = at,
            fixAgeSeconds = 0.0,
            committedStates = states
        ).detections

        // Asserted per fix: reused pending evidence would still give one arrival over the pair,
        // just from the first fix.
        sample(1L, marginal).shouldBeEmpty()
        sample(2L, marginal.resampled()) shouldBeEqualTo listOf(
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
                fixAgeSeconds = 0.0,
                committedStates = committedStates
            ).detections
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

        /** About 2 m north, so a resampled fix is independent rather than a re-delivery. */
        const val RESAMPLE_LATITUDE_DELTA = 0.000018

        fun point(latitude: Double, longitude: Double) =
            PolygonCoordinate(latitude = latitude, longitude = longitude)

        fun PolygonLocationSample.resampled() = copy(
            coordinate = coordinate.copy(latitude = coordinate.latitude + RESAMPLE_LATITUDE_DELTA)
        )
    }
}
