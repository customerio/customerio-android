package io.customer.sdk.data.store

import android.content.Context
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.util.Logger
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Disk-backed queue of entries waiting on confirmation that they reached the
 * Customer.io backend through some primary delivery path (typically a
 * WorkManager job).
 *
 * Generic over [T], which must implement [PendingDeliveryEntry] so the store
 * can pull the dedup key and eviction timestamp without per-feature glue.
 * Serialization uses kotlinx.serialization — callers pass the
 * compiler-generated [KSerializer] for their entry type (e.g.
 * `PendingPushDeliveryMetric.serializer()`).
 *
 * Storage is a single JSON file in [Context.filesDir]. All read-modify-write
 * sequences are guarded by an in-process [ReentrantLock] so concurrent appends
 * cannot corrupt the file. Capacity is capped at [maxEntries]; on overflow
 * the entry with the smallest [PendingDeliveryEntry.timestamp] is dropped so
 * the queue never grows without bound when the primary delivery path is
 * failing.
 *
 * Mutating operations skip the write when nothing actually changed, so a
 * transient read failure (a corrupted file, a brief IO error) cannot silently
 * wipe legitimate entries.
 */
@InternalCustomerIOApi
class PendingDeliveryStore<T : PendingDeliveryStore.PendingDeliveryEntry>(
    context: Context,
    fileName: String,
    elementSerializer: KSerializer<T>,
    private val logger: Logger,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    /**
     * Shape contract every entry must satisfy. The store relies on [key]
     * to filter on remove and [timestamp] to evict the oldest entry on
     * overflow.
     */
    interface PendingDeliveryEntry {
        /** Stable dedup key for [remove]/[removeAll]. Must be unique per entry. */
        val key: String

        /** Epoch-millis timestamp used to evict the oldest entry on overflow. */
        val timestamp: Long
    }

    private val file: File = File(context.applicationContext.filesDir, fileName)
    private val lock = ReentrantLock()
    private val listSerializer = ListSerializer(elementSerializer)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Append a new entry, evicting the oldest if the store is at capacity. */
    fun append(entry: T) {
        lock.withLock {
            val entries = readAll().toMutableList()
            entries.add(entry)
            while (entries.size > maxEntries) {
                val oldestIndex = entries.indices.minBy { entries[it].timestamp }
                entries.removeAt(oldestIndex)
            }
            writeAll(entries)
        }
    }

    /** Returns all pending entries in insertion order. */
    fun loadAll(): List<T> = lock.withLock { readAll() }

    /**
     * Remove the entry whose [PendingDeliveryEntry.key] equals [key]. No-op
     * if no such entry exists. Skips the write when no entry was actually
     * removed so a transient read failure cannot wipe the file.
     */
    fun remove(key: String) {
        lock.withLock {
            val entries = readAll()
            val filtered = entries.filterNot { it.key == key }
            if (filtered.size == entries.size) return@withLock
            writeAll(filtered)
        }
    }

    /**
     * Remove all entries whose key is in [keys]. Prefer this over looping
     * [remove] when flushing multiple entries — it's one coordinated
     * read-modify-write, and entries appended after this call's read survive.
     */
    fun removeAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val keySet = keys.toSet()
        lock.withLock {
            val entries = readAll()
            val filtered = entries.filterNot { it.key in keySet }
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
            json.decodeFromString(listSerializer, text)
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
            file.writeText(json.encodeToString(listSerializer, entries))
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
