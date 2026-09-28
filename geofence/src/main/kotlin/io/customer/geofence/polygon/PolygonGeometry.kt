package io.customer.geofence.polygon

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PolygonCoordinate(
    @SerialName("latitude")
    val latitude: Double,
    @SerialName("longitude")
    val longitude: Double
)

internal enum class PolygonPointRelation {
    INSIDE,
    OUTSIDE,
    BOUNDARY
}

/** Canonical polygon outer ring. V1 does not support holes. */
internal class PolygonGeometry private constructor(
    val vertices: List<PolygonCoordinate>,
    /**
     * The ring made contiguous: each vertex a short arc from the previous, so an antimeridian ring
     * runs 179.5 -> 180.5, not 179.5 -> -179.5. Queries are mapped onto the same line by
     * [onRingLine]; wrapping each longitude against the query instead would split the ring apart
     * for a query roughly antipodal to it.
     */
    private val ringLongitudes: DoubleArray
) {
    /**
     * Roughly how deep this venue is: `2 x area / perimeter`, in metres. Scales the arrival
     * ceiling in [PolygonAccuracyEvaluator]: once the accuracy circle is as wide as the venue is
     * deep, `inside` carries no information.
     *
     * An O(n) approximation of the maximum inradius, exact for a circle. It errs high on convex
     * rings (the safe direction) but is not a bound: a thin appendage adds perimeter without area
     * and reads low. Projected around the ring's mean position, as iOS does.
     */
    val venueScaleMeters: Double by lazy {
        val meanLatitude = vertices.sumOf(PolygonCoordinate::latitude) / vertices.size
        val meanLongitude = ringLongitudes.average()
        val cosMeanLatitude = cos(Math.toRadians(meanLatitude))
        val x = DoubleArray(vertices.size) {
            EARTH_RADIUS_METERS * Math.toRadians(ringLongitudes[it] - meanLongitude) * cosMeanLatitude
        }
        val y = DoubleArray(vertices.size) {
            EARTH_RADIUS_METERS * Math.toRadians(vertices[it].latitude - meanLatitude)
        }
        var twiceArea = 0.0
        var perimeter = 0.0
        for (index in vertices.indices) {
            val next = (index + 1) % vertices.size
            twiceArea += x[index] * y[next] - x[next] * y[index]
            perimeter += hypot(x[next] - x[index], y[next] - y[index])
        }
        if (perimeter > 0.0) abs(twiceArea) / perimeter else 0.0
    }

    fun relationTo(point: PolygonCoordinate): PolygonPointRelation {
        val pointLongitude = onRingLine(point.longitude)
        var inside = false
        var previousIndex = vertices.lastIndex

        for (index in vertices.indices) {
            if (isOnSegment(point.latitude, pointLongitude, previousIndex, index)) {
                return PolygonPointRelation.BOUNDARY
            }

            val currentLatitude = vertices[index].latitude
            val previousLatitude = vertices[previousIndex].latitude
            val currentLongitude = ringLongitudes[index]
            val previousLongitude = ringLongitudes[previousIndex]
            val intersects = (currentLatitude > point.latitude) != (previousLatitude > point.latitude) &&
                pointLongitude < (previousLongitude - currentLongitude) *
                (point.latitude - currentLatitude) /
                (previousLatitude - currentLatitude) + currentLongitude
            if (intersects) inside = !inside
            previousIndex = index
        }

        return if (inside) PolygonPointRelation.INSIDE else PolygonPointRelation.OUTSIDE
    }

    fun boundaryDistanceMeters(point: PolygonCoordinate): Double {
        val pointLongitude = onRingLine(point.longitude)
        var minimumDistance = Double.MAX_VALUE
        var previousIndex = vertices.lastIndex
        for (index in vertices.indices) {
            minimumDistance = min(
                minimumDistance,
                distanceToSegmentMeters(point.latitude, pointLongitude, previousIndex, index)
            )
            previousIndex = index
        }
        return minimumDistance
    }

    /** [longitude] expressed on [ringLongitudes]' line, so ring and query share one frame. */
    private fun onRingLine(longitude: Double): Double =
        ringLongitudes[0] + normalizeLongitude(longitude - ringLongitudes[0])

    private fun distanceToSegmentMeters(
        pointLatitude: Double,
        pointLongitude: Double,
        firstIndex: Int,
        secondIndex: Int
    ): Double {
        val firstOffset = localOffsetMeters(pointLatitude, pointLongitude, firstIndex)
        val secondOffset = localOffsetMeters(pointLatitude, pointLongitude, secondIndex)
        val segmentX = secondOffset.first - firstOffset.first
        val segmentY = secondOffset.second - firstOffset.second
        val lengthSquared = segmentX * segmentX + segmentY * segmentY
        if (lengthSquared == 0.0) return hypot(firstOffset.first, firstOffset.second)

        val projection = (
            -(firstOffset.first * segmentX + firstOffset.second * segmentY) / lengthSquared
            ).coerceIn(0.0, 1.0)
        return hypot(
            firstOffset.first + projection * segmentX,
            firstOffset.second + projection * segmentY
        )
    }

    /**
     * Offset in metres from the query point to vertex [index].
     *
     * The longitude delta is taken raw, not re-wrapped: both operands already sit on the ring's line,
     * and wrapping here would split a seam-crossing segment back into a near-global one.
     */
    private fun localOffsetMeters(
        pointLatitude: Double,
        pointLongitude: Double,
        index: Int
    ): Pair<Double, Double> {
        val vertexLatitude = vertices[index].latitude
        val latitudeDelta = Math.toRadians(vertexLatitude - pointLatitude)
        val longitudeDelta = Math.toRadians(ringLongitudes[index] - pointLongitude)
        val meanLatitude = Math.toRadians((pointLatitude + vertexLatitude) / 2.0)
        return Pair(
            EARTH_RADIUS_METERS * longitudeDelta * cos(meanLatitude),
            EARTH_RADIUS_METERS * latitudeDelta
        )
    }

    private fun isOnSegment(
        pointLatitude: Double,
        pointLongitude: Double,
        firstIndex: Int,
        secondIndex: Int
    ): Boolean {
        val first = vertices[firstIndex]
        val second = vertices[secondIndex]
        val firstLongitude = ringLongitudes[firstIndex]
        val secondLongitude = ringLongitudes[secondIndex]
        val cross = (pointLatitude - first.latitude) * (secondLongitude - firstLongitude) -
            (pointLongitude - firstLongitude) * (second.latitude - first.latitude)
        val scale = max(
            1.0,
            max(
                abs(secondLongitude - firstLongitude),
                abs(second.latitude - first.latitude)
            )
        )
        if (abs(cross) > BOUNDARY_EPSILON * scale) return false

        return pointLongitude >= minOf(firstLongitude, secondLongitude) - BOUNDARY_EPSILON &&
            pointLongitude <= maxOf(firstLongitude, secondLongitude) + BOUNDARY_EPSILON &&
            pointLatitude >= minOf(first.latitude, second.latitude) - BOUNDARY_EPSILON &&
            pointLatitude <= maxOf(first.latitude, second.latitude) + BOUNDARY_EPSILON
    }

    internal companion object {
        private const val BOUNDARY_EPSILON = 1e-12

        // Half the globe. The flat projection the evaluator uses cannot describe more.
        private const val MAXIMUM_LONGITUDE_SPAN = 180.0

        /** Earth radius for the local flat projection. Matches iOS so both measure the same distances. */
        internal const val EARTH_RADIUS_METERS = 6_371_000.0

        fun from(vertices: List<PolygonCoordinate>): PolygonGeometry {
            require(vertices.isNotEmpty()) { "polygon requires at least one position" }

            // Canonicalised on the unwrapped line so 180 and -180 are one position; otherwise a ring
            // closed with the opposite sign (legal GeoJSON) would be rejected for a zero-length edge.
            val longitudes = unwrapLongitudes(vertices)

            // Compared on source positions: the unwrapped line accumulates float drift (~1e-13), so
            // exact equality there would miss the closure and keep a spurious edge.
            fun samePosition(first: Int, second: Int): Boolean =
                vertices[first].latitude == vertices[second].latitude &&
                    normalizeLongitude(vertices[first].longitude - vertices[second].longitude) == 0.0

            val collapsed = vertices.indices.filter { index -> index == 0 || !samePosition(index, index - 1) }
            val kept = if (collapsed.size > 1 && samePosition(collapsed.first(), collapsed.last())) {
                collapsed.dropLast(1)
            } else {
                collapsed
            }
            val canonical = kept.map(vertices::get)
            val ringLongitudes = DoubleArray(kept.size) { longitudes[kept[it]] }

            require(canonical.size >= 3) { "polygon requires at least three vertices" }
            require(canonical.distinct().size >= 3) { "polygon requires at least three distinct vertices" }
            // One flat frame only describes a shape narrower than a hemisphere. A ring winding
            // around a pole unwraps past that and nothing below rejects it. Only fences within
            // ~55 km of a pole are refused.
            require(ringLongitudes.max() - ringLongitudes.min() < MAXIMUM_LONGITUDE_SPAN) {
                "polygon spans too much longitude to evaluate on one frame"
            }

            val unwrapped = canonical.mapIndexed { index, vertex ->
                PolygonCoordinate(vertex.latitude, ringLongitudes[index])
            }
            unwrapped.forEachIndexed { index, current ->
                val next = unwrapped[(index + 1) % unwrapped.size]
                require(current != next) { "polygon cannot contain a zero-length edge" }
            }
            require(unwrapped.hasNonCollinearVertices()) { "polygon cannot have zero area" }
            require(!unwrapped.hasSelfIntersection()) { "polygon cannot intersect itself" }

            return PolygonGeometry(canonical, ringLongitudes)
        }

        /** [from] without the throw, for callers that skip one unusable ring rather than fail. */
        fun fromOrNull(vertices: List<PolygonCoordinate>): PolygonGeometry? = try {
            from(vertices)
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun normalizeLongitude(longitude: Double): Double =
            ((longitude + 540.0) % 360.0) - 180.0

        /** Ring longitudes as one continuous line, each vertex a short arc from the previous. */
        private fun unwrapLongitudes(vertices: List<PolygonCoordinate>): DoubleArray =
            DoubleArray(vertices.size).also { unwrapped ->
                unwrapped[0] = vertices[0].longitude
                for (index in 1 until vertices.size) {
                    unwrapped[index] = unwrapped[index - 1] +
                        normalizeLongitude(vertices[index].longitude - vertices[index - 1].longitude)
                }
            }

        private fun List<PolygonCoordinate>.hasNonCollinearVertices(): Boolean {
            for (firstIndex in indices) {
                val first = this[firstIndex]
                val second = this[(firstIndex + 1) % size]
                val third = this[(firstIndex + 2) % size]
                if (abs(orientation(first, second, third)) > BOUNDARY_EPSILON) return true
            }
            return false
        }

        private fun List<PolygonCoordinate>.hasSelfIntersection(): Boolean {
            for (firstIndex in indices) {
                val firstStart = this[firstIndex]
                val firstEnd = this[(firstIndex + 1) % size]
                for (secondIndex in firstIndex + 1 until size) {
                    if (segmentsAreAdjacent(firstIndex, secondIndex, size)) continue
                    val secondStart = this[secondIndex]
                    val secondEnd = this[(secondIndex + 1) % size]
                    if (segmentsIntersect(firstStart, firstEnd, secondStart, secondEnd)) return true
                }
            }
            return false
        }

        private fun segmentsAreAdjacent(firstIndex: Int, secondIndex: Int, size: Int): Boolean =
            secondIndex == firstIndex + 1 || firstIndex == 0 && secondIndex == size - 1

        private fun segmentsIntersect(
            firstStart: PolygonCoordinate,
            firstEnd: PolygonCoordinate,
            secondStart: PolygonCoordinate,
            secondEnd: PolygonCoordinate
        ): Boolean {
            val firstOrientation = orientation(firstStart, firstEnd, secondStart)
            val secondOrientation = orientation(firstStart, firstEnd, secondEnd)
            val thirdOrientation = orientation(secondStart, secondEnd, firstStart)
            val fourthOrientation = orientation(secondStart, secondEnd, firstEnd)

            if (firstOrientation * secondOrientation < 0.0 && thirdOrientation * fourthOrientation < 0.0) {
                return true
            }
            return abs(firstOrientation) <= BOUNDARY_EPSILON && secondStart.isWithin(firstStart, firstEnd) ||
                abs(secondOrientation) <= BOUNDARY_EPSILON && secondEnd.isWithin(firstStart, firstEnd) ||
                abs(thirdOrientation) <= BOUNDARY_EPSILON && firstStart.isWithin(secondStart, secondEnd) ||
                abs(fourthOrientation) <= BOUNDARY_EPSILON && firstEnd.isWithin(secondStart, secondEnd)
        }

        private fun orientation(
            first: PolygonCoordinate,
            second: PolygonCoordinate,
            third: PolygonCoordinate
        ): Double =
            (second.longitude - first.longitude) * (third.latitude - first.latitude) -
                (second.latitude - first.latitude) * (third.longitude - first.longitude)

        private fun PolygonCoordinate.isWithin(
            first: PolygonCoordinate,
            second: PolygonCoordinate
        ): Boolean =
            longitude >= minOf(first.longitude, second.longitude) - BOUNDARY_EPSILON &&
                longitude <= maxOf(first.longitude, second.longitude) + BOUNDARY_EPSILON &&
                latitude >= minOf(first.latitude, second.latitude) - BOUNDARY_EPSILON &&
                latitude <= maxOf(first.latitude, second.latitude) + BOUNDARY_EPSILON
    }
}
