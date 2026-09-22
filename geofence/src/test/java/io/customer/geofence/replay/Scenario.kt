package io.customer.geofence.replay

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One recorded drive, as the `capture2scenario.py` transform emits it.
 *
 * The format is deliberately dumb: NDJSON, one header line then one record per line, each tagged
 * `given` / `when` / `then` / `note`. The tagging comes from the SDK's own `io=` classification, so
 * a record cannot be logged as one thing and graded as another — which is the property the whole
 * exercise rests on.
 */
internal data class Scenario(
    val name: String,
    val platform: String,
    /**
     * `recorded` for a drive taken off a phone, `authored` for a scenario somebody wrote. Read
     * from `source.kind`, which every header carries, rather than from the directory the file sits
     * in — the corpus is one flat directory and provenance has to travel inside the file.
     *
     * Absent or unrecognised becomes `unknown` and discovery rejects it, mirroring how `platform`
     * is handled. It must not default to `recorded`: that let an authored scenario with no
     * `source` stand in for a phone capture and satisfy the guard that asks whether any drive was
     * found at all.
     */
    val sourceKind: String,
    val sdkVersion: String?,
    val device: String?,
    val records: List<ScenarioRecord>
) {
    /**
     * Whether this came off a phone. Only used to keep the "did discovery find any drives" guard
     * honest: a corpus of authored scenarios alone must not satisfy it.
     *
     * Deliberately NOT what decides where a scenario runs. That is [platform] alone.
     */
    val isRecorded: Boolean get() = sourceKind == "recorded"

    /** Pre-existing world: the fetch responses, with their fence catalogues folded in. */
    val given: List<ScenarioRecord> get() = records.filter { it.kind == Kind.GIVEN }

    /** Inputs crossing into the SDK, in recorded order. */
    val stimuli: List<ScenarioRecord> get() = records.filter { it.kind == Kind.WHEN }

    /** Decisions the SDK made. The only thing replay asserts. */
    val expectations: List<ScenarioRecord> get() = records.filter { it.kind == Kind.THEN }

    enum class Kind { GIVEN, WHEN, THEN, NOTE }
}

internal data class ScenarioRecord(
    val kind: Scenario.Kind,
    /** Seconds since the capture's `t0`. Drives the virtual clock. */
    val at: Double,
    val ev: String,
    val fields: Map<String, Any?>,
    /** Only on `fixture.api.fetch`: the fences the response carried. */
    val body: List<ScenarioFence> = emptyList()
) {
    fun string(key: String): String? = fields[key]?.toString()

    fun double(key: String): Double? = when (val v = fields[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    fun boolean(key: String): Boolean? = fields[key] as? Boolean

    /**
     * The fences a record names.
     *
     * Android batches — one GMS broadcast can carry several fences under `ids` — where iOS reports
     * one `id` per callback. Reading both keeps one loader honest about two platforms.
     */
    fun geofenceIds(): List<String> {
        string("ids")?.let { raw ->
            return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return listOfNotNull(string("id"))
    }
}

internal data class ScenarioFence(
    val id: String,
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    val geosetIds: List<String>,
    val transitionTypes: List<String>
)

internal object ScenarioLoader {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(file: File): Scenario {
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.isNotEmpty()) { "${file.name}: empty scenario" }

        val header = json.parseToJsonElement(lines.first()).jsonObject
        require(header["k"]?.jsonPrimitive?.content == "scenario") {
            "${file.name}: first line is not a scenario header"
        }

        val records = lines.drop(1).mapIndexed { index, line ->
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrElse {
                throw IllegalArgumentException("${file.name}: line ${index + 2} is not valid JSON", it)
            }
            parseRecord(obj, file.name, index + 2)
        }

        return Scenario(
            name = header["name"]?.jsonPrimitive?.content ?: file.nameWithoutExtension,
            platform = header["platform"]?.jsonPrimitive?.content ?: "unknown",
            sourceKind = header["source"]?.jsonObject?.get("kind")?.jsonPrimitive?.content ?: "unknown",
            sdkVersion = header["sdk"]?.jsonPrimitive?.contentOrNullSafe(),
            device = header["device"]?.jsonPrimitive?.contentOrNullSafe(),
            records = records
        )
    }

    private fun parseRecord(obj: JsonObject, fileName: String, lineNumber: Int): ScenarioRecord {
        val kind = when (val k = obj["k"]?.jsonPrimitive?.content) {
            "given" -> Scenario.Kind.GIVEN
            "when" -> Scenario.Kind.WHEN
            "then" -> Scenario.Kind.THEN
            "note" -> Scenario.Kind.NOTE
            // Rejected, not dropped. Returning null here left the file readable while a record
            // vanished from the replay — a misspelled `k` would take its input or its assertion
            // with it and the drive would still report green. iOS throws on the same input.
            else -> throw IllegalArgumentException("$fileName: line $lineNumber has unknown k '$k'")
        }
        val ev = obj["ev"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("$fileName: line $lineNumber has no ev")
        // Absent rather than defaulted: a record with no `at` cannot be placed on the timeline, and
        // silently calling it t=0 would reorder the drive.
        val at = obj["at"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: throw IllegalArgumentException("$fileName: line $lineNumber ($ev) has no usable `at`")

        val fields = obj
            .filterKeys { it !in setOf("k", "at", "ev", "body") }
            .mapValues { (_, value) -> scalar(value) }

        return ScenarioRecord(kind, at, ev, fields, parseBody(obj["body"]))
    }

    private fun parseBody(element: kotlinx.serialization.json.JsonElement?): List<ScenarioFence> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            val o = entry as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            ScenarioFence(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNullSafe(),
                latitude = o["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null,
                longitude = o["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null,
                radius = o["radius"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null,
                geosetIds = (o["geosetIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
                    ?: emptyList(),
                transitionTypes = (o["transitionTypes"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
                    ?: emptyList()
            )
        }
    }

    /** Values arrive already coerced by the transform, so the type is whatever JSON says it is. */
    private fun scalar(element: kotlinx.serialization.json.JsonElement): Any? {
        val primitive = element as? JsonPrimitive ?: return element.toString()
        if (primitive is JsonNull) return null
        primitive.booleanOrNull?.let { return it }
        if (primitive.isString) return primitive.content
        primitive.content.toLongOrNull()?.let { return it }
        primitive.content.toDoubleOrNull()?.let { return it }
        return primitive.content
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content
}
