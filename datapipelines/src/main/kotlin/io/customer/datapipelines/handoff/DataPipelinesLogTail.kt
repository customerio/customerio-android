package io.customer.datapipelines.handoff

import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.platform.EnrichmentClosure
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioLogLevel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The data pipelines replay log: one debug line per input crossing into the SDK (`io=in`) and one
 * per call the SDK makes into Segment (`io=out`), in geofence's machine-tail format
 * ` || ev=… io=… key=value…`, so a device capture of both modules reads as one scenario. Keys and
 * values are percent-encoded ([percentEncode]); an empty value prints as `key=` and a field the call
 * doesn't carry is left out, so a reader splits each pair at its first `=` and decodes both sides.
 *
 * Each input gets a call id that the hand-offs it causes carry, and every line carries `sdk`, the SDK
 * instance that logged it, for reading a capture by hand. A hand-off line holds only what the call
 * carries ([handOff]); nothing is read back from Segment, so a value the call leaves to Segment (the
 * user of a track, the anonymous id) is not in the line.
 *
 * Logging only: nothing here changes what the SDK does. Off unless [DataPipelinesDiagnostics] is on
 * and the SDK logs at debug level.
 */
internal object DataPipelinesLogTail {
    const val DELIMITER = " || "

    private val calls = AtomicLong()

    private val instances = AtomicInteger()

    /** Debug level and the manifest flag: two cheap reads, and no other work while the log is off. */
    val isEnabled: Boolean
        get() = SDKComponent.logger.logLevel == CioLogLevel.DEBUG && DataPipelinesDiagnostics.isEnabled

    /** The SDK instance created last: the one a public API call reaches. */
    @Volatile
    var currentInstance: Int? = null
        private set

    /** Numbers a new SDK instance. */
    fun newInstance(): Int = instances.incrementAndGet().also { currentInstance = it }

    // The input running on this thread; a public call made from inside another is part of it
    private val current = ThreadLocal<Long?>()

    /** Logs [ev] as an input of SDK [instance] (null before there is one), then runs [block] as that input. */
    inline fun <R> input(
        ev: String,
        fields: () -> List<Pair<String, String?>> = { emptyList() },
        instance: Int? = currentInstance,
        block: () -> R
    ): R {
        if (!isEnabled || currentCall() != null) return block()
        begin(ev, fields(), instance)
        try {
            return block()
        } finally {
            end()
        }
    }

    fun currentCall(): Long? = current.get()

    fun begin(ev: String, fields: List<Pair<String, String?>>, instance: Int?) {
        val call = calls.incrementAndGet()
        current.set(call)
        emit("SDK input", ev, "in", listOf("call" to call.toString(), "sdk" to instance?.toString()) + fields)
    }

    fun end() = current.remove()

    /**
     * One call into Segment, with what it carries: the event name, an identify's [userId], the
     * values [enrichment] pins, and the top-level primitive [properties] (or traits) as
     * `p.<key>=<value>`. Nested objects and arrays are skipped. [properties] is only built while
     * the log is on.
     */
    fun handOff(
        instance: Int?,
        ev: String,
        event: String? = null,
        userId: String? = null,
        properties: () -> JsonElement? = { null },
        enrichment: EnrichmentClosure? = null
    ) {
        if (!isEnabled) return
        val pinned = Pinned.of(enrichment)
        val fields = mutableListOf(
            "call" to currentCall()?.toString(),
            "sdk" to instance?.toString(),
            "event" to event,
            // Segment applies the enrichment last, so a user it pins wins. Empty is anonymous
            "user" to (pinned.userId ?: userId),
            "anon" to pinned.anonymousId,
            "token" to pinned.deviceToken,
            "ts" to pinned.timestamp
        )
        runCatching { properties() as? JsonObject }.getOrNull()?.forEach { (key, value) ->
            // A null property is carried empty
            if (value is JsonPrimitive) fields += "p.$key" to if (value is JsonNull) "" else value.content
        }
        emit("handed off to Segment", ev, "out", fields)
    }

    /** [value] as Segment serializes it for the call, or null if it can't be. */
    fun <T> encode(serializationStrategy: SerializationStrategy<T>, value: T): JsonElement? =
        runCatching { Json.encodeToJsonElement(serializationStrategy, value) }.getOrNull()

    fun reset(instance: Int?) {
        if (!isEnabled) return
        emit("handed off to Segment", "segment.reset", "out", listOf("call" to currentCall()?.toString(), "sdk" to instance?.toString()))
    }

    /**
     * [text] as the tail prints a key or value, the same on iOS: each UTF-8 byte of `%`, whitespace,
     * `=`, `,`, `:`, `|` and control characters as `%XX` (uppercase hex), everything else literal.
     * No key is special, a token included.
     */
    fun percentEncode(text: String): String {
        var index = text.indexOfFirst { it.needsEncoding() }
        if (index < 0) return text
        val encoded = StringBuilder(text.length + 16).append(text, 0, index)
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val length = Character.charCount(codePoint)
            if (codePoint.needsEncoding()) {
                for (byte in text.substring(index, index + length).toByteArray(Charsets.UTF_8)) {
                    encoded.append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
                }
            } else {
                encoded.append(text, index, index + length)
            }
            index += length
        }
        return encoded.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    private fun Char.needsEncoding(): Boolean = code.needsEncoding()

    private fun Int.needsEncoding(): Boolean = this == '%'.code || this == '='.code || this == ','.code ||
        this == ':'.code || this == '|'.code || Character.isWhitespace(this) || Character.isSpaceChar(this) ||
        Character.isISOControl(this)

    private fun emit(message: String, ev: String, io: String, fields: List<Pair<String, String?>>) {
        val line = StringBuilder(message).append(DELIMITER).append("ev=").append(ev).append(" io=").append(io)
        for ((key, value) in fields) {
            // Not carried: left out. Carried empty prints as `key=`
            if (value == null) continue
            line.append(' ').append(percentEncode(key)).append('=').append(percentEncode(value))
        }
        SDKComponent.logger.debug(line.toString())
    }

    /** What an enrichment closure sets on an event, read off a throwaway one. */
    private class Pinned(val userId: String?, val anonymousId: String?, val timestamp: String?, val deviceToken: String?) {
        companion object {
            // Marks "not set by the enrichment" on the probe event
            private const val UNSET = "\u0000unset"

            /** Every enrichment the SDK hands off is a plain field setter, so applying one to a probe has no side effects. */
            fun of(enrichment: EnrichmentClosure?): Pinned {
                val event: BaseEvent? = enrichment?.let { closure ->
                    runCatching {
                        val probe = TrackEvent(emptyJsonObject, "probe").apply {
                            userId = UNSET
                            anonymousId = UNSET
                            timestamp = UNSET
                            messageId = ""
                            integrations = emptyJsonObject
                            context = emptyJsonObject
                        }
                        closure(probe)
                    }.getOrNull()
                }
                return Pinned(
                    userId = event?.userId?.takeUnless { it == UNSET },
                    anonymousId = event?.anonymousId?.takeUnless { it == UNSET },
                    timestamp = event?.timestamp?.takeUnless { it == UNSET },
                    deviceToken = runCatching {
                        event?.context?.get("device")?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                )
            }
        }
    }
}
