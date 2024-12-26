package io.customer.datapipelines.plugins

import android.app.Activity
import com.segment.analytics.kotlin.core.Analytics
import io.customer.datapipelines.testutils.core.JUnitTest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AutomaticApplicationLifecycleTrackingPluginTest : JUnitTest() {

    private val mockAnalytics = mockk<Analytics>()

    private val subject = AutomaticApplicationLifecycleTrackingPlugin()

    @BeforeEach
    fun beforeEach() {
        every { mockAnalytics.track(any()) } just runs
        subject.setup(mockAnalytics)
    }

    @Test
    fun `GIVEN app is closed WHEN app is opened THEN ApplicationForegrounded is tracked`() {
        simulateAppOpened()

        verify(exactly = 1) { mockAnalytics.track("Application Foregrounded") }
    }

    @Test
    fun `GIVEN app is open WHEN app is put to background THEN ApplicationForegrounded is tracked only once`() {
        simulateAppOpened()
        simulateActivityClosing(false)

        verify(exactly = 1) { mockAnalytics.track("Application Foregrounded") }
    }

    @Test
    fun `GIVEN app is open WHEN app is put to background AND reopened THEN ApplicationForegrounded is tracked twice`() {
        simulateAppOpened()
        simulateActivityClosing(false)
        simulateAppOpened()

        verify(exactly = 2) { mockAnalytics.track("Application Foregrounded") }
    }

    @Test
    fun `GIVEN app is open WHEN configuration changes THEN ApplicationForegrounded is tracked only once`() {
        simulateAppOpened()
        simulateConfigurationChange()

        verify(exactly = 1) { mockAnalytics.track("Application Foregrounded") }
    }

    @Test
    fun `GIVEN app is open WHEN configuration changes AND app is put to background THEN ApplicationForegrounded is tracked only once`() {
        simulateAppOpened()
        simulateConfigurationChange()
        simulateActivityClosing(false)

        verify(exactly = 1) { mockAnalytics.track("Application Foregrounded") }
    }

    @Test
    fun `GIVEN app is open WHEN app is reopened after config changes THEN ApplicationForegrounded is tracked twice`() {
        simulateAppOpened()
        simulateConfigurationChange()
        simulateActivityClosing(false)
        simulateAppOpened()

        verify(exactly = 2) { mockAnalytics.track("Application Foregrounded") }
    }

    private fun simulateAppOpened() {
        subject.onActivityStarted(null)
    }

    private fun simulateActivityClosing(isConfigChanging: Boolean) {
        val activity = mockk<Activity> {
            every { isChangingConfigurations } returns isConfigChanging
        }
        subject.onActivityStopped(activity)
    }

    private fun simulateConfigurationChange() {
        simulateActivityClosing(true)
        simulateAppOpened()
    }
}
