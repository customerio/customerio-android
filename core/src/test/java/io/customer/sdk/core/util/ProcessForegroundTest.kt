package io.customer.sdk.core.util

import android.app.ActivityManager.RunningAppProcessInfo
import androidx.lifecycle.Lifecycle
import io.customer.commontest.core.JUnit5Test
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class ProcessForegroundTest : JUnit5Test() {

    private fun lifecycleIn(state: Lifecycle.State): Lifecycle = mockk<Lifecycle>().also {
        every { it.currentState } returns state
    }

    /**
     * The importance and the resumed activity must not be read unless a test provides them,
     * which also checks that a caller who did not ask for a signal never pays for reading it.
     */
    private fun isForeground(
        state: Lifecycle.State,
        importance: Int? = null,
        countVisibleAsForeground: Boolean = false,
        requireResumedActivity: Boolean = false,
        resumedActivityProvider: (() -> Boolean?)? = null
    ): Boolean = isProcessInForeground(
        processLifecycle = lifecycleIn(state),
        countVisibleAsForeground = countVisibleAsForeground,
        requireResumedActivity = requireResumedActivity,
        importanceProvider = {
            checkNotNull(importance) { "importance must not be read here" }
        },
        resumedActivityProvider = {
            checkNotNull(resumedActivityProvider) { "resumed activity must not be read here" }.invoke()
        }
    )

    @Test
    fun isProcessInForeground_givenStartedOrResumed_expectTrueWithoutReadingImportance() {
        isForeground(Lifecycle.State.STARTED) shouldBeEqualTo true
        isForeground(Lifecycle.State.RESUMED) shouldBeEqualTo true
    }

    @Test
    fun isProcessInForeground_givenCreatedOrDestroyed_expectFalseWithoutReadingAnySignal() {
        // A process lifecycle that is running but not started, for example a push-started process.
        // Neither the importance nor the resumed activity is consulted, even if they would say foreground.
        listOf(false, true).forEach { requireResumedActivity ->
            isForeground(Lifecycle.State.CREATED, requireResumedActivity = requireResumedActivity) shouldBeEqualTo false
            isForeground(Lifecycle.State.DESTROYED, requireResumedActivity = requireResumedActivity) shouldBeEqualTo false
        }
    }

    @Test
    fun isProcessInForeground_givenNeverAttachedAndForegroundImportance_expectTrue() {
        isForeground(Lifecycle.State.INITIALIZED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND) shouldBeEqualTo true
    }

    @Test
    fun isProcessInForeground_givenNeverAttachedAndVisibleImportanceNotCounted_expectFalse() {
        // The OS reports VISIBLE for a process that only has an overlay window, with no activity.
        isForeground(Lifecycle.State.INITIALIZED, RunningAppProcessInfo.IMPORTANCE_VISIBLE, countVisibleAsForeground = false) shouldBeEqualTo false
    }

    @Test
    fun isProcessInForeground_givenNeverAttachedAndVisibleImportanceCounted_expectTrue() {
        isForeground(Lifecycle.State.INITIALIZED, RunningAppProcessInfo.IMPORTANCE_VISIBLE, countVisibleAsForeground = true) shouldBeEqualTo true
    }

    @Test
    fun isProcessInForeground_givenRunningLifecycleAndVisibleCounted_expectLifecycleDecides() {
        isForeground(Lifecycle.State.CREATED, countVisibleAsForeground = true) shouldBeEqualTo false
        isForeground(Lifecycle.State.STARTED, countVisibleAsForeground = true) shouldBeEqualTo true
    }

    @Test
    fun isProcessInForeground_givenNeverAttachedAndImportanceBelowForeground_expectFalse() {
        listOf(
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
            RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE,
            RunningAppProcessInfo.IMPORTANCE_SERVICE,
            RunningAppProcessInfo.IMPORTANCE_CACHED
        ).forEach { importance ->
            isForeground(Lifecycle.State.INITIALIZED, importance) shouldBeEqualTo false
        }
    }

    // The window after the last activity paused: the process lifecycle still reports started for
    // about 700 ms, and the OS importance is still FOREGROUND until the activity stops.

    @Test
    fun isProcessInForeground_givenStartedAndNoResumedActivity_expectFalse() {
        isForeground(Lifecycle.State.STARTED, requireResumedActivity = true, resumedActivityProvider = { false }) shouldBeEqualTo false
        isForeground(Lifecycle.State.RESUMED, requireResumedActivity = true, resumedActivityProvider = { false }) shouldBeEqualTo false
    }

    @Test
    fun isProcessInForeground_givenStartedAndResumedActivity_expectTrueWithoutReadingImportance() {
        isForeground(Lifecycle.State.STARTED, requireResumedActivity = true, resumedActivityProvider = { true }) shouldBeEqualTo true
        isForeground(Lifecycle.State.RESUMED, requireResumedActivity = true, resumedActivityProvider = { true }) shouldBeEqualTo true
    }

    @Test
    fun isProcessInForeground_givenStartedAndResumedActivityUnknown_expectLifecycleDecides() {
        // No resume or pause has been seen yet, for example after a late SDK initialization
        isForeground(Lifecycle.State.STARTED, requireResumedActivity = true, resumedActivityProvider = { null }) shouldBeEqualTo true
    }

    @Test
    fun isProcessInForeground_givenNeverAttachedAndResumedActivity_expectTrueWithoutReadingImportance() {
        isForeground(Lifecycle.State.INITIALIZED, requireResumedActivity = true, resumedActivityProvider = { true }) shouldBeEqualTo true
    }

    @Test
    fun isProcessInForeground_givenNeverAttachedAndNoResumedActivity_expectFalseEvenWithForegroundImportance() {
        // A UI-less service that a foreground app is bound to gets IMPORTANCE_FOREGROUND from the OS
        isForeground(
            Lifecycle.State.INITIALIZED,
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            requireResumedActivity = true,
            resumedActivityProvider = { false }
        ) shouldBeEqualTo false
    }

    @Test
    fun isProcessInForeground_givenNeverAttachedAndResumedActivityUnknown_expectImportanceDecides() {
        isForeground(
            Lifecycle.State.INITIALIZED,
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            requireResumedActivity = true,
            resumedActivityProvider = { null }
        ) shouldBeEqualTo true
        isForeground(
            Lifecycle.State.INITIALIZED,
            RunningAppProcessInfo.IMPORTANCE_VISIBLE,
            countVisibleAsForeground = false,
            requireResumedActivity = true,
            resumedActivityProvider = { null }
        ) shouldBeEqualTo false
    }
}
