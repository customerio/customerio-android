package io.customer.sdk.lifecycle

import android.app.Activity
import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.util.ScopeProviderStub
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.core.util.SdkDispatchers
import io.customer.sdk.core.util.SdkScopeProvider
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Test
    fun runOnNextResume_givenStartThenResumeBurstOnRealDispatcher_expectItRunsEveryTime() {
        // The subscriber flow drops an event while a collector is busy with the previous one, so a start and a
        // resume called back to back lose the resume. The hook must not. 300 bursts, each on a fresh instance.
        SDKComponent.overrideDependency<ScopeProvider>(SdkScopeProvider(SdkDispatchers()))
        var missed = 0
        repeat(300) {
            val burstCallbacks = CustomerIOActivityLifecycleCallbacks()
            val ran = CountDownLatch(1)
            burstCallbacks.runOnNextResume { ran.countDown() }
            val activity = activity()
            burstCallbacks.onActivityStarted(activity)
            burstCallbacks.onActivityResumed(activity)
            if (!ran.await(500, TimeUnit.MILLISECONDS)) missed++
        }
        missed shouldBeEqualTo 0
    }

    @Test
    fun runOnNextResume_givenRegisteredBeforeResume_expectRunsOnceOnTheNextResumeOnly() {
        SDKComponent.overrideDependency<ScopeProvider>(ScopeProviderStub.Unconfined())
        val inlineCallbacks = CustomerIOActivityLifecycleCallbacks()
        var runs = 0
        inlineCallbacks.runOnNextResume { runs++ }
        runs shouldBeEqualTo 0

        val activity = activity()
        inlineCallbacks.onActivityResumed(activity)
        runs shouldBeEqualTo 1

        inlineCallbacks.onActivityPaused(activity)
        inlineCallbacks.onActivityResumed(activity)
        runs shouldBeEqualTo 1
    }

    @Test
    fun runOnNextResume_givenAnActivityIsAlreadyResumed_expectRunsRightAway() {
        // The resume can land between the caller's check and its registration
        SDKComponent.overrideDependency<ScopeProvider>(ScopeProviderStub.Unconfined())
        val inlineCallbacks = CustomerIOActivityLifecycleCallbacks()
        inlineCallbacks.onActivityResumed(activity())
        var runs = 0

        inlineCallbacks.runOnNextResume { runs++ }

        runs shouldBeEqualTo 1
    }

    @Test
    fun runOnNextResume_givenUnregisteredBeforeResume_expectNeverRuns() {
        SDKComponent.overrideDependency<ScopeProvider>(ScopeProviderStub.Unconfined())
        val inlineCallbacks = CustomerIOActivityLifecycleCallbacks()
        var runs = 0
        inlineCallbacks.runOnNextResume { runs++ }

        inlineCallbacks.unregister(mockk(relaxed = true))
        inlineCallbacks.onActivityResumed(activity())

        runs shouldBeEqualTo 0
    }
}
