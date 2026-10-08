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
}
