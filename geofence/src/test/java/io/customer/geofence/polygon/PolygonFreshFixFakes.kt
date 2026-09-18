package io.customer.geofence.polygon

import android.location.Location

/**
 * A source that never answers, which is how the pipeline behaved before an on-demand fix existed.
 * The default for tests about something else, so that adding the on-demand path does not quietly
 * change what they measure.
 */
internal object NeverAnswersFreshFix : PolygonFreshFixSource {
    override suspend fun awaitFreshFix(timeoutMs: Long): Location? = null
}

/** Answers the first request with [location] and every later one with nothing. */
internal class AnswersOnceFreshFix(private val location: Location) : PolygonFreshFixSource {
    var requests: Int = 0
        private set

    override suspend fun awaitFreshFix(timeoutMs: Long): Location? {
        requests++
        return location.takeIf { requests == 1 }
    }
}
