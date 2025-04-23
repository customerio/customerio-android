package io.customer.datapipelines.plugins

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.util.UiThreadRunner
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class AutomaticApplicationLifecycleTrackingPluginTest : JUnitTest() {

    private val mockProcessLifecycleOwner = mockk<LifecycleOwner>()
    private val mockUiThreadRunner = mockk<UiThreadRunner>()
    private val mockAnalytics by lazy { analytics }

    private val lifecycleObserverCaptor = slot<DefaultLifecycleObserver>()

    private val subject = AutomaticApplicationLifecycleTrackingPlugin(mockProcessLifecycleOwner, mockUiThreadRunner)

    @BeforeEach
    fun beforeEach() {
        val uiThreadRunnerCaptor = slot<() -> Unit>()
        every { mockUiThreadRunner.run(capture(uiThreadRunnerCaptor)) } answers { uiThreadRunnerCaptor.captured.invoke() }

        val mockLifecycle = mockk<Lifecycle>()
        every { mockProcessLifecycleOwner.lifecycle } returns mockLifecycle
        every { mockLifecycle.addObserver(capture(lifecycleObserverCaptor)) } just runs

        every { mockAnalytics.track(any()) } just runs
        subject.setup(mockAnalytics)
    }

    @Test
    fun onCreate_expectApplicationForegroundedEventToNotBeTracked() {
        ensureLifecycleObserverAdded()
        lifecycleObserverCaptor.captured.onCreate(mockk())

        assertCalledNever { mockAnalytics.track("Application Foregrounded") }
    }

    @Test
    fun onStart_expectApplicationForegroundedEventToBeTracked() {
        ensureLifecycleObserverAdded()
        lifecycleObserverCaptor.captured.onStart(mockk())

        assertCalledOnce { mockAnalytics.track("Application Foregrounded") }
    }

    @Test
    fun onResume_expectApplicationForegroundedEventToNotBeTracked() {
        ensureLifecycleObserverAdded()
        lifecycleObserverCaptor.captured.onResume(mockk())

        assertCalledNever { mockAnalytics.track("Application Foregrounded") }
    }

    @Test
    fun onPause_expectApplicationForegroundedEventToNotBeTracked() {
        ensureLifecycleObserverAdded()
        lifecycleObserverCaptor.captured.onPause(mockk())

        assertCalledNever { mockAnalytics.track("Application Foregrounded") }
    }

    @Test
    fun onStop_expectApplicationForegroundedEventToNotBeTracked() {
        ensureLifecycleObserverAdded()
        lifecycleObserverCaptor.captured.onStop(mockk())

        assertCalledNever { mockAnalytics.track("Application Foregrounded") }
    }

    @Test
    fun onDestroy_expectApplicationForegroundedEventToNotBeTracked() {
        ensureLifecycleObserverAdded()
        lifecycleObserverCaptor.captured.onDestroy(mockk())

        assertCalledNever { mockAnalytics.track("Application Foregrounded") }
    }

    private fun ensureLifecycleObserverAdded() {
        if (!lifecycleObserverCaptor.isCaptured) {
            fail("Lifecycle observer was not added!")
        }
    }
}
