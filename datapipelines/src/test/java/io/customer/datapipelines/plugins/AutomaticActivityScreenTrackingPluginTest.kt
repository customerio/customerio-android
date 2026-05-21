package io.customer.datapipelines.plugins

import android.app.Activity
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.tracking.TrackableScreen
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the name-only dedup behavior in [AutomaticActivityScreenTrackingPlugin].
 *
 * Parity reference: iOS `InMemoryAutoTrackingScreenViewStore` in
 * `AutoTrackingScreenViews.swift` (~lines 250-268). Comparison is name-only,
 * case-sensitive, in-memory, scoped to the plugin instance lifetime.
 *
 * Captures screen emissions by mocking [EventBus] — `CustomerIO.instance().screen(name)`
 * publishes [Event.ScreenViewedEvent] to the bus, so a single observed publish per
 * name confirms a single emission. The concurrent test mirrors the style of
 * `ConcurrentScreenViewTest` (latch-coordinated thread pool).
 */
class AutomaticActivityScreenTrackingPluginTest : JUnitTest() {

    private val capturedEvents = mutableListOf<Event.ScreenViewedEvent>()
    private val capturedEventsLock = Any()

    private lateinit var plugin: AutomaticActivityScreenTrackingPlugin

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                diGraph {
                    sdk {
                        val mockEventBus = mockk<EventBus>(relaxed = true)
                        val eventSlot = slot<Event.ScreenViewedEvent>()
                        every { mockEventBus.publish(capture(eventSlot)) } answers {
                            synchronized(capturedEventsLock) {
                                capturedEvents.add(eventSlot.captured)
                            }
                        }
                        overrideDependency<EventBus>(mockEventBus)
                    }
                }
            }
        )

        capturedEvents.clear()
        plugin = AutomaticActivityScreenTrackingPlugin().apply {
            analytics = this@AutomaticActivityScreenTrackingPluginTest.analytics
        }
    }

    private fun activityForScreen(name: String?): Activity {
        // Use TrackableScreen branch so we avoid touching PackageManager — the dedup
        // logic is independent of how the screen name is resolved. `relaxed = true`
        // lets the unused `activity.packageManager` access at the top of
        // `onActivityStarted` return a relaxed default instead of crashing.
        return mockk<Activity>(
            relaxed = true,
            moreInterfaces = arrayOf(TrackableScreen::class)
        ).also { activity ->
            every { (activity as TrackableScreen).getScreenName() } returns name
        }
    }

    private fun publishedScreenNames(): List<String> =
        synchronized(capturedEventsLock) { capturedEvents.map { it.name }.toList() }

    @Test
    fun onActivityStarted_givenSameScreenTwice_emitsOnce() {
        val activity = activityForScreen("Home")

        plugin.onActivityStarted(activity)
        plugin.onActivityStarted(activity)

        assertEquals(listOf("Home"), publishedScreenNames())
    }

    @Test
    fun onActivityStarted_givenDifferentScreens_emitsEach() {
        plugin.onActivityStarted(activityForScreen("Home"))
        plugin.onActivityStarted(activityForScreen("Profile"))
        plugin.onActivityStarted(activityForScreen("Settings"))

        assertEquals(listOf("Home", "Profile", "Settings"), publishedScreenNames())
    }

    @Test
    fun onActivityStarted_givenSameScreenAfterDifferent_emitsAgain() {
        plugin.onActivityStarted(activityForScreen("Home"))
        plugin.onActivityStarted(activityForScreen("Profile"))
        plugin.onActivityStarted(activityForScreen("Home"))

        // Home re-emits because the last tracked screen was Profile.
        assertEquals(listOf("Home", "Profile", "Home"), publishedScreenNames())
    }

    @Test
    fun onActivityStarted_givenSameNameAcrossThreads_emitsOnce() {
        // Probes the @Synchronized guard on shouldSkipDuplicate by hammering the same
        // name from many threads at once. Without the guard, the read-compare-write on
        // lastScreenTracked races: two threads can both observe lastScreenTracked != name,
        // both decide "not duplicate", both emit. With the guard, exactly one emission
        // wins regardless of contention.
        val threadCount = 16
        val callsPerThread = 50
        val sharedActivity = activityForScreen("Home")

        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                startLatch.await()
                repeat(callsPerThread) { plugin.onActivityStarted(sharedActivity) }
                doneLatch.countDown()
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.SECONDS)

        assertEquals(listOf("Home"), publishedScreenNames())
    }
}
