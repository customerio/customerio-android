package io.customer.sdk.core.util

import android.app.ActivityManager
import androidx.lifecycle.Lifecycle
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent

/**
 * Whether this process has a started UI, that is, the app is in the foreground.
 *
 * The process lifecycle ([processLifecycle]) answers this once it is running: the app is in the
 * foreground while it is at least STARTED. Reading the state is a plain field read, so it is safe from
 * any thread.
 *
 * The process lifecycle stays INITIALIZED only when it never attached to this process: a secondary
 * process, or an app that removed the androidx.startup initializer. It has no state to read then, so the
 * process importance reported by the OS decides instead. IMPORTANCE_FOREGROUND always counts. The OS gives
 * it to a process with a visible or paused activity, also when another app's dialog or share sheet is
 * over it. IMPORTANCE_VISIBLE is the caller's choice, because the OS also reports it for a process that
 * only has an overlay window, or that a foreground app is bound to or reads from, with no activity.
 *
 * Known limitation: the process lifecycle reports a stop about 700 ms after the last Activity paused.
 * A caller that only reads it still counts the app as in the foreground in that window, and the OS
 * importance does not help either, because it stays FOREGROUND until the activity stops, which takes
 * from about 300 ms to more than 800 ms depending on the device. A caller that must not act in that
 * window passes [requireResumedActivity].
 *
 * @param countVisibleAsForeground whether IMPORTANCE_VISIBLE counts as foreground when the process
 * lifecycle never attached. Pass false where a process without an activity must not pass, for example
 * before launching a modal over another app.
 * @param requireResumedActivity whether an activity of this app must be resumed. The SDK's activity
 * callbacks answer this synchronously: it turns false the moment the last activity pauses. It decides
 * whenever it has an answer, for any process lifecycle state. It has no answer until the first resume or
 * pause is seen. That covers a late SDK initialization, and also a process that never had an activity,
 * such as one started by a push or a service process bound by a foreground app. The process lifecycle and
 * importance decide there, as they did before, so a UI-less service process whose lifecycle never
 * attached and that a foreground app is bound to still counts as foreground. A resumed activity can be
 * missing for a short moment while an activity is recreated or replaced, and while the app is in
 * picture-in-picture or covered by another app's dialog. Pass true where acting without a resumed
 * activity is harmful, and a short deferral is acceptable.
 * @param importanceProvider the importance of this process, replaced in tests
 * @param resumedActivityProvider whether an activity is resumed, or null when unknown, replaced in tests
 */
@InternalCustomerIOApi
fun isProcessInForeground(
    processLifecycle: Lifecycle,
    countVisibleAsForeground: Boolean,
    requireResumedActivity: Boolean = false,
    importanceProvider: () -> Int = ::currentProcessImportance,
    resumedActivityProvider: () -> Boolean? = ::currentResumedActivityState
): Boolean {
    val state = processLifecycle.currentState
    if (state != Lifecycle.State.INITIALIZED && !state.isAtLeast(Lifecycle.State.STARTED)) {
        return false
    }
    if (requireResumedActivity) {
        resumedActivityProvider()?.let { return it }
    }
    if (state != Lifecycle.State.INITIALIZED) {
        return true
    }
    val importance = importanceProvider()
    return importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
        (countVisibleAsForeground && importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
}

private fun currentProcessImportance(): Int {
    val processInfo = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(processInfo)
    return processInfo.importance
}

private fun currentResumedActivityState(): Boolean? = SDKComponent.activityLifecycleCallbacks.hasResumedActivity()
