package io.customer.geofence.polygon

import android.location.Location

internal object NeverAnswersFreshFix : PolygonFreshFixSource {
    override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? = null
}

internal class AnswersOnceFreshFix(private val location: Location) : PolygonFreshFixSource {
    var requests: Int = 0
        private set

    override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
        requests++
        return location.takeIf { requests == 1 }
    }
}

/** A fake, not a mock: the last call decides the state, and a call count can't tell. */
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

internal object NoopRecheckScheduler : PolygonRecheckScheduler {
    override fun schedule(): Boolean = true
    override fun cancel(): Boolean = true
}

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

internal object NoopPassiveMonitor : PolygonPassiveMonitor {
    override fun start() = Unit
    override fun stop() = Unit

    override fun isArmed(): Boolean = true
}
