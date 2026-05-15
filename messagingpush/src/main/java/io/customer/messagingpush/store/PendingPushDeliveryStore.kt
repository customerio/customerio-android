package io.customer.messagingpush.store

import android.content.Context
import io.customer.sdk.core.util.Logger
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Disk-backed queue of push-delivered metrics waiting on a delivery confirmation.
 *
 * Each entry is the tuple `(id, deliveryId, token)` where `id` is generated at
 * [append] time and is the handle used to remove the entry once the metric has
 * been delivered. There is no timestamp and no age-based eviction; entries live
 * until [remove] / [removeAll] is called.
 *
 * Storage is a single JSON file in [Context.filesDir]. All read-modify-write
 * sequences are guarded by an in-process [ReentrantLock] so concurrent appends
 * from multiple FCM callbacks cannot corrupt the file.
 *
 * Capacity is capped at [MAX_ENTRIES]. On overflow the oldest entry is dropped
 * so the queue never grows without bound when delivery confirmation is failing.
 */
internal class PendingPushDeliveryStore(
    context: Context,
    private val logger: Logger
) {
    private val file: File = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = ReentrantLock()

    /**
     * Append a new pending entry and return its generated id. The id should be
     * passed to the WorkManager job / direct-HTTP fallback so it can call
     * [remove] on a successful response.
     */
    fun append(deliveryId: String, token: String): String {
        val id = UUID.randomUUID().toString()
        lock.withLock {
            val entries = readAll().toMutableList()
            entries.add(PendingPushDeliveryMetric(id = id, deliveryId = deliveryId, token = token))
            // Drop oldest entries to keep the queue bounded.
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
            writeAll(entries)
        }
        return id
    }

    /** Returns all pending entries in insertion order. */
    fun loadAll(): List<PendingPushDeliveryMetric> = lock.withLock { readAll() }

    /**
     * Remove the entry with the given [id]. No-op if no such entry exists
     * (e.g. it was already flushed at launch or removed by an earlier success).
     */
    fun remove(id: String) {
        lock.withLock {
            val entries = readAll().filterNot { it.id == id }
            writeAll(entries)
        }
    }

    /** Remove all pending entries (used after the launch flush hands them off). */
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
                    val id = obj.optString(KEY_ID).takeIf { it.isNotBlank() } ?: continue
                    val deliveryId = obj.optString(KEY_DELIVERY_ID).takeIf { it.isNotBlank() } ?: continue
                    val token = obj.optString(KEY_TOKEN).takeIf { it.isNotBlank() } ?: continue
                    add(PendingPushDeliveryMetric(id = id, deliveryId = deliveryId, token = token))
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
                    put(KEY_ID, entry.id)
                    put(KEY_DELIVERY_ID, entry.deliveryId)
                    put(KEY_TOKEN, entry.token)
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
        private const val KEY_ID = "id"
        private const val KEY_DELIVERY_ID = "deliveryId"
        private const val KEY_TOKEN = "token"
        private const val TAG = "PendingPushDeliveryStore"
    }
}
