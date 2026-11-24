package io.customer.messaginginapp.gist.presentation.hostwindow

import android.app.Activity
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 28, 35])
class HostActivityTrackerTest {
    private val resumedCalls = mutableListOf<Activity>()
    private val destroyedCalls = mutableListOf<Activity>()
    private val tracker = HostActivityTracker(isModalActivity = { it is ModalStandIn }).also {
        it.listener = object : HostActivityTracker.Listener {
            override fun onHostResumed(activity: Activity) {
                resumedCalls += activity
            }

            override fun onHostDestroyed(activity: Activity) {
                destroyedCalls += activity
            }
        }
    }

    /** Stands in for GistModalActivity, which must never count as a host. */
    class ModalStandIn : Activity()

    // Real Robolectric activities. Mocking Activity with MockK here leaves instrumentation behind that
    // breaks mocks of Kotlin function types in later test classes of the same JVM.
    private fun host(finishing: Boolean = false, destroyed: Boolean = false): Activity {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        if (finishing) controller.get().finish()
        if (destroyed) controller.pause().stop().destroy()
        return controller.get()
    }

    @Test
    fun resumedActivity_givenNoActivityResumed_expectNull() {
        tracker.resumedActivity.shouldBeNull()
    }

    @Test
    fun onActivityResumed_givenHost_expectTrackedAndListenerNotified() {
        val activity = host()

        tracker.onActivityResumed(activity)

        tracker.resumedActivity shouldBe activity
        resumedCalls shouldBeEqualTo listOf(activity)
    }

    @Test
    fun onActivityResumed_givenModalActivity_expectIgnored() {
        val modal = Robolectric.buildActivity(ModalStandIn::class.java).setup().get()

        tracker.onActivityResumed(modal)

        tracker.resumedActivity.shouldBeNull()
        resumedCalls shouldBeEqualTo emptyList()
    }

    @Test
    fun onActivityPaused_givenTrackedActivity_expectCleared() {
        val activity = host().also { tracker.onActivityResumed(it) }

        tracker.onActivityPaused(activity)

        tracker.resumedActivity.shouldBeNull()
    }

    @Test
    fun onActivityPaused_givenDifferentActivity_expectStillTracked() {
        val current = host().also { tracker.onActivityResumed(it) }

        tracker.onActivityPaused(host())

        tracker.resumedActivity shouldBe current
    }

    @Test
    fun resumedActivity_givenFinishingActivity_expectNull() {
        tracker.onActivityResumed(host(finishing = true))

        tracker.resumedActivity.shouldBeNull()
    }

    @Test
    fun resumedActivity_givenDestroyedActivity_expectNull() {
        tracker.onActivityResumed(host(destroyed = true))

        tracker.resumedActivity.shouldBeNull()
    }

    @Test
    fun onActivityDestroyed_givenAnyActivity_expectListenerNotifiedAndTrackingCleared() {
        val activity = host().also { tracker.onActivityResumed(it) }

        tracker.onActivityDestroyed(activity)

        tracker.resumedActivity.shouldBeNull()
        destroyedCalls shouldBeEqualTo listOf(activity)
    }

    @Test
    fun onActivityResumed_givenNewHost_expectLatestWins() {
        val first = host().also { tracker.onActivityResumed(it) }
        val second = host()

        tracker.onActivityPaused(first)
        tracker.onActivityResumed(second)

        tracker.resumedActivity shouldBe second
        resumedCalls shouldBeEqualTo listOf(first, second)
    }
}
