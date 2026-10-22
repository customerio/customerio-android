package io.customer.geofence

import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Persisted by name. Append only: a name this build does not know drops its record when read. */
internal enum class DwellDiagnosticEvent {
    DEADLINE_SCHEDULED,
    DEADLINE_REACHED,
    DEADLINE_CANCELLED,
    WAKE_TRANSITION,
    WAKE_PASSIVE_FIX,
    WAKE_APPROACH_FIX,
    WAKE_WORKER,
    FIX_REQUESTED,
    FIX_RECEIVED,
    DWELL_REJECTED,
    DWELL_EMITTED,

    /** The scheduled check at a polygon visit's threshold, or after its last unproven request. */
    WAKE_DWELL_CHECK
}

/** Why a wake or fix decided nothing. Persisted by name; one this build does not know reads as null. */
internal enum class DwellDiagnosticReason {
    WITHIN_COOLDOWN,
    UNCHANGED_POSITION,
    NONE_ARRIVED,
    NOT_ROUTABLE,
    NOT_CURRENT_SESSION,
    STILL_UNDECIDED,
    DWELL_UNPROVEN,

    /** Location evidence before the visit's threshold; journaled once per visit. */
    BELOW_THRESHOLD,

    /** Evidence for a visit with no duration on this boot's clock, so it cannot qualify. */
    UNTIMED,

    /** A reboot, re-registration or geometry revision ended an unemitted dwell candidate. */
    CONTINUITY_LOST,

    /** Evidence, or a native DWELL, that cannot be tied to the current stay, so it proves nothing for it. */
    NOT_ATTRIBUTED,

    /** The visit already spent every dwell-proof request; nothing was asked. */
    BUDGET_EXHAUSTED,

    /** The visit's last dwell-proof request was too recent; nothing was asked, and it may ask later. */
    PROOF_SPACING,

    /** The request's charge to the visit's budget could not be saved, so the request was not made. */
    CHARGE_NOT_SAVED,

    /** A DWELL the outbox kept refusing was given up as too old to deliver. */
    RESERVATION_EXPIRED
}

/**
 * Timing and fix quality only: never coordinates, user identity or free text. Times are
 * `SystemClock.elapsedRealtime`, so records from different boots do not order against each other.
 */
internal data class DwellDiagnosticRecord(
    val event: DwellDiagnosticEvent,
    val atElapsedMs: Long,
    val fenceId: String? = null,
    val dueElapsedMs: Long? = null,
    val reason: DwellDiagnosticReason? = null,
    /** A request label, such as a priority name; never requested meters. */
    val priority: String? = null,
    val provider: String? = null,
    val returnedAccuracyMeters: Double? = null,
    val fixAgeMs: Long? = null,
    val fixElapsedNanos: Long? = null
)

/**
 * Whose history a bound journal call reads or writes: the region store's user-state generation, never
 * an identifier. The store advances it whenever a session opens, so another value is another session's.
 */
internal sealed interface DwellDiagnosticOwner {
    data class Session(val generation: Long) : DwellDiagnosticOwner

    /** No session is signed in, so history is kept for nobody. */
    data object None : DwellDiagnosticOwner
}

/**
 * Opt-in dwell diagnostics in one private preferences entry, bounded in records and characters.
 * Calls run on [ioContext] under one process-wide lock, never log, and throw only to cancel. While
 * [enabled] is false nothing is read or written, except by [clear] and [prepareOwner], which only
 * erase. Fence ids with times are location history, so callers clear it on opt-out and identity reset.
 *
 * A bound call passes its [DwellDiagnosticOwner], kept under [OWNER_KEY] and written in the commit that
 * writes the records. History whose owner is absent, unreadable or another is erased or replaced before
 * it is read or appended to, so a process that dies between a session change and its clear cannot hand
 * that history to the next session. A null owner is unbound and behaves as before.
 */
