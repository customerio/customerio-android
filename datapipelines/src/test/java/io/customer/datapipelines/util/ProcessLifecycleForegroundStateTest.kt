package io.customer.datapipelines.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.commontest.config.TestConfig
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.datapipelines.testutils.core.JUnitTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class ProcessLifecycleForegroundStateTest : JUnitTest() {

    private val mockOwner = mockk<LifecycleOwner>()
    private val mockLifecycle = mockk<Lifecycle>()

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        every { mockOwner.lifecycle } returns mockLifecycle
    }

    private fun newState() = ProcessLifecycleForegroundState(
        processLifecycleOwner = mockOwner,
        dispatchersProvider = DispatchersProviderStub()
    )

    @Test
    fun isInForeground_givenStartedState_expectsTrue() = runTest {
        every { mockLifecycle.currentState } returns Lifecycle.State.STARTED

        newState().isInForeground() shouldBeEqualTo true
    }

    @Test
    fun isInForeground_givenResumedState_expectsTrue() = runTest {
        every { mockLifecycle.currentState } returns Lifecycle.State.RESUMED

        newState().isInForeground() shouldBeEqualTo true
    }

    @Test
    fun isInForeground_givenInitializedState_expectsFalse() = runTest {
        every { mockLifecycle.currentState } returns Lifecycle.State.INITIALIZED

        newState().isInForeground() shouldBeEqualTo false
    }

    @Test
    fun isInForeground_givenCreatedState_expectsFalse() = runTest {
        every { mockLifecycle.currentState } returns Lifecycle.State.CREATED

        newState().isInForeground() shouldBeEqualTo false
    }

    @Test
    fun isInForeground_givenDestroyedState_expectsFalse() = runTest {
        every { mockLifecycle.currentState } returns Lifecycle.State.DESTROYED

        newState().isInForeground() shouldBeEqualTo false
    }
}
