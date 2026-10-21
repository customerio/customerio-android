package io.customer.geofence.polygon

import android.content.SharedPreferences
import org.json.JSONObject

/** One polygon visit whose enclosing circle may carry a native DWELL wake. Ids and counters only. */
internal data class PolygonDwellWakeRecord(
    val polygonId: String,
    val visitId: String,
    val regionRevision: Int,
    val userStateGeneration: Long,
    val bootSessionId: String?,
    val entryElapsedMs: Long
)

internal sealed interface PolygonDwellWakeRecords {
    data class Known(val byPolygonId: Map<String, PolygonDwellWakeRecord>) : PolygonDwellWakeRecords

    /** Unreadable, so any registered polygon may be armed and none may be skipped as unchanged. */
    data object Unknown : PolygonDwellWakeRecords
}

/**
 * Write-ahead record of dwell wakes, so a crash, timeout or failed disarm cannot leave a DWELL
 * registration nothing restores. Written before the GMS add; removed only after a disarm, a full
 * re-registration or a clearAll succeeded. A failed [put] attempts to write back what it read, so a refused arm does not normally
 * leave a record behind. If that rollback also fails, ownership remains conservative. A commit that
 * fails after updating the in-memory map can only overstate what is armed, which costs one extra disarm.
 *
 * Synchronous disk I/O: call off the main thread. It never logs, so any lock may be held.
 */
internal class PolygonDwellWakeRecordStore(private val prefs: SharedPreferences) {
    private val lock = Any()

    fun read(): PolygonDwellWakeRecords = synchronized(lock) { readLocked() }

    /**
     * False, and the caller must not arm, when unwritable, unreadable, full or out of bounds. A failed
     * commit can leave the record in the in-memory map, where it would show an arm that never ran and
     * refuse the visit's retry, so the records read are written back, any older visit's included.
     */
    fun put(record: PolygonDwellWakeRecord): Boolean = synchronized(lock) {
        val known = (readLocked() as? PolygonDwellWakeRecords.Known)?.byPolygonId
            ?: return@synchronized false
        val records = known + (record.polygonId to record)
        if (!record.isWithinBounds() || records.size > MAX_RECORDS) return@synchronized false
        if (write(records)) return@synchronized true
        write(known)
        false
    }

    /** Only while the record is still [visitId]'s, so an older disarm cannot drop a newer arm. */
    fun remove(polygonId: String, visitId: String): Boolean = synchronized(lock) {
        val records = (readLocked() as? PolygonDwellWakeRecords.Known)?.byPolygonId
        records != null && records[polygonId]?.visitId == visitId && write(records - polygonId)
    }

    /** After clearAll, a full re-registration or an OS-reported loss succeeded. No GMS call. */
    fun clear(): Boolean = synchronized(lock) {
        runCatching { prefs.edit().remove(KEY).commit() }.getOrDefault(false)
    }

    private fun readLocked(): PolygonDwellWakeRecords = runCatching<PolygonDwellWakeRecords?> {
        // Another type under the key throws here, corrupt JSON in decode: both read as unknown.
        val raw = prefs.getString(KEY, null) ?: return@runCatching PolygonDwellWakeRecords.Known(emptyMap())
        if (raw.length > MAX_RAW_CHARS) null else decode(JSONObject(raw))
    }.getOrNull() ?: PolygonDwellWakeRecords.Unknown

    /** Null unless every entry is well formed: an entry dropped silently could be an armed one. */
    private fun decode(json: JSONObject): PolygonDwellWakeRecords? {
        if (json.length() > MAX_RECORDS) return null
        val records = json.keys().asSequence().associateWith { id ->
            val entry = json.getJSONObject(id)
            PolygonDwellWakeRecord(
                polygonId = id,
                visitId = entry.getString(VISIT),
                regionRevision = entry.getInt(REVISION),
                userStateGeneration = entry.getLong(GENERATION),
                bootSessionId = if (entry.isNull(BOOT)) null else entry.getString(BOOT),
                entryElapsedMs = entry.getLong(ENTRY)
            ).takeIf { it.isWithinBounds() } ?: return null
        }
        return PolygonDwellWakeRecords.Known(records)
    }

    /**
     * Refuses a value over [MAX_RAW_CHARS] before editing: [readLocked] rejects one, so the write would
     * read back as [PolygonDwellWakeRecords.Unknown]. Escaping can make a field six times longer.
     */
    private fun write(records: Map<String, PolygonDwellWakeRecord>): Boolean = runCatching {
        if (records.isEmpty()) return@runCatching prefs.edit().remove(KEY).commit()
        val json = JSONObject()
        records.values.forEach { record ->
            val entry = JSONObject()
                .put(VISIT, record.visitId)
                .put(REVISION, record.regionRevision)
                .put(GENERATION, record.userStateGeneration)
                .put(ENTRY, record.entryElapsedMs)
                .putOpt(BOOT, record.bootSessionId)
            json.put(record.polygonId, entry)
        }
        val raw = json.toString()
        raw.length <= MAX_RAW_CHARS && prefs.edit().putString(KEY, raw).commit()
    }.getOrDefault(false)

    private fun PolygonDwellWakeRecord.isWithinBounds(): Boolean =
        polygonId.length in 1..MAX_ID_LENGTH &&
            visitId.length in 1..MAX_ID_LENGTH &&
            (bootSessionId?.length ?: 0) <= MAX_ID_LENGTH &&
            entryElapsedMs >= 0L

    companion object {
        const val PREFS_NAME = "io.customer.geofence.dwell_wake_records"
        const val KEY = "armed_v1"

        /** One per business slot: GMS allows 100 geofences, one of them the movement trigger. */
        const val MAX_RECORDS = 99

        /** GMS's request id limit; visit and boot ids are far shorter. */
        private const val MAX_ID_LENGTH = 100
        private const val MAX_RAW_CHARS = 64_000
        private const val VISIT = "visit"
        private const val REVISION = "rev"
        private const val GENERATION = "gen"
        private const val BOOT = "boot"
        private const val ENTRY = "entry"
    }
}