internal class DwellDiagnosticJournal(
    private val prefs: Lazy<SharedPreferences>,
    private val enabled: () -> Boolean,
    private val ioContext: CoroutineContext,
    capacity: Int = DEFAULT_CAPACITY
) {
    private val capacity = capacity.coerceIn(1, MAX_CAPACITY)
    private val maxEncodedChars = this.capacity * MAX_ENCODED_RECORD_CHARS

    /**
     * True only when persisted. Unreadable history, or history [owner] does not own, is replaced in the
     * commit that records [owner], never appended to. [DwellDiagnosticOwner.None] keeps nothing, and a
     * late record from an older session never displaces a newer session's history.
     */
    suspend fun append(record: DwellDiagnosticRecord, owner: DwellDiagnosticOwner? = null): Boolean =
        locked(false) {
            if (record.atElapsedMs < 0 || owner == DwellDiagnosticOwner.None || !isEnabled()) {
                return@locked false
            }
            val generation = (owner as? DwellDiagnosticOwner.Session)?.generation
            val stored = generation?.let { storedOwner() }
            if (generation != null && stored != null && stored > generation) return@locked false
            val kept = if (generation == null || stored == generation) read() else null
            val edit = prefs.value.edit()
                .putString(KEY, encode(((kept ?: emptyList()) + record).takeLast(capacity)))
            if (generation != null) edit.putLong(OWNER_KEY, generation)
            edit.commit()
        }

    /**
     * Oldest first; empty while disabled; null when the entry is unreadable or storage fails. Bound,
     * history [owner] does not own is erased first and reads as empty.
     */
    suspend fun snapshot(owner: DwellDiagnosticOwner? = null): List<DwellDiagnosticRecord>? = locked(null) {
        when {
            !isEnabled() -> emptyList()
            owner == null || owns(owner) -> read()
            eraseIfAny() -> emptyList()
            else -> null
        }
    }

    /** Erases history [owner] does not own and writes nothing else, so, like [clear], it ignores [enabled]. */
    suspend fun prepareOwner(owner: DwellDiagnosticOwner): Boolean = locked(false) {
        owns(owner) || eraseIfAny()
    }

    /** Ignores [enabled], so an opt-out can erase history. Writes nothing when there is none. */
    suspend fun clear(): Boolean = locked(false) { eraseIfAny() }

    private fun owns(owner: DwellDiagnosticOwner): Boolean =
        owner is DwellDiagnosticOwner.Session && storedOwner() == owner.generation

    /** Null when absent or unreadable, which no owner matches. */
    private fun storedOwner(): Long? {
        val store = prefs.value
        if (!store.contains(OWNER_KEY)) return null
        return try {
            store.getLong(OWNER_KEY, 0L)
        } catch (e: ClassCastException) {
            null
        }
    }

    /** Records and owner go together, so neither outlives the other. */
    private fun eraseIfAny(): Boolean {
        val store = prefs.value
        if (!store.contains(KEY) && !store.contains(OWNER_KEY)) return true
        return store.edit().remove(KEY).remove(OWNER_KEY).commit()
    }

    private fun isEnabled(): Boolean = try {
        enabled()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun <T> locked(onFailure: T, block: () -> T): T = withContext(ioContext) {
        mutex.withLock {
            try {
                block()
            } catch (e: CancellationException) {
                // An Exception too: swallowed, it would let a cancelled caller carry on.
                throw e
            } catch (e: Exception) {
                onFailure
            }
        }
    }

    /** Null when unreadable: the wrong type, not a JSON array, or longer than any write could be. */
    private fun read(): List<DwellDiagnosticRecord>? {
        val raw = try {
            prefs.value.getString(KEY, null)
        } catch (e: ClassCastException) {
            return null
        } ?: return emptyList()
        // Checked before parsing, so a corrupt entry costs no more than a full one.
        if (raw.length > maxEncodedChars) return null
        val array = try {
            JSONArray(raw)
        } catch (e: JSONException) {
            return null
        }
        return (0 until array.length())
            .mapNotNull { index -> array.optJSONObject(index)?.let { decode(it) } }
            .takeLast(capacity)
    }

    private fun encode(records: List<DwellDiagnosticRecord>): String = JSONArray(
        records.map { record ->
            JSONObject()
                .put(FIELD_EVENT, record.event.name)
                .put(FIELD_AT, record.atElapsedMs)
                .putOpt(FIELD_FENCE_ID, bound(record.fenceId, MAX_FENCE_ID_CHARS))
                .putOpt(FIELD_DUE, record.dueElapsedMs?.takeIf { it >= 0 })
                .putOpt(FIELD_REASON, record.reason?.name)
                .putOpt(FIELD_PRIORITY, bound(record.priority, MAX_LABEL_CHARS))
                .putOpt(FIELD_PROVIDER, bound(record.provider, MAX_LABEL_CHARS))
                .putOpt(FIELD_ACCURACY, validAccuracy(record.returnedAccuracyMeters))
                .putOpt(FIELD_FIX_AGE, record.fixAgeMs?.takeIf { it >= 0 })
                .putOpt(FIELD_FIX_ELAPSED, record.fixElapsedNanos?.takeIf { it >= 0 })
        }
    ).toString()

    private fun decode(json: JSONObject): DwellDiagnosticRecord? {
        val event = DwellDiagnosticEvent.values().firstOrNull { it.name == json.opt(FIELD_EVENT) }
        val atElapsedMs = json.nonNegativeLong(FIELD_AT)
        if (event == null || atElapsedMs == null) return null
        return DwellDiagnosticRecord(
            event = event,
            atElapsedMs = atElapsedMs,
            fenceId = bound(json.opt(FIELD_FENCE_ID) as? String, MAX_FENCE_ID_CHARS),
            dueElapsedMs = json.nonNegativeLong(FIELD_DUE),
            reason = DwellDiagnosticReason.values().firstOrNull { it.name == json.opt(FIELD_REASON) },
            priority = bound(json.opt(FIELD_PRIORITY) as? String, MAX_LABEL_CHARS),
            provider = bound(json.opt(FIELD_PROVIDER) as? String, MAX_LABEL_CHARS),
            returnedAccuracyMeters = validAccuracy((json.opt(FIELD_ACCURACY) as? Number)?.toDouble()),
            fixAgeMs = json.nonNegativeLong(FIELD_FIX_AGE),
            fixElapsedNanos = json.nonNegativeLong(FIELD_FIX_ELAPSED)
        )
    }

    private fun JSONObject.nonNegativeLong(name: String): Long? =
        (opt(name) as? Number)?.toLong()?.takeIf { it >= 0 }

    /** NaN or infinity would make `org.json` throw and fail the whole append. */
    private fun validAccuracy(meters: Double?): Double? = meters?.takeIf { it.isFinite() && it >= 0.0 }

    /** Cut to [max] chars, never through a surrogate pair. Empty reads as absent. */
    private fun bound(value: String?, max: Int): String? = when {
        value.isNullOrEmpty() -> null
        value.length <= max -> value
        else -> value.substring(0, if (value[max - 1].isHighSurrogate()) max - 1 else max)
    }

    companion object {
        const val PREFS_NAME = "io.customer.geofence.dwell_diagnostics"
        const val DEFAULT_CAPACITY = 64
        private const val MAX_CAPACITY = 256

        /** Above the longest record [encode] writes with every character escaped; longer is corrupt. */
        @VisibleForTesting
        const val MAX_ENCODED_RECORD_CHARS = 2_048

        @VisibleForTesting
        const val KEY = "dwell_diagnostic_journal_v1"

        /** The [DwellDiagnosticOwner.Session] generation the records under [KEY] belong to. */
        @VisibleForTesting
        const val OWNER_KEY = "dwell_diagnostic_journal_owner_v1"
        private const val MAX_FENCE_ID_CHARS = 128
        private const val MAX_LABEL_CHARS = 32
        private const val FIELD_EVENT = "event"
        private const val FIELD_AT = "atElapsedMs"
        private const val FIELD_FENCE_ID = "fenceId"
        private const val FIELD_DUE = "dueElapsedMs"
        private const val FIELD_REASON = "reason"
        private const val FIELD_PRIORITY = "priority"
        private const val FIELD_PROVIDER = "provider"
        private const val FIELD_ACCURACY = "returnedAccuracyMeters"
        private const val FIELD_FIX_AGE = "fixAgeMs"
        private const val FIELD_FIX_ELAPSED = "fixElapsedNanos"

        /** One for every instance: they all write [KEY]. */
        private val mutex = Mutex()
    }
}
