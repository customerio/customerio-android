package io.customer.android.sample.java_layout.diagnostics

import io.customer.sdk.core.util.CioLogLevel

/**
 * Decides which SDK records reach the file.
 *
 * The sink captures every record the SDK emits, and on a device that is overwhelmingly not what a
 * geofence drive needs. Measured across seven real captures: one idle simulator run was 23,136
 * records and 12.9 MB, of which 22,544 records (94%) were in-app polling, against nine geofence
 * records — 0.03%. Unfiltered, a background test session fills a device with traffic nobody will
 * ever read.
 *
 * The predicate is a **deny-list**, not an allow-list: a module we have not met yet keeps being
 * recorded rather than silently disappearing. Two rules protect against the deny-list being wrong —
 * errors are never dropped, and anything that mentions location vocabulary is kept whatever else
 * matches it.
 *
 * Measured effect of this predicate over those seven captures: 26,072 records to 947, 14.2 MB to
 * 245 KB, with **zero** of the 235 location-bearing records lost.
 */
object DiagnosticFilter {
    /**
     * Runtime switch, off the Location test screen. Turning the filter off records everything,
     * which is what you want when the bug is in a module the deny-list removes.
     */
    @Volatile
    var isEnabled: Boolean = true

    /** Modules whose entire output is noise for a location drive. */
    private val DENY_TAGS = setOf("InApp", "CIO-Inbox", "SSE", "Polling")

    /**
     * Untagged records, matched on how the message opens.
     *
     * This list exists because Android's `Logger.debug(message, tag = null)` leaves the tag optional
     * and most modules omit it — 59-68% of records in the Android captures carry no tag at all, and
     * that bucket is almost entirely the in-app redux store. A tag-only deny-list fixes iOS and
     * barely dents Android.
     *
     * Second element, where present, must also appear in the message. It narrows prefixes that are
     * too generic to match on alone: `Found ` would otherwise swallow anything.
     */
    private val DENY_UNTAGGED: List<Pair<String, String?>> = listOf(
        // In-app redux store. Narrowed rather than a bare `Store:` so an unrelated future store is
        // not swallowed silently.
        "Store: action:" to null,
        "Store: state " to null,
        "Store: no state changes" to null,
        "Store: " to "InAppMessagingState",
        // CDP/Segment event envelopes. iOS logs these bare; Android prefixes them with prose, so
        // both shapes are listed — otherwise the same payload is dropped on one platform and kept
        // on the other, which is how the one outbound location body survived by accident.
        "{\"" to null,
        "Customer.io Data Pipelines running {" to null,
        "processing event on" to null,
        "applying base attributes" to null,
        "SegmentStartupQueue" to null,
        "Analytics starting" to null,
        "track a screen" to null,
        "automatic screenview ignored" to null,
        "Fetched Settings:" to null,
        // Gist / in-app fetch loop.
        "Gist:" to null,
        "Gist queue fetch" to null,
        "Current gist route" to null,
        "X-CIO-Use-SSE" to null,
        "Action received:" to null,
        "Unhandled action received:" to null,
        "No state changes" to null,
        "Fetching user messages" to null,
        "Found " to "in-app messages",
        "Found " to "inbox messages",
        "Processing " to "regular messages",
        "Saved " to "anonymous messages",
        "Retrieved " to "anonymous messages",
        "No anonymous messages" to null,
        "Cleared all anonymous" to null,
        // Event-bus wiring. The largest single block left after the first pass — 21% of everything
        // that survived. `Posting event` is deliberately absent: it is the only place the raw fix
        // the SDK acted on appears (`LocationAcquiredEvent(… latitude … longitude …)`).
        "CombinedCacheEventBusHandler: Adding observer" to null,
        "CombinedCacheEventBusHandler: Replaying event" to null,
        "CombinedCacheEventBusHandler: No observers" to null
    )

    /**
     * Evaluated **before** the deny rules. Anything speaking location vocabulary is kept no matter
     * what else would have matched it.
     *
     * Measured cost against the current deny-list: zero records, zero bytes — every one of these is
     * already kept. It is insurance, and the reason to add it now rather than after something is
     * lost: the next person to add a deny pattern does not have to reason about whether it clips a
     * geofence record.
     */
    private val LOCATION_KEEP = Regex(
        "geofen|region|latitud|longitud|coordinat|CLLocation|fence|dwell|radius|" +
            "geo_|boundary|monitor|lat=|lng=|LocationAcquired",
        RegexOption.IGNORE_CASE
    )

    /** Records dropped since process start, so a reader can tell "quiet" from "filtered". */
    @Volatile
    var droppedCount: Long = 0L
        private set

    fun shouldRecord(
        source: DiagnosticLog.Source,
        tag: String?,
        level: CioLogLevel,
        message: String
    ): Boolean {
        val keep = evaluate(source, tag, level, message)
        if (!keep) droppedCount += 1
        return keep
    }

    private fun evaluate(
        source: DiagnosticLog.Source,
        tag: String?,
        level: CioLogLevel,
        message: String
    ): Boolean {
        if (!isEnabled) return true
        // A filter must never be the reason a failure went unseen.
        if (level == CioLogLevel.ERROR) return true
        // The app's own records are the session spine — a handful per run, and the only thing
        // marking process starts and device-state changes.
        if (source == DiagnosticLog.Source.APP) return true
        if (LOCATION_KEEP.containsMatchIn(message)) return true
        if (tag != null) return tag !in DENY_TAGS
        return DENY_UNTAGGED.none { (prefix, required) ->
            message.startsWith(prefix) && (required == null || message.contains(required))
        }
    }

    /**
     * Written into the file header. A filtered file is otherwise silently lossy — nothing in it
     * distinguishes "in-app was quiet" from "in-app was removed", and a reader would draw the wrong
     * conclusion from its absence.
     */
    fun headerJson(): String = buildString {
        append("{\"enabled\":").append(isEnabled)
        append(",\"denyTags\":[")
        append(DENY_TAGS.sorted().joinToString(",") { "\"$it\"" })
        append("],\"denyUntaggedPatterns\":").append(DENY_UNTAGGED.size)
        append('}')
    }
}
