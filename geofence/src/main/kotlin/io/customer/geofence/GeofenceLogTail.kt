package io.customer.geofence

import android.location.Location
import android.os.Build
import android.os.SystemClock
import io.customer.sdk.core.util.CioDiagnostics
import io.customer.sdk.core.util.Logger
import java.util.Locale

/**
 * Whether a record is something the SDK was told, something the SDK decided, or neither.
 *
 * Stated explicitly on every record rather than inferred from the event name. Replay feeds the
 * `in` records back and compares the `out` records that come out the other side, so getting this
 * wrong silently invalidates a whole replay run — and inferring it from a naming convention is
 * exactly the kind of rule that goes wrong quietly a year later.
 */
internal enum class GeofenceLogIo(val wire: String) {
    /** Crosses into the SDK: an OS callback, a location fix, a lifecycle or permission change, or
     *  the response to a nearby-geofence fetch. */
    INPUT("in"),

    /** Produced by the SDK: emissions and decisions, including registration results and rankings. */
    OUTPUT("out"),

    /** Neither. Device state, preflight checks, and anything a reference app contributes. */
    OBSERVATION("obs")
}

/**
 * Builds the machine-readable tail appended to a geofence log message.
 *
 * The human prose in front of the tail stays byte-identical to what it was before enrichment, so
 * nothing regresses for anyone reading Logcat. Everything a script needs rides behind a ` || `
 * delimiter as flat `key=value` pairs:
 *
 * ```
 * [Geofence] Geofence 'notl_core' ENTER: queued ... || ev=transition.emitted io=out id=notl_core t=enter
 * ```
 *
 * Deliberately *not* a structured logging API. Adding a payload type to the core `Logger` would be
 * a tracked public-API change on both platforms, with a permanent obligation to populate two
 * representations at every call site, and the only consumer is our own tooling.
 *
 * Kept identical to the iOS `GeofenceLog` so one off-device parser reads both platforms.
 */
internal object GeofenceLogTail {
    /**
     * Chosen over a single pipe after confirming no log message on either platform contains one.
     * A parser splits on the **last** occurrence and only accepts the remainder as a tail if it
     * parses cleanly as `key=value` pairs, so prose that someday contains `||` stays prose.
     */
    const val DELIMITER = " || "

    /**
     * @param ev stable machine key from the event taxonomy. Never reworded — `msg` is the prose
     *   someone will eventually rewrite, `ev` is the contract.
     * @param io replay classification.
     * @param fields ordered key/value pairs. Null values are omitted rather than written empty, so
     *   absent and empty stay distinguishable.
     */
    fun tail(ev: String, io: GeofenceLogIo, fields: List<Pair<String, String?>> = emptyList()): String {
        val parts = StringBuilder(DELIMITER).append("ev=").append(ev).append(" io=").append(io.wire)
        for ((key, value) in fields) {
            if (value == null) continue
            parts.append(' ').append(key).append('=').append(sanitize(value))
        }
        return parts.toString()
    }

    /**
     * Values may not contain whitespace — the parser splits the tail on it. Geofence identifiers
     * come from workspace configuration and can contain anything at all, so they are folded here
     * rather than trusted.
     */
    fun sanitize(value: String): String {
        if (value.isEmpty()) return "_"
        return buildString(value.length) {
            for (character in value) append(if (character.isWhitespace()) '_' else character)
        }
    }

    // MARK: - Value formatting

    fun num(value: Double?, places: Int = 1): String? {
        if (value == null || value.isNaN() || value.isInfinite()) return null
        return String.format(Locale.US, "%.${places}f", value)
    }

    fun num(value: Float?, places: Int = 1): String? = num(value?.toDouble(), places)

    fun int(value: Int?): String? = value?.toString()

    fun bool(value: Boolean): String = if (value) "true" else "false"

    /**
     * A comma-separated list, no spaces. Used for registered identifiers and ranking results.
     *
     * Capped because a ranked list of every candidate on a dense workspace would dwarf the record
     * it is attached to. The count travels separately, so a truncated list is still honest about
     * how much it left out.
     */
    fun list(values: List<String>, limit: Int = 25): String? {
        if (values.isEmpty()) return null
        val head = values.take(limit).joinToString(",") { sanitize(it) }
        return if (values.size > limit) "$head,+${values.size - limit}" else head
    }

