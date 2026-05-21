package io.customer.sdk.data.store

import android.content.Context
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.util.Logger
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Disk-backed queue of entries waiting on confirmation that they reached the
 * Customer.io backend through some primary delivery path (typically a
 * WorkManager job). Generic over the entry type [T]; the per-feature
 * [Serializer] describes how to convert entries to/from JSON, how to derive
 * the dedup key, and how to read the eviction timestamp.
 *
 * Storage is a single JSON file in [Context.filesDir]. All read-modify-write
 * sequences are guarded by an in-process [ReentrantLock] so concurrent appends
 * cannot corrupt the file. Capacity is capped at [maxEntries]; on overflow
 * the entry with the smallest [Serializer.timestamp] is dropped so the queue
 * never grows without bound when the primary delivery path is failing.
 *
 * Mutating operations skip the write when nothing actually changed, so a
 * transient read failure (a corrupted file, a brief IO error) cannot silently
 * wipe legitimate entries.
 */
@InternalCustomerIOApi
class PendingDeliveryStore<T>(
    context: Context,
    fileName: String,
    private val serializer: Serializer<T>,
    private val logger: Logger,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    /**
     * Per-feature description of how to persist a [T] entry, what key to use
     * for [remove]/[removeAll], and what timestamp drives oldest-first
     * eviction.
     */
    interface Serializer<T> {
        /** Stable dedup key for [remove]/[removeAll]. Must be unique per entry. */
        fun key(entry: T): String

        /** Epoch-millis timestamp used to evict the oldest entry on overflow. */
        fun timestamp(entry: T): Long

        /** Convert the entry to a JSON object for persistence. */
        fun toJson(entry: T): JSONObject

        /** Read an entry back from JSON; return null to skip malformed rows. */
        fun fromJson(obj: JSONObject): T?
    }

    private val file: File = File(context.applicationContext.filesDir, fileName)
    private val lock = ReentrantLock()

    /** Append a new entry, evicting the oldest if the store is at capacity. */
    fun append(entry: T) {
        lock.withLock {
            val entries = readAll().toMutableList()
            entries.add(entry)
            while (entries.size > maxEntries) {
                val oldestIndex = entries.indices.minBy { serializer.timestamp(entries[it]) }
                entries.removeAt(oldestIndex)
            }
            writeAll(entries)
        }
    }

    /** Returns all pending entries in insertion order. */
    fun loadAll(): List<T> = lock.withLock { readAll() }

    /**
     * Remove the entry whose [Serializer.key] equals [key]. No-op if no such
     * entry exists. Skips the write when no entry was actually removed so a
     * transient read failure cannot wipe the file.
     */
    fun remove(key: String) {
        lock.withLock {
            val entries = readAll()
            val filtered = entries.filterNot { serializer.key(it) == key }
            if (filtered.size == entries.size) return@withLock
            writeAll(filtered)
        }
    }

    /**
     * Remove all entries whose [Serializer.key] is in [keys]. Prefer this over
     * looping [remove] when flushing multiple entries — it's one coordinated
     * read-modify-write, and entries appended after this call's read survive.
     */
    fun removeAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val keySet = keys.toSet()
        lock.withLock {
            val entries = readAll()
            val filtered = entries.filterNot { serializer.key(it) in keySet }
            if (filtered.size == entries.size) return@withLock
            writeAll(filtered)
        }
    }

    /** Remove all pending entries. */
    fun removeAll() {
        lock.withLock {
            writeAll(emptyList())
        }
    }

    private fun readAll(): List<T> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val array = JSONArray(text)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val entry = serializer.fromJson(obj) ?: continue
                    add(entry)
                }
            }
        } catch (ex: Exception) {
            logger.error(
                "Failed to read pending delivery store ${file.name}; treating as empty",
                tag = TAG,
                throwable = ex
            )
            emptyList()
        }
    }

    private fun writeAll(entries: List<T>) {
        try {
            val array = JSONArray()
            entries.forEach { entry -> array.put(serializer.toJson(entry)) }
            file.writeText(array.toString())
        } catch (ex: Exception) {
            logger.error(
                "Failed to write pending delivery store ${file.name}",
                tag = TAG,
                throwable = ex
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100
        private const val TAG = "PendingDeliveryStore"
    }
}
