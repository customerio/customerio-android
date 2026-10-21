package io.customer.geofence.replay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayBoundaryGateTest {
    @Test
    fun advance_whenBoundaryOwnerIsCancelled_thenDoesNotAdvanceToItsAnswer() =
        runTest {
            val clock = VirtualClock()
            val gate = ReplayBoundaryGate(clock)
            val request = backgroundScope.launch { gate.awaitVirtual(20.0, "dead-process-fetch") }
            runCurrent()

            request.cancel()
            runCurrent()
            gate.releaseAll { runCurrent() }

            clock.elapsedSeconds shouldBeEqualTo 0.0
        }

    @Test
    fun advance_whileSuspended_thenHoldsAnswerAndReleasesItAtResumeTime() =
        runTest {
            val clock = VirtualClock()
            val gate = ReplayBoundaryGate(clock)
            var answeredAt: Double? = null
            backgroundScope.launch {
                gate.awaitVirtual(20.0, "fetch")
                answeredAt = clock.elapsedSeconds
            }
            runCurrent()

            gate.suspendExecution()
            gate.advanceTo(300.0) { runCurrent() }
            runCurrent()
            answeredAt shouldBeEqualTo null
            clock.elapsedSeconds shouldBeEqualTo 300.0

            gate.resumeExecution()
            gate.advanceTo(clock.elapsedSeconds) { runCurrent() }

            // Resume time, not the recorded 20: nothing could run while the answer was due.
            answeredAt shouldBeEqualTo 300.0
        }

    @Test
    fun releaseAll_whenScenarioEndsSuspended_thenAnswerStaysHeld() =
        runTest {
            val clock = VirtualClock()
            val gate = ReplayBoundaryGate(clock)
            var answered = false
            backgroundScope.launch {
                gate.awaitVirtual(20.0, "fetch")
                answered = true
            }
            runCurrent()

            gate.suspendExecution()
            gate.releaseAll { runCurrent() }
            runCurrent()

            answered shouldBeEqualTo false
            gate.heldAtEnd shouldBeEqualTo listOf("fetch")
            clock.elapsedSeconds shouldBeEqualTo 0.0
        }
}