    /**
     * Folds arbitrary text into a snake_case token.
     *
     * Reasons are tokens, never prose: `why=no_identified_user` survives someone rewriting the
     * sentence in front of it, and `why=no identified user` would break the parser on the first
     * space anyway.
     */
    fun token(value: String): String {
        val out = StringBuilder(value.length)
        var lastWasSeparator = false
        for (character in value.lowercase(Locale.US)) {
            if (character.isLetterOrDigit()) {
                out.append(character)
                lastWasSeparator = false
            } else if (!lastWasSeparator && out.isNotEmpty()) {
                out.append('_')
                lastWasSeparator = true
            }
        }
        while (out.isNotEmpty() && out.last() == '_') out.deleteCharAt(out.length - 1)
        return if (out.isEmpty()) "unknown" else out.toString()
    }

    // MARK: - Fix quality and provenance (ungated)

    /**
     * Where a fix came from. Trustworthiness differs enormously between these, and until now the
     * log said only that *a* position existed.
     *
     * Android is better placed than iOS here: `GeofencingEvent.triggeringLocation` is a real fix
     * the OS computed for this crossing ([OS_TRIGGER]), where CoreLocation supplies no position
     * with a geofence event at all and the SDK must fall back to its own cache.
     */
    enum class FixSource(val wire: String) {
        /** The OS's own triggering fix, attached to the crossing it reported. */
        OS_TRIGGER("os_trigger"),

        /** The last location the SDK was handed or cached — may be arbitrarily stale. */
        CACHED("cached"),

        /** Requested on purpose and waited for. */
        FRESH_REQUEST("fresh_request"),
        NONE("none")
    }

    /**
     * How good the fix is and where it came from. **Deliberately ungated.**
     *
     * None of these keys says anything about *where* the device is, so none of them is the thing
     * [CioDiagnostics.logPreciseLocation] exists to protect. Gating them would mean a default build
     * cannot judge whether a transition's position is worth anything at all — and `age` in
     * particular is the difference between a measurement and a guess.
     */
    fun fixQuality(location: Location?, source: FixSource): List<Pair<String, String?>> {
        val fields = mutableListOf<Pair<String, String?>>("fixsrc" to source.wire)
        if (location == null) return fields

        if (location.hasAccuracy()) fields.add("acc" to num(location.accuracy))
        fields.add("age" to num(fixAgeSeconds(location)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            fields.add("vacc" to num(location.verticalAccuracyMeters))
        }
        // Marks a fix injected by `adb emu geo fix`, a mock provider, or a route driver. Without it
        // a bench run and a real drive are indistinguishable once the files are pooled, which is
        // exactly the sort of contamination nobody notices until a conclusion rests on it.
        fields.add("sim" to bool(isMock(location)))
        return fields
    }

    /**
     * Age from the monotonic clock, not the wall clock.
     *
     * `Location.getTime()` is wall-clock and steps with NTP; `getElapsedRealtimeNanos()` cannot,
     * and counts through deep sleep. On a backgrounded phone the difference is exactly the interval
     * that matters.
     */
    private fun fixAgeSeconds(location: Location): Double =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000.0

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

    // MARK: - Gated device position

    /**
     * Whether the caller may include device coordinates, warning loudly the first time it may.
     *
     * The warning is logged at error level so it survives in a host app's own Logcat even at a
     * restrictive level: if this somehow ships enabled, the app's owner is told.
     */
    private fun allowPreciseLocation(logger: Logger): Boolean {
        if (!CioDiagnostics.logPreciseLocation) return false
        if (CioDiagnostics.claimPreciseLocationWarning()) {
            logger.error(
                "Diagnostics: precise location logging is ENABLED. Geofence debug logs now contain device coordinates. This is intended for Customer.io field testing and must not be enabled in a production build.",
                tag = "Geofence"
            )
        }
        return true
    }

    /**
     * Device position, emitted only when [CioDiagnostics.logPreciseLocation] is on.
     *
     * Speed and bearing are gated alongside the coordinate, not with the quality keys above: a run
     * of them from a known starting point is dead reckoning, so they carry positional information
     * even though neither is a coordinate.
     */
    fun position(location: Location?, logger: Logger): List<Pair<String, String?>> {
        if (location == null || !allowPreciseLocation(logger)) return emptyList()
        return buildList {
            add("lat" to num(location.latitude, 5))
            add("lon" to num(location.longitude, 5))
            if (location.hasAltitude()) add("alt" to num(location.altitude))
            if (location.hasSpeed()) add("spd" to num(location.speed))
            if (location.hasBearing()) add("brg" to num(location.bearing))
        }
    }

    /** Coordinates only, for the paths that carry bare doubles rather than a full [Location]. */
    fun position(latitude: Double?, longitude: Double?, logger: Logger): List<Pair<String, String?>> {
        if (latitude == null || longitude == null || !allowPreciseLocation(logger)) return emptyList()
        return listOf("lat" to num(latitude, 5), "lon" to num(longitude, 5))
    }
}
