package io.customer.datapipelines.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.datapipelines.testutils.core.JUnitTest
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class ProcessLifecycleForegroundStateTest : JUnitTest() {

    @Test
    fun constructor_expectsLifecycleOwnerNotResolved() {
        var providerCalled = false

        ProcessLifecycleForegroundState(
            processLifecycleOwnerProvider = {
                providerCalled = true
                mockk()
            }
        )

        providerCalled shouldBeEqualTo false
    }

    @Test
    fun isInForeground_givenStartedState_expectsTrue() {
        ProcessLifecycleForegroundState(processLifecycleOwnerProvider = ownerInState(Lifecycle.State.STARTED))
            .isInForeground shouldBeEqualTo true
    }

    @Test
    fun isInForeground_givenResumedState_expectsTrue() {
        ProcessLifecycleForegroundState(processLifecycleOwnerProvider = ownerInState(Lifecycle.State.RESUMED))
            .isInForeground shouldBeEqualTo true
    }

    @Test
    fun isInForeground_givenCreatedState_expectsFalse() {
        ProcessLifecycleForegroundState(processLifecycleOwnerProvider = ownerInState(Lifecycle.State.CREATED))
            .isInForeground shouldBeEqualTo false
    }

    @Test
    fun isInForeground_givenDestroyedState_expectsFalse() {
        ProcessLifecycleForegroundState(processLifecycleOwnerProvider = ownerInState(Lifecycle.State.DESTROYED))
            .isInForeground shouldBeEqualTo false
    }

    private fun ownerInState(state: Lifecycle.State): () -> LifecycleOwner = {
        val mockLifecycle = mockk<Lifecycle>()
        every { mockLifecycle.currentState } returns state
        mockk<LifecycleOwner>().apply {
            every { lifecycle } returns mockLifecycle
        }
    }
}
