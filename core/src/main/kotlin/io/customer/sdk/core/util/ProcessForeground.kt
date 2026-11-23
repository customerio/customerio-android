package io.customer.sdk.core.util

import android.app.ActivityManager
import androidx.lifecycle.Lifecycle
import io.customer.base.internal.InternalCustomerIOApi

/**
 * Whether this process has a started UI, that is, the app is in the foreground.
 *
 * The process lifecycle ([processLifecycle]) answers this once it is running: the app is in the
 * foreground while it is at least STARTED. Reading the state is a plain field read, so it is safe from
 * any thread.
 *
 * The process lifecycle stays INITIALIZED only when it never attached to this process: a secondary
 * process, or an app that removed the androidx.startup initializer. It has no state to read then, so the
 * process importance reported by the OS decides instead. Only IMPORTANCE_FOREGROUND counts. The OS gives
 * it to a process with a visible or paused activity, also when another app's dialog or share sheet is
 * over it. IMPORTANCE_VISIBLE does not count: the OS also reports it for a process that only has an
 * overlay window, or that a foreground app is bound to or reads from, and none of these has an activity.
 *
 * Known limitation: the process lifecycle reports a stop about 700 ms after the last Activity stopped.
 * A caller that checks within that window still counts the app as in the foreground.
 *
 * @param importanceProvider the importance of this process, replaced in tests
 */
@InternalCustomerIOApi
fun isProcessInForeground(
    processLifecycle: Lifecycle,
    importanceProvider: () -> Int = ::currentProcessImportance
): Boolean {
    val state = processLifecycle.currentState
    if (state != Lifecycle.State.INITIALIZED) {
        return state.isAtLeast(Lifecycle.State.STARTED)
    }
    val importance = importanceProvider()
    return importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}

private fun currentProcessImportance(): Int {
    val processInfo = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(processInfo)
    return processInfo.importance
}
