package io.customer.geofence

import android.location.Location
import android.os.Build
import android.os.SystemClock
import java.util.Locale

/**
 * Whether a record is something the SDK was told, decided, or neither.
 *
 * Stated explicitly rather than inferred from the event name: replay feeds the `in` records back
 * and compares the `out` records, so a naming convention getting this wrong invalidates a run.
 */
internal enum class GeofenceLogIo(val wire: String) {
    INPUT("in"),
    OUTPUT("out"),
    OBSERVATION("obs")
}

/**
 * Builds the machine-readable tail appended to a geofence log message.
 *
 * ```
 * [Geofence] Geofence 'notl_core' ENTER: queued ... || ev=transition.emitted io=out id=notl_core t=enter
 * ```
 *
 * Mirrors the iOS `GeofenceLog`. The key vocabulary matches; a few records are split
 * differently per platform (registration in particular), so the parser accepts both shapes.
 */
internal object GeofenceLogTail {
    /** A parser splits on the **last** occurrence, and only if the remainder is all `key=value`. */
    const val DELIMITER = " || "

    /**
     * @param ev stable machine key. Never reworded — `msg` is the prose someone will rewrite.
     * @param fields null values are omitted, keeping absent and empty distinct.
     */
    fun tail(
        ev: String,
        io: GeofenceLogIo,
        fields: List<Pair<String, String?>> = emptyList()
    ): String {
        // The single gate for every diagnostic value. Prose is emitted by the caller either way.
        if (!GeofenceDiagnostics.isEnabled) return ""

        val parts = StringBuilder(DELIMITER).append("ev=").append(ev).append(" io=").append(io.wire)
        for ((key, value) in fields) {
            if (value == null) continue
            // Sanitize by default and let the few composed keys opt out, rather than the reverse.
            // A new field is then safe without anyone remembering to wrap it, which is how 20
            // `id` call sites ended up unprotected the last time this was the other way round.
            val safe = if (key in COMPOSED_KEYS) foldWhitespace(value) else sanitize(value)
            parts.append(' ').append(key).append('=').append(safe)
        }
        return parts.toString()
    }

    /**
     * Every character the format itself uses to separate things. Applied to *untrusted tokens*
     * only — a workspace-authored id containing one of these would otherwise split a field: `=` a
     * pair, `,` a list, `:` an `id:distance` entry in `ranked`, `|` the tail delimiter.
     *
     * Deliberately not applied to a finished value: `list` and `ranked` compose their separators
     * on purpose, and folding those turns `a,b` into `a_b`.
     */
    private val SEPARATORS = charArrayOf('=', ',', ':', '|')

    /**
     * The only values that compose the format's separators on purpose. Everything else is treated
     * as an untrusted token: geofence identifiers are workspace-authored and can hold anything.
     */
    private val COMPOSED_KEYS = setOf("ranked", "evicted", "ids")

    /**
     * Applied to every finished value. Only whitespace, which is what separates one `key=value`
     * from the next — the value's own structure is already the caller's business.
     */
    fun foldWhitespace(value: String): String {
        if (value.isEmpty()) return "_"
        return buildString(value.length) {
            for (character in value) append(if (character.isWhitespace()) '_' else character)
        }
    }

    /** The parser splits on whitespace, and workspace-authored ids can contain anything. */
    fun sanitize(value: String): String {
        if (value.isEmpty()) return "_"
        return buildString(value.length) {
            for (character in value) {
                append(if (character.isWhitespace() || character in SEPARATORS) '_' else character)
            }
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
     * For elements the caller has already composed, like `id:distance` — their structure is
     * deliberate, so the untrusted part must be sanitized before composing, not after.
     */
    fun composedList(values: List<String>, limit: Int = 25): String? {
        if (values.isEmpty()) return null
        val head = values.take(maxOf(0, limit)).joinToString(",")
        return if (values.size > limit) "$head,+${values.size - limit}" else head
    }

    /** Comma-separated, capped; the count travels separately so truncation stays honest. */
    fun list(values: List<String>, limit: Int = 25): String? {
        if (values.isEmpty()) return null
        // `take` throws on a negative count; no caller passes one, but a log must not be
        // the thing that takes the process down.
        val head = values.take(maxOf(0, limit)).joinToString(",") { sanitize(it) }
        return if (values.size > limit) "$head,+${values.size - limit}" else head
    }

    /** Reasons are tokens so they survive the sentence in front of them being reworded. */
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

    // MARK: - Fix quality and provenance

    /**
     * Where a fix came from; the log previously said only that *a* position existed.
     *
     * Android is better placed than iOS: `triggeringLocation` is a real fix the OS computed for
     * this crossing, where CoreLocation supplies none and the SDK falls back to its own cache.
     */
    enum class FixSource(val wire: String) {
        /** The OS's own triggering fix, attached to the crossing it reported. */
        OS_TRIGGER("os_trigger"),

        /** Last handed to or cached by the SDK — may be arbitrarily stale. */
        CACHED("cached"),
        FRESH_REQUEST("fresh_request"),
        NONE("none")
    }

    /** How good the fix is and where it came from; `age` separates a measurement from a guess. */
    fun fixQuality(location: Location?, source: FixSource): List<Pair<String, String?>> {
        val fields = mutableListOf<Pair<String, String?>>("fixsrc" to source.wire)
        if (location == null) return fields

        if (location.hasAccuracy()) fields.add("acc" to num(location.accuracy))
        fields.add("age" to num(fixAgeSeconds(location)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            fields.add("vacc" to num(location.verticalAccuracyMeters))
        }
        // Without this a bench run and a real drive are indistinguishable once files are pooled.
        fields.add("sim" to bool(isMock(location)))
        return fields
    }

    /** Monotonic, not wall clock: `getTime()` steps with NTP, `elapsedRealtimeNanos` cannot. */
    private fun fixAgeSeconds(location: Location): Double =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000.0

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

    // MARK: - Device position

    /** Device position. Gated with the rest of the tail; no per-field switch. */
    fun position(location: Location?): List<Pair<String, String?>> {
        if (location == null) return emptyList()
        return buildList {
            add("lat" to num(location.latitude, 5))
            add("lon" to num(location.longitude, 5))
            if (location.hasAltitude()) add("alt" to num(location.altitude))
            if (location.hasSpeed()) add("spd" to num(location.speed))
            if (location.hasBearing()) add("brg" to num(location.bearing))
        }
    }

    /** Coordinates only, for the paths that carry bare doubles rather than a full [Location]. */
    fun position(latitude: Double?, longitude: Double?): List<Pair<String, String?>> {
        if (latitude == null || longitude == null) return emptyList()
        return listOf("lat" to num(latitude, 5), "lon" to num(longitude, 5))
    }
}
