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

    private fun isForeground(state: Lifecycle.State, importance: Int? = null): Boolean = isProcessInForeground(
        processLifecycle = lifecycleIn(state),
        importanceProvider = {
            checkNotNull(importance) { "importance must not be read when the process lifecycle is running" }
        }
    )

    @Test
    fun isProcessInForeground_givenStartedOrResumed_expectTrueWithoutReadingImportance() {
        isForeground(Lifecycle.State.STARTED) shouldBeEqualTo true
        isForeground(Lifecycle.State.RESUMED) shouldBeEqualTo true
    }

    @Test
    fun isProcessInForeground_givenCreatedOrDestroyed_expectFalseWithoutReadingImportance() {
        // A process lifecycle that is running but not started, for example a push-started process.
        // The importance is not consulted, even if it would say foreground.
        isForeground(Lifecycle.State.CREATED) shouldBeEqualTo false
        isForeground(Lifecycle.State.DESTROYED) shouldBeEqualTo false
    }

    @Test
    fun isProcessInForeground_givenNeverAttachedAndForegroundImportance_expectTrue() {
        isForeground(Lifecycle.State.INITIALIZED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND) shouldBeEqualTo true
    }

    @Test
    fun isProcessInForeground_givenNeverAttachedAndVisibleImportance_expectFalse() {
        // The OS reports VISIBLE for a process that only has an overlay window, with no activity.
        isForeground(Lifecycle.State.INITIALIZED, RunningAppProcessInfo.IMPORTANCE_VISIBLE) shouldBeEqualTo false
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
}
