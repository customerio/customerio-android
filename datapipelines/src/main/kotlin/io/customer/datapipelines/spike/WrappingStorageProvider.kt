package io.customer.datapipelines.spike

import android.util.Log
import com.segment.analytics.kotlin.android.AndroidStorageProvider
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.StorageProvider
import kotlinx.coroutines.CoroutineDispatcher
import sovran.kotlin.Store
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Encryption-at-rest Phase 3 spike.
 *
 * Wraps the default analytics-kotlin storage. Every operation is traced via logcat
 * (`SegmentStorageTrace`). All write paths mutate the bytes before forwarding so
 * any plaintext that hits disk via a path we DO intercept is provably absent.
 * Read paths reverse the mutation so the library never sees the wrapping.
 *
 * Mutation:
 *  - Prepend ASCII sentinel `WRAPPED::v1::` (13 bytes).
 *  - XOR every subsequent byte with 0x55.
 *
 * Pass condition for the spike: zero `SPIKE_` needles appear in plaintext anywhere
 * in the app data directory after a full identify/track/screen sequence.
 */
class WrappingStorageProvider : StorageProvider {
    override fun createStorage(vararg params: Any): Storage {
        val delegate = AndroidStorageProvider.createStorage(*params)
        return WrappingStorage(delegate)
    }

    override fun getStorage(
        analytics: Analytics,
        store: Store,
        writeKey: String,
        ioDispatcher: CoroutineDispatcher,
        application: Any
    ): Storage {
        val delegate = AndroidStorageProvider.getStorage(
            analytics,
            store,
            writeKey,
            ioDispatcher,
            application
        )
        return WrappingStorage(delegate)
    }
}

class WrappingStorage(private val delegate: Storage) : Storage {

    override suspend fun initialize() {
        log("initialize", key = "-", bytes = ByteArray(0))
        delegate.initialize()
    }

    override suspend fun write(key: Storage.Constants, value: String) {
        val original = value.toByteArray(Charsets.UTF_8)
        log("write", key = key.rawVal, bytes = original)
        val mutated = wrap(original)
        delegate.write(key, mutated.toString(Charsets.ISO_8859_1))
    }

    override fun writePrefs(key: Storage.Constants, value: String) {
        val original = value.toByteArray(Charsets.UTF_8)
        log("writePrefs", key = key.rawVal, bytes = original)
        val mutated = wrap(original)
        delegate.writePrefs(key, mutated.toString(Charsets.ISO_8859_1))
    }

    override fun read(key: Storage.Constants): String? {
        val raw = delegate.read(key)
        return if (raw == null) {
            log("read", key = key.rawVal, bytes = ByteArray(0), extra = "null")
            null
        } else {
            // For Constants that point to file paths (Events), read() returns a comma-separated
            // file path list, NOT wrapped content. Detect by sentinel presence.
            val rawBytes = raw.toByteArray(Charsets.ISO_8859_1)
            val unwrapped = unwrap(rawBytes)
            log("read", key = key.rawVal, bytes = unwrapped, extra = "wrapped=${rawBytes.startsWithSentinel()}")
            // Return unwrapped form to the library when sentinel was present, else pass-through.
            if (rawBytes.startsWithSentinel()) unwrapped.toString(Charsets.UTF_8) else raw
        }
    }

    override fun readAsStream(source: String): InputStream? {
        val stream = delegate.readAsStream(source)
        if (stream == null) {
            log("readAsStream", key = source, bytes = ByteArray(0), extra = "null")
            return null
        }
        val all = stream.readBytes()
        stream.close()
        val unwrapped = unwrap(all)
        log("readAsStream", key = source, bytes = unwrapped, extra = "wrapped=${all.startsWithSentinel()}")
        return ByteArrayInputStream(if (all.startsWithSentinel()) unwrapped else all)
    }

    override fun remove(key: Storage.Constants): Boolean {
        log("remove", key = key.rawVal, bytes = ByteArray(0))
        return delegate.remove(key)
    }

    override fun removeFile(filePath: String): Boolean {
        log("removeFile", key = filePath, bytes = ByteArray(0))
        return delegate.removeFile(filePath)
    }

    override suspend fun rollover() {
        log("rollover", key = "-", bytes = ByteArray(0))
        delegate.rollover()
    }

    override fun close() {
        log("close", key = "-", bytes = ByteArray(0))
        delegate.close()
    }

    // ---- mutation helpers ----

    private fun wrap(original: ByteArray): ByteArray {
        val xored = ByteArray(original.size)
        for (i in original.indices) {
            xored[i] = (original[i].toInt() xor 0x55).toByte()
        }
        return SENTINEL + xored
    }

    private fun unwrap(bytes: ByteArray): ByteArray {
        if (!bytes.startsWithSentinel()) return bytes
        val body = bytes.copyOfRange(SENTINEL.size, bytes.size)
        for (i in body.indices) {
            body[i] = (body[i].toInt() xor 0x55).toByte()
        }
        return body
    }

    private fun ByteArray.startsWithSentinel(): Boolean {
        if (this.size < SENTINEL.size) return false
        for (i in SENTINEL.indices) {
            if (this[i] != SENTINEL[i]) return false
        }
        return true
    }

    private fun log(op: String, key: String, bytes: ByteArray, extra: String = "") {
        val previewLen = minOf(64, bytes.size)
        val preview = bytes.copyOfRange(0, previewLen)
            .joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        val asAscii = String(bytes, 0, previewLen, Charsets.UTF_8)
            .replace(Regex("[^\\x20-\\x7E]"), ".")
        Log.i(
            TAG,
            "op=$op key=$key bytes=${bytes.size} extra=$extra ascii=\"$asAscii\" hex=$preview"
        )
    }

    companion object {
        const val TAG = "SegmentStorageTrace"
        val SENTINEL = "WRAPPED::v1::".toByteArray(Charsets.US_ASCII)
    }
}
