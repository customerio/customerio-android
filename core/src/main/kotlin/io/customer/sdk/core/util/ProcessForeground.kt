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
 * process importance reported by the OS decides instead.
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
    // Importances in between, such as a foreground service, don't mean an Activity is started
    return importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
}

private fun currentProcessImportance(): Int {
    val processInfo = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(processInfo)
    return processInfo.importance
}
