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
    fun onActivityStarted_concurrent_isThreadSafe() {
        // Mirrors the style of ConcurrentScreenViewTest.verifyScreenMethodIsThreadSafe:
        // multiple threads fire onActivityStarted simultaneously, then we assert the
        // dedup guard remained internally consistent under contention.
        val threadCount = 5
        val eventsPerThread = 3
        val totalUniqueScreens = threadCount * eventsPerThread

        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(totalUniqueScreens)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { threadId ->
            executor.submit {
                readyLatch.countDown()
                startLatch.await()
                repeat(eventsPerThread) { eventIndex ->
                    val screenName = "Screen_${eventIndex}_Thread_$threadId"
                    plugin.onActivityStarted(activityForScreen(screenName))
                    // Fire the same screen again — the dedup guard must collapse this
                    // when it remains the "last tracked" on this plugin instance, but
                    // interleaved threads may race; the invariant is "each unique name
                    // emits at least once and the bus never sees an inconsistent state".
                    plugin.onActivityStarted(activityForScreen(screenName))
                    completionLatch.countDown()
                    Thread.sleep(10)
                }
            }
        }

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
        startLatch.countDown()
        assertTrue(completionLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.SECONDS)

        val expectedScreenNames = mutableSetOf<String>()
        repeat(threadCount) { threadId ->
            repeat(eventsPerThread) { eventIndex ->
                expectedScreenNames.add("Screen_${eventIndex}_Thread_$threadId")
            }
        }

        val emitted = publishedScreenNames()
        // Each unique screen name must appear at least once. Same-name back-to-back calls
        // are deduped by the guard, but interleaving from other threads can change the
        // "last tracked" value between the two same-name calls on a given thread, so
        // we don't constrain the upper bound here — the threadsafe guarantee under test
        // is structural integrity (no exceptions, no lost names).
        assertEquals(expectedScreenNames, emitted.toSet())
        assertTrue(
            emitted.size in totalUniqueScreens..(totalUniqueScreens * 2),
            "emitted=${emitted.size} expected between $totalUniqueScreens and ${totalUniqueScreens * 2}"
        )
    }
}
