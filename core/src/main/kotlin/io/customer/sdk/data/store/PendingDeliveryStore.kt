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
import kotlinx.serialization.json.jsonArray

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
 * A read that fails is reported as unreadable rather than empty, and every mutating operation
 * declines to write over it, so a brief IO error cannot silently wipe legitimate entries. Rows that
 * are readable but cannot be decoded are dropped individually and counted — one bad row costs its
 * own delivery, not the whole queue.
 */
@InternalCustomerIOApi
class PendingDeliveryStore<T : PendingDeliveryStore.PendingDeliveryEntry>(
    context: Context,
    fileName: String,
    private val elementSerializer: KSerializer<T>,
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
            // Entries we could not read are still on disk; writing without them discards them.
            val current = readAll() ?: return@withLock false
            val all = current.filterNot { it.key in incomingKeys }.toMutableList()
            all.addAll(entries)
            while (all.size > maxEntries) {
                all.removeAt(0)
            }
            writeAll(all)
        }
    }

    /** Returns all pending entries in insertion order, reading an unreadable queue as empty. */
    fun loadAll(): List<T> = lock.withLock { readAll().orEmpty() }

    /**
     * All pending entries in insertion order, or `null` when the queue could not be read.
     *
     * Same distinction [contains] draws, for a caller that drains rather than asks about one key:
     * an unreadable queue is not an empty one, and treating it as empty reports the work finished
     * while the rows are still on disk.
     */
    fun loadAllOrNull(): List<T>? = lock.withLock { readAll() }

    /** Returns the entry whose [PendingDeliveryEntry.key] equals [key], or null if none is present. */
    fun get(key: String): T? = lock.withLock { readAll()?.firstOrNull { it.key == key } }

    /**
     * Whether [key] is present, or `null` when the queue could not be read so presence is unknown.
     *
     * A caller deciding whether another channel already delivered an entry must not read unknown as
     * absent: that reports a delivery which never happened and drops the entry from consideration.
     */
    fun contains(key: String): Boolean? = lock.withLock { readAll()?.any { it.key == key } }

    /**
     * Remove the entry whose [PendingDeliveryEntry.key] equals [key]. No-op
     * if no such entry exists. Skips the write when no entry was actually
     * removed so a transient read failure cannot wipe the file.
     *
     * @return `true` when the store no longer holds [key] — it was removed, or was already absent.
     * `false` only when the entry is present and the removing write failed, so it is still queued.
     * A caller that removes to mark work *done* must check this: treating a failed removal as done
     * lets it re-read the same head entry and repeat the work without bound.
     */
    fun remove(key: String): Boolean {
        return lock.withLock {
            // Unreadable is not absent: reporting removal would mark work done while the row
            // is still on disk, waiting to be delivered again.
            val entries = readAll() ?: return@withLock false
            val filtered = entries.filterNot { it.key == key }
            if (filtered.size == entries.size) return@withLock true
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
        // A queue we cannot read yields no claim, leaving the entry for the other channel.
        val entries = readAll() ?: return@withLock false
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
            val entries = readAll() ?: return@withLock
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

    /**
     * Entries on disk, or `null` when the file is there but could not be read.
     *
     * Bytes we did read but cannot decode are the opposite case: those rows can never be delivered,
     * and refusing to write would strand the queue behind them forever, so they are dropped.
     */
    private fun readAll(): List<T>? {
        if (!file.exists()) return emptyList()
        val text = try {
            file.readText()
        } catch (ex: Exception) {
            // Anything that stops us reading means unreadable, not empty. Kept broad because this
            // never threw before, and a throw here reaches a WorkManager node with no catch.
            logger.error(
                "Could not read pending delivery store ${file.name}; leaving it untouched",
                tag = TAG,
                throwable = ex
            )
            return null
        }
        if (text.isBlank()) return emptyList()

        val rows = try {
            Json.parseToJsonElement(text).jsonArray
        } catch (ex: Exception) {
            // Readable bytes that are not a JSON array at all. Nothing here is recoverable, and
            // reporting it unreadable would refuse every future write, so give up the contents.
            logger.error(
                "Pending delivery store ${file.name} is not a readable queue; dropping its contents",
                tag = TAG,
                throwable = ex
            )
            return emptyList()
        }

        var dropped = 0
        val entries = rows.mapNotNull { row ->
            try {
                Json.decodeFromJsonElement(elementSerializer, row)
            } catch (_: Exception) {
                dropped++
                null
            }
        }
        if (dropped > 0) {
            logger.error(
                "Dropped $dropped undecodable row(s) from pending delivery store ${file.name}; kept ${entries.size}",
                tag = TAG
            )
        }
        return entries
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
