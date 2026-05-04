package io.customer.datapipelines.plugins.policies

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import io.customer.datapipelines.util.AppForegroundState
import io.customer.datapipelines.util.ProcessLifecycleForegroundState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Flush policy that skips ticks while the app is backgrounded — needed on Android 15+ where the OS blocks background network. */
internal class BackgroundAwareFrequencyFlushPolicy(
    private val flushIntervalInMillis: Long,
    private val foregroundState: AppForegroundState = ProcessLifecycleForegroundState()
) : FlushPolicy {

    private var flushJob: Job? = null

    override fun schedule(analytics: Analytics) {
        if (flushJob?.isActive == true) return
        flushJob = analytics.analyticsScope.launch(analytics.fileIODispatcher) {
            while (isActive) {
                if (foregroundState.isInForeground) {
                    analytics.flush()
                }
                delay(flushIntervalInMillis)
            }
        }
    }

    override fun unschedule() {
        flushJob?.cancel()
        flushJob = null
    }

    override fun shouldFlush(): Boolean = false
}
