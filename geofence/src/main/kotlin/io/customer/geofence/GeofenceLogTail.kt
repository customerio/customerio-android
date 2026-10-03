package io.customer.geofence

import android.location.Location
import android.os.Build
import android.os.SystemClock
import java.util.Locale

/**
 * Replay feeds `in` records back and compares `out` ones. An environment fault the SDK reacts to is
 * [INPUT], since replay must be fed it to reproduce the decision.
 */
internal enum class GeofenceLogIo(val wire: String) {
    INPUT("in"),
    OUTPUT("out"),
    OBSERVATION("obs")
}

/**
 * Builds the ` || key=value` log tail. Keys match iOS's `GeofenceLog`; a few records (registration
 * in particular) split differently per platform, so the parser accepts both shapes.
 */
internal object GeofenceLogTail {
    /** A parser splits on the **last** occurrence, and only if the remainder is all `key=value`. */
    const val DELIMITER = " || "

    /**
     * @param ev stable machine key; never reworded.
     * @param fields null values are omitted, keeping absent and empty distinct.
     */
    fun tail(
        ev: String,
        io: GeofenceLogIo,
        fields: List<Pair<String, String?>> = emptyList()
    ): String {
        if (!GeofenceDiagnostics.isEnabled) return ""

        val parts = StringBuilder(DELIMITER).append("ev=").append(ev).append(" io=").append(io.wire)
        for ((key, value) in fields) {
            if (value == null) continue
            // Sanitize by default so a new field is safe; only composed keys opt out.
            val safe = if (key in COMPOSED_KEYS) foldWhitespace(value) else sanitize(value)
            parts.append(' ').append(key).append('=').append(safe)
        }
        return parts.toString()
    }

    /** Format separators: `=` a pair, `,` a list, `:` an `id:distance` entry, `|` the tail delimiter. */
    private val SEPARATORS = charArrayOf('=', ',', ':', '|')

    /** Keys whose values contain separators by construction; every other value is an untrusted token. */
    private val COMPOSED_KEYS = setOf("ranked", "evicted", "ids", "gs", "tt", "ring")

    /** For [COMPOSED_KEYS] values: folds only whitespace, which separates one `key=value` from the next. */
    fun foldWhitespace(value: String): String {
        if (value.isEmpty()) return "_"
        return buildString(value.length) {
            for (character in value) append(if (character.isWhitespace()) '_' else character)
        }
    }

    /** Folds whitespace and [SEPARATORS]; workspace-authored ids can contain anything. */
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
     * For elements the caller has already composed, like `id:distance`. Not sanitized here, so the
     * untrusted part must be sanitized before composing.
     */
    fun composedList(values: List<String>, limit: Int = 25): String? {
        if (values.isEmpty()) return null
        val head = values.take(maxOf(0, limit)).joinToString(",")
        return if (values.size > limit) "$head,+${values.size - limit}" else head
    }

    fun list(values: List<String>, limit: Int = 25): String? {
        if (values.isEmpty()) return null
        // `take` throws on a negative count, and a log must never crash the process.
        val head = values.take(maxOf(0, limit)).joinToString(",") { sanitize(it) }
        return if (values.size > limit) "$head,+${values.size - limit}" else head
    }

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

    /** Unlike iOS, Android's `triggeringLocation` is a real fix the OS computed for the crossing. */
    enum class FixSource(val wire: String) {
        OS_TRIGGER("os_trigger"),

        /** Last handed to or cached by the SDK; may be arbitrarily stale. */
        CACHED("cached"),
        FRESH_REQUEST("fresh_request"),
        NONE("none")
    }

    fun fixQuality(location: Location?, source: FixSource): List<Pair<String, String?>> {
        val fields = mutableListOf<Pair<String, String?>>("fixsrc" to source.wire)
        if (location == null) return fields

        if (location.hasAccuracy()) fields.add("acc" to num(location.accuracy))
        // Full precision, matching iOS's tail.
        fields.add("age" to num(fixAgeSeconds(location), 6))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            fields.add("vacc" to num(location.verticalAccuracyMeters))
        }
        fields.add("sim" to bool(isMock(location)))
        return fields
    }

    private const val NANOS_PER_SECOND = 1_000_000_000.0

    /** Monotonic, not wall clock: `getTime()` steps with NTP, `elapsedRealtimeNanos` cannot. */
    fun fixAgeSeconds(elapsedRealtimeNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / NANOS_PER_SECOND

    private fun fixAgeSeconds(location: Location): Double =
        fixAgeSeconds(location.elapsedRealtimeNanos)

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

    // MARK: - Device position

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

    fun position(latitude: Double?, longitude: Double?): List<Pair<String, String?>> {
        if (latitude == null || longitude == null) return emptyList()
        return listOf("lat" to num(latitude, 5), "lon" to num(longitude, 5))
    }
}
