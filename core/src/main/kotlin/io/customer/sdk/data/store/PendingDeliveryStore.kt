package io.customer.sdk.data.store

import android.content.Context
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.util.Logger
import java.io.File
import java.io.IOException
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
 * cannot corrupt the file, and each write is atomic — staged to a temp file
 * then renamed over the target — so a failed or interrupted write leaves the
 * previous file intact rather than a partial one. Capacity is capped at [maxEntries]; on overflow
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
     * to filter on remove. Eviction order is insertion order — the store
     * appends to the tail and drops from the head when at capacity, so
     * no timestamp field is required from the entry.
     */
    interface PendingDeliveryEntry {
        /** Stable dedup key for [remove]/[removeAll]. Must be unique per entry. */
        val key: String
    }

    private val file: File = File(context.applicationContext.filesDir, fileName)
    private val lock = ReentrantLock()
    private val listSerializer = ListSerializer(elementSerializer)

    /** Append a new entry, evicting the head if the store is at capacity. Returns whether it persisted. */
    fun append(entry: T): Boolean = appendAll(listOf(entry))

    /**
     * Append entries in one read-modify-write so a batch (e.g. a transition's per-geoset fan-out)
     * persists atomically — a crash can't save some and lose the rest. Evicts from the head when
     * over capacity. Named distinctly from [append] so a single-arg `append(x)` never resolves
     * ambiguously against this overload (e.g. in mocked `append(any())` verifications).
     *
     * Returns `true` when persisted (empty input is a trivial success), `false` on write failure — so
     * a caller relying on this store as its only durable copy can react (e.g. retry or surface it)
     * instead of losing the entry silently.
     */
    fun appendAll(entries: List<T>): Boolean {
        if (entries.isEmpty()) return true
        return lock.withLock {
            // A caller recovering a staged outbox attempt may append the same stable keys again.
            // Replace those rows atomically instead of duplicating one physical delivery.
            val incomingKeys = entries.mapTo(mutableSetOf(), PendingDeliveryEntry::key)
            val all = readAll().filterNot { it.key in incomingKeys }.toMutableList()
            all.addAll(entries)
            while (all.size > maxEntries) {
                all.removeAt(0)
            }
            writeAll(all)
        }
    }

    /** Returns all pending entries in insertion order. */
    fun loadAll(): List<T> = lock.withLock { readAll() }

    /** Returns the entry whose [PendingDeliveryEntry.key] equals [key], or null if none is present. */
    fun get(key: String): T? = lock.withLock { readAll().firstOrNull { it.key == key } }

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
     * Atomically remove the entry whose [PendingDeliveryEntry.key] equals
     * [key] and report whether it was present. Lets a caller "claim" an entry
     * so that exactly one of several racing delivery channels (e.g. the
     * WorkManager worker and the foreground handoff) sends the metric: the
     * channel whose [claim] returns true owns the send, the other backs off.
     * A read-only check is not enough — claim-then-send must be atomic, or a
     * slow send lets both channels act on the same still-present entry.
     * Returns true only when the removing write completed and this call owns
     * the send; false when the entry was already gone or the write failed.
     * [writeAll] is atomic, so a failed write leaves the prior file intact — a
     * false return from a write failure therefore means the entry is still
     * present for the other channel to claim.
     */
    fun claim(key: String): Boolean = lock.withLock {
        val entries = readAll()
        val filtered = entries.filterNot { it.key == key }
        if (filtered.size == entries.size) return@withLock false
        writeAll(filtered)
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
            Json.decodeFromString(listSerializer, text)
        } catch (ex: Exception) {
            logger.error(
                "Failed to read pending delivery store ${file.name}; treating as empty",
                tag = TAG,
                throwable = ex
            )
            emptyList()
        }
    }

    private fun writeAll(entries: List<T>): Boolean {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return try {
            tmp.writeText(Json.encodeToString(listSerializer, entries))
            // Atomic swap: rename the fully-written temp over the target. A failed or interrupted
            // write leaves the prior file intact rather than a truncated/partial one, so readers and
            // claimers never see corrupt JSON.
            if (!tmp.renameTo(file)) {
                throw IOException("atomic rename failed for ${file.name}")
            }
            true
        } catch (ex: Exception) {
            tmp.delete()
            logger.error(
                "Failed to write pending delivery store ${file.name}",
                tag = TAG,
                throwable = ex
            )
            false
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100
        private const val TAG = "PendingDeliveryStore"
    }
}
