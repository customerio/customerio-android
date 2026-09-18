package io.customer.geofence.polygon

import android.location.Location

/**
 * A source that never answers, which is how the pipeline behaved before an on-demand fix existed.
 * The default for tests about something else, so that adding the on-demand path does not quietly
 * change what they measure.
 */
internal object NeverAnswersFreshFix : PolygonFreshFixSource {
    override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? = null
}

/** Answers the first request with [location] and every later one with nothing. */
internal class AnswersOnceFreshFix(private val location: Location) : PolygonFreshFixSource {
    var requests: Int = 0
        private set

    override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
        requests++
        return location.takeIf { requests == 1 }
    }
}

/**
 * Records what the controller asked of the periodic re-check without scheduling anything.
 *
 * A fake rather than a mock because the interesting assertion is the *last* thing asked for: a
 * reconcile that schedules and then cancels leaves the re-check off, and a call-count check would
 * pass either way.
 */
internal class RecordingRecheckScheduler : PolygonRecheckScheduler {
    val calls = mutableListOf<String>()

    override fun schedule(): Boolean {
        calls += "schedule"
        return true
    }

    override fun cancel(): Boolean {
        calls += "cancel"
        return true
    }
}

/** The default for tests about something else, so scheduling never changes what they measure. */
internal object NoopRecheckScheduler : PolygonRecheckScheduler {
    override fun schedule(): Boolean = true
    override fun cancel(): Boolean = true
}

/**
 * Records the passive listener's lifecycle without touching GMS.
 *
 * The interesting property is the ORDER: a reconcile that starts and then stops leaves the listener
 * off, and a call-count assertion would pass either way.
 */
internal class RecordingPassiveMonitor : PolygonPassiveMonitor {
    val calls = mutableListOf<String>()

    override fun start() {
        calls += "start"
    }

    override fun stop() {
        calls += "stop"
    }
}

/** The default for tests about something else. */
internal object NoopPassiveMonitor : PolygonPassiveMonitor {
    override fun start() = Unit
    override fun stop() = Unit
}
