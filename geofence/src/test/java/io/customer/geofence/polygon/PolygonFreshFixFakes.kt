package io.customer.geofence.polygon

import android.location.Location

/** Never answers. The default for tests about something else, so the on-demand fix stays out. */
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
 * Records re-check calls in order. A fake rather than a mock because the last call is what
 * matters: schedule-then-cancel leaves the re-check off, and a call count would pass either way.
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
 * Records passive listener calls in order: start-then-stop leaves it off, and a call-count check
 * would pass either way.
 */
internal class RecordingPassiveMonitor : PolygonPassiveMonitor {
    val calls = mutableListOf<String>()

    /** Starts armed so receiver tests are not refused at the arming check. */
    private var armed = true

    override fun start() {
        calls += "start"
        armed = true
    }

    override fun stop() {
        calls += "stop"
        armed = false
    }

    override fun isArmed(): Boolean = armed
}

/** The default for tests about something else. */
internal object NoopPassiveMonitor : PolygonPassiveMonitor {
    override fun start() = Unit
    override fun stop() = Unit

    /** Armed by default: tests about something else must not be refused by the arming check. */
    override fun isArmed(): Boolean = true
}
