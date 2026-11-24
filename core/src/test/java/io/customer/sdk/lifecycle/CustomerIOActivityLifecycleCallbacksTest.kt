package io.customer.sdk.lifecycle

import android.app.Activity
import io.customer.commontest.core.JUnit5Test
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class CustomerIOActivityLifecycleCallbacksTest : JUnit5Test() {
    private val callbacks = CustomerIOActivityLifecycleCallbacks()

    private fun activity(): Activity = mockk(relaxed = true)

    @Test
    fun hasResumedActivity_givenNoEventSeen_expectUnknown() {
        callbacks.hasResumedActivity() shouldBeEqualTo null
    }

    @Test
    fun hasResumedActivity_givenActivityResumed_expectTrue() {
        callbacks.onActivityResumed(activity())

        callbacks.hasResumedActivity() shouldBeEqualTo true
    }

    @Test
    fun hasResumedActivity_givenActivityResumedAndPaused_expectFalse() {
        val activity = activity()

        callbacks.onActivityResumed(activity)
        callbacks.onActivityPaused(activity)

        callbacks.hasResumedActivity() shouldBeEqualTo false
    }

    @Test
    fun hasResumedActivity_givenTwoActivitiesResumedAndOnePaused_expectTrueUntilTheLastPauses() {
        // Split-screen and other multi-window modes resume more than one activity
        val first = activity()
        val second = activity()

        callbacks.onActivityResumed(first)
        callbacks.onActivityResumed(second)
        callbacks.onActivityPaused(first)
        callbacks.hasResumedActivity() shouldBeEqualTo true

        callbacks.onActivityPaused(second)
        callbacks.hasResumedActivity() shouldBeEqualTo false
    }

    @Test
    fun hasResumedActivity_givenPauseOfActivityResumedBeforeRegistration_expectFalseAndNeverNegative() {
        // The first event seen is a pause, because the SDK was initialized after the activity resumed
        callbacks.onActivityPaused(activity())
        callbacks.hasResumedActivity() shouldBeEqualTo false

        callbacks.onActivityResumed(activity())
        callbacks.hasResumedActivity() shouldBeEqualTo true
    }

    @Test
    fun hasResumedActivity_givenActivityReplacedByRecreation_expectTrueAgainAfterTheNewOneResumes() {
        val old = activity()
        val recreated = activity()

        callbacks.onActivityResumed(old)
        callbacks.onActivityPaused(old)
        callbacks.hasResumedActivity() shouldBeEqualTo false

        callbacks.onActivityResumed(recreated)
        callbacks.hasResumedActivity() shouldBeEqualTo true
    }
}
