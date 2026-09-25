package io.customer.geofence.replay

import io.customer.geofence.polygon.PolygonCoordinate
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
    val transitionTypes: List<String>,
    // Present only for a polygon: the outer ring the transform folded out of `fence.cataloged`. The
    // runner registers the polygon from these and reads latitude/longitude/radius above as its
    // enclosing wake circle; absent, the fence is the circle those three describe.
    val vertices: List<PolygonCoordinate>? = null
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
            // A polygon carries the same wire shape iOS decodes straight into the SDK model: a GeoJSON
            // `geometry` and an `enclosingCircle`. Its wake circle comes from `enclosingCircle`, not
            // the flat fields — those belong to a circle fence. Absent, this is that circle.
            val geometry = o["geometry"] as? JsonObject
            val enclosing = o["enclosingCircle"] as? JsonObject
            val vertices = geometry?.let(::parseGeometry)
            val circle = enclosing ?: o
            val radiusKey = if (enclosing != null) "baseRadiusM" else "radius"
            ScenarioFence(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNullSafe(),
                latitude = circle["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null,
                longitude = circle["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null,
                radius = circle[radiusKey]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null,
                geosetIds = (o["geosetIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
                    ?: emptyList(),
                transitionTypes = (o["transitionTypes"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
                    ?: emptyList(),
                vertices = vertices
            )
        }
    }

    /** The outer ring of a GeoJSON `Polygon`: `coordinates[0]` as `[longitude, latitude]` positions. */
    private fun parseGeometry(geometry: JsonObject): List<PolygonCoordinate>? {
        val rings = geometry["coordinates"] as? JsonArray ?: return null
        val outer = rings.firstOrNull() as? JsonArray ?: return null
        val vertices = outer.mapNotNull { position ->
            val pair = position as? JsonArray ?: return@mapNotNull null
            if (pair.size < 2) return@mapNotNull null
            val longitude = pair[0].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
            val latitude = pair[1].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
            PolygonCoordinate(latitude, longitude)
        }
        return vertices.ifEmpty { null }
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
