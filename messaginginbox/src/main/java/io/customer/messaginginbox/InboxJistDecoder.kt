package io.customer.messaginginbox

import android.text.format.DateUtils
import io.customer.jist.JistJson
import io.customer.jist.JistTemplate
import io.customer.messaginginapp.inbox.jist.JistInboxMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure (non-Compose) decoders that bridge the visual-inbox DATA LAYER
 * ([io.customer.messaginginapp.inbox.VisualInbox]) to Jist's kotlinx.serialization render
 * types. Kept as plain functions so they can be unit-tested without a Compose runtime.
 *
 * The data layer hands the overlay three raw render inputs:
 *  - templates: a raw JSON string (`VisualInbox.getTemplatesJson()`)
 *  - branding theme: a `Map<String, Any?>` (`VisualInbox.getBranding()?.theme`)
 *  - per-message properties: a typed/nested `Map<String, Any?>` (`JistInboxMessage.properties`)
 *
 * `JistView` consumes them as `Map<String, List<JistTemplate>>`, `JsonObject`, and
 * `Map<String, JsonElement>` respectively. These functions perform that conversion.
 */
internal object InboxJistDecoder {

    /**
     * Decodes the data layer's raw templates registry JSON into the template map Jist renders
     * from: `{ "<name>": [ <template>, ... ], "$schema": ... }`. Top-level keys starting with
     * `$` (e.g. `$schema`) are metadata and dropped — this mirrors the Jist example loader.
     *
     * Tolerant by design: a null/blank/malformed registry, or a value that is not a JSON array
     * of templates, yields an empty map (the inbox simply renders nothing rather than crashing).
     */
    fun decodeTemplates(templatesJson: String?): Map<String, List<JistTemplate>> {
        if (templatesJson.isNullOrBlank()) return emptyMap()
        val root = runCatching { JistJson.parseToJsonElement(templatesJson) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        return root.entries
            .filterNot { (key, _) -> key.startsWith("$") }
            .mapNotNull { (key, value) ->
                val versions = (value as? JsonArray)?.mapNotNull { element ->
                    runCatching { JistJson.decodeFromJsonElement(JistTemplate.serializer(), element) }.getOrNull()
                } ?: return@mapNotNull null
                key to versions
            }
            .toMap()
    }

    /**
     * Converts a branding `theme` map (or a message `properties` map) into a [JsonObject].
     * Nested maps/lists and Boolean/Number/String/Date leaves are preserved (Date is rendered
     * as an ISO-ish string fallback only when no better representation exists). A null map
     * yields an empty [JsonObject].
     */
    fun toJsonObject(map: Map<String, Any?>?): JsonObject {
        if (map.isNullOrEmpty()) return JsonObject(emptyMap())
        return JsonObject(map.mapValues { (_, v) -> toJsonElement(v) })
    }

    /**
     * Per-message render data for `JistView(data = ...)`. The data layer preserves typed/nested
     * properties on [JistInboxMessage.properties]; this maps them to `Map<String, JsonElement>`.
     */
    fun decodeData(message: JistInboxMessage): Map<String, JsonElement> = toJsonObject(message.properties)

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Date -> JsonPrimitive(value.toInstantString())
        is Map<*, *> -> JsonObject(
            value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) }
        )
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        is Array<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }

    // ISO-8601 date helpers WITHOUT java.time, so the SDK needs no core-library desugaring on
    // minSdk < 26 (mirrors Jist's own approach). SimpleDateFormat is expensive to construct and is
    // NOT thread-safe, so cache one per thread. The classic initialValue() override is intentional —
    // ThreadLocal.withInitial is a Java 8 default method that WOULD re-introduce the desugaring need.

    /** Per-thread ISO-8601 (UTC, millis) formatter used to render Date leaves as strings. */
    private val isoFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }

    /** Per-thread ISO-8601 parsers (UTC, with and without fractional seconds). */
    private val isoParsers = object : ThreadLocal<List<SimpleDateFormat>>() {
        override fun initialValue(): List<SimpleDateFormat> =
            listOf("yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss").map { pattern ->
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
            }
    }

    private fun Date.toInstantString(): String = runCatching {
        isoFormatter.get()?.format(this) ?: toString()
    }.getOrElse { toString() }

    /** Fractional-seconds group, e.g. the `123456` in `…:11.123456Z`. */
    private val fractionalSeconds = Regex("\\.(\\d+)")

    /** Trailing numeric UTC offset, e.g. `+05:30`, `-0800` (the colon is optional). */
    private val trailingOffset = Regex("([+-])(\\d{2}):?(\\d{2})$")

    /**
     * Parses an ISO-8601 instant to epoch millis WITHOUT java.time, matching the permissiveness the
     * inbox relied on before (`Instant.parse`): fractional seconds of any length, a trailing `Z`, and
     * numeric `±HH:MM` / `±HHMM` offsets (a value with no designator is treated as UTC). Returns null
     * if the date/time core still doesn't match. SimpleDateFormat only understands 3-digit millis and
     * has no minSdk-21-safe offset token, so the offset/fraction are normalized here first.
     */
    internal fun parseIsoToMillis(iso: String): Long? {
        val parsers = isoParsers.get() ?: return null
        // Clamp fractional seconds to 3 digits (millis), so more/fewer digits than `.SSS` still parse.
        var s = fractionalSeconds.replace(iso.trim()) { "." + it.groupValues[1].take(3).padEnd(3, '0') }
        // Resolve + strip the timezone designator to a UTC offset; absent designator ⇒ assume UTC.
        var offsetMillis = 0L
        if (s.endsWith("Z")) {
            s = s.dropLast(1)
        } else {
            trailingOffset.find(s)?.let { match ->
                val sign = if (match.groupValues[1] == "-") -1 else 1
                offsetMillis = sign * (match.groupValues[2].toLong() * 3_600_000L + match.groupValues[3].toLong() * 60_000L)
                s = s.substring(0, match.range.first)
            }
        }
        // Parse the offset-free time as UTC, then subtract the offset to get the true epoch millis.
        for (parser in parsers) {
            runCatching { parser.parse(s)?.time }.getOrNull()?.let { return it - offsetMillis }
        }
        return null
    }

    /**
     * Jist `formatDate` hook: turns a raw ISO-8601 instant (the decoder feeds dates as ISO strings
     * via [toInstantString]) into a SYSTEM-LOCALIZED relative-time label ("2 hours ago",
     * "yesterday", …) via [DateUtils.getRelativeTimeSpanString] — the platform equivalent of web's
     * `Intl.RelativeTimeFormat`. Localized by the OS, so the inbox is i18n/translation-ready without
     * us hand-rolling or translating strings. Unparseable input falls back to the raw value.
     *
     * `name` is the Jist date-node name (unused here; the same format applies to every inbox date).
     */
    fun formatRelativeDate(iso: String, @Suppress("UNUSED_PARAMETER") name: String, now: Long = System.currentTimeMillis()): String {
        val millis = parseIsoToMillis(iso) ?: return iso
        return DateUtils.getRelativeTimeSpanString(
            millis,
            now,
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
}

/**
 * Number of unread (unopened) messages, used to drive the unread badge. Computed from the
 * data layer's already-selected message list. Extracted as a plain function so the badge
 * logic can be unit-tested without a Compose runtime.
 */
internal fun unopenedInboxCount(messages: List<JistInboxMessage>): Int = messages.count { !it.opened }
