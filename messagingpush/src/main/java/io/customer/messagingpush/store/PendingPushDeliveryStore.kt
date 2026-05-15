package io.customer.messagingpush.store

import android.content.Context
import io.customer.sdk.core.util.Logger
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Disk-backed queue of push-delivered metrics waiting on a delivery confirmation.
 *
 * Each entry is the tuple `(deliveryId, token, timestamp)` where [deliveryId]
 * is the natural key used to remove the entry once the metric has been
 * delivered, and [timestamp] is the epoch-millis value captured at [append]
 * time. Entries live until [remove] / [removeAll] is called.
 *
 * Storage is a single JSON file in [Context.filesDir]. All read-modify-write
 * sequences are guarded by an in-process [ReentrantLock] so concurrent appends
 * from multiple FCM callbacks cannot corrupt the file.
 *
 * Capacity is capped at [MAX_ENTRIES]. On overflow the entry with the smallest
 * timestamp is dropped so the queue never grows without bound when delivery
 * confirmation is failing.
 */
internal class PendingPushDeliveryStore(
    context: Context,
    private val logger: Logger
) {
    private val file: File = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = ReentrantLock()

    /**
     * Append a new pending entry, capturing [System.currentTimeMillis] as the
     * entry's timestamp. The WorkManager job / direct-HTTP fallback uses
     * [deliveryId] to call [remove] on a successful response.
     */
    fun append(deliveryId: String, token: String) {
        val entry = PendingPushDeliveryMetric(
            deliveryId = deliveryId,
            token = token,
            timestamp = System.currentTimeMillis()
        )
        lock.withLock {
            val entries = readAll().toMutableList()
            entries.add(entry)
            // Drop oldest (smallest-timestamp) entries to keep the queue bounded.
            while (entries.size > MAX_ENTRIES) {
                val oldestIndex = entries.indices.minBy { entries[it].timestamp }
                entries.removeAt(oldestIndex)
            }
            writeAll(entries)
        }
    }

    /** Returns all pending entries in insertion order. */
    fun loadAll(): List<PendingPushDeliveryMetric> = lock.withLock { readAll() }

    /**
     * Remove the entry with the given [deliveryId]. No-op if no such entry
     * exists (e.g. it was already flushed at launch or removed by an earlier
     * success).
     */
    fun remove(deliveryId: String) {
        lock.withLock {
            val entries = readAll().filterNot { it.deliveryId == deliveryId }
            writeAll(entries)
        }
    }

    /**
     * Remove all entries whose deliveryIds are in [deliveryIds] in a single
     * coordinated read-modify-write. Prefer this over calling [remove] in a
     * loop when flushing multiple metrics at once. Entries appended after
     * this call's read are not affected.
     */
    fun removeAll(deliveryIds: Collection<String>) {
        if (deliveryIds.isEmpty()) return
        val idSet = deliveryIds.toSet()
        lock.withLock {
            val entries = readAll().filterNot { it.deliveryId in idSet }
            writeAll(entries)
        }
    }

    /** Remove all pending entries. */
    fun removeAll() {
        lock.withLock {
            writeAll(emptyList())
        }
    }

    private fun readAll(): List<PendingPushDeliveryMetric> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val array = JSONArray(text)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val deliveryId = obj.optString(KEY_DELIVERY_ID).takeIf { it.isNotBlank() } ?: continue
                    val token = obj.optString(KEY_TOKEN).takeIf { it.isNotBlank() } ?: continue
                    if (!obj.has(KEY_TIMESTAMP)) continue
                    val timestamp = obj.optLong(KEY_TIMESTAMP, Long.MIN_VALUE)
                    if (timestamp == Long.MIN_VALUE) continue
                    add(
                        PendingPushDeliveryMetric(
                            deliveryId = deliveryId,
                            token = token,
                            timestamp = timestamp
                        )
                    )
                }
            }
        } catch (ex: Exception) {
            logger.error(
                "Failed to read pending push delivery store; treating as empty",
                tag = TAG,
                throwable = ex
            )
            emptyList()
        }
    }

    private fun writeAll(entries: List<PendingPushDeliveryMetric>) {
        try {
            val array = JSONArray()
            entries.forEach { entry ->
                val obj = JSONObject().apply {
                    put(KEY_DELIVERY_ID, entry.deliveryId)
                    put(KEY_TOKEN, entry.token)
                    put(KEY_TIMESTAMP, entry.timestamp)
                }
                array.put(obj)
            }
            file.writeText(array.toString())
        } catch (ex: Exception) {
            logger.error(
                "Failed to write pending push delivery store",
                tag = TAG,
                throwable = ex
            )
        }
    }

    internal companion object {
        const val MAX_ENTRIES = 100
        private const val FILE_NAME = "cio_pending_push_delivery.json"
        private const val KEY_DELIVERY_ID = "deliveryId"
        private const val KEY_TOKEN = "token"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val TAG = "PendingPushDeliveryStore"
    }
}
