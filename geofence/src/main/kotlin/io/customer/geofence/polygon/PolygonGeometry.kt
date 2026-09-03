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

/** Canonical polygon outer ring. V1 does not support holes or antimeridian crossings. */
internal class PolygonGeometry private constructor(
    val vertices: List<PolygonCoordinate>
) {
    fun relationTo(point: PolygonCoordinate): PolygonPointRelation {
        var inside = false
        var previous = vertices.last()

        for (current in vertices) {
            if (point.isOnSegment(previous, current)) return PolygonPointRelation.BOUNDARY

            val intersects = (current.latitude > point.latitude) != (previous.latitude > point.latitude) &&
                point.longitude < (previous.longitude - current.longitude) *
                (point.latitude - current.latitude) /
                (previous.latitude - current.latitude) + current.longitude
            if (intersects) inside = !inside
            previous = current
        }

        return if (inside) PolygonPointRelation.INSIDE else PolygonPointRelation.OUTSIDE
    }

    fun boundaryDistanceMeters(point: PolygonCoordinate): Double {
        var minimumDistance = Double.MAX_VALUE
        var previous = vertices.last()
        for (current in vertices) {
            minimumDistance = min(minimumDistance, point.distanceToSegmentMeters(previous, current))
            previous = current
        }
        return minimumDistance
    }

    private fun PolygonCoordinate.distanceToSegmentMeters(
        first: PolygonCoordinate,
        second: PolygonCoordinate
    ): Double {
        val firstOffset = localOffsetMeters(first)
        val secondOffset = localOffsetMeters(second)
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

    private fun PolygonCoordinate.localOffsetMeters(destination: PolygonCoordinate): Pair<Double, Double> {
        val latitudeDelta = Math.toRadians(destination.latitude - latitude)
        val longitudeDelta = Math.toRadians(normalizeLongitude(destination.longitude - longitude))
        val meanLatitude = Math.toRadians((latitude + destination.latitude) / 2.0)
        return Pair(
            EARTH_RADIUS_METERS * longitudeDelta * cos(meanLatitude),
            EARTH_RADIUS_METERS * latitudeDelta
        )
    }

    private fun normalizeLongitude(longitude: Double): Double =
        ((longitude + 540.0) % 360.0) - 180.0

    private fun PolygonCoordinate.isOnSegment(
        first: PolygonCoordinate,
        second: PolygonCoordinate
    ): Boolean {
        val cross = (latitude - first.latitude) * (second.longitude - first.longitude) -
            (longitude - first.longitude) * (second.latitude - first.latitude)
        val scale = max(
            1.0,
            max(
                abs(second.longitude - first.longitude),
                abs(second.latitude - first.latitude)
            )
        )
        if (abs(cross) > BOUNDARY_EPSILON * scale) return false

        return longitude >= minOf(first.longitude, second.longitude) - BOUNDARY_EPSILON &&
            longitude <= maxOf(first.longitude, second.longitude) + BOUNDARY_EPSILON &&
            latitude >= minOf(first.latitude, second.latitude) - BOUNDARY_EPSILON &&
            latitude <= maxOf(first.latitude, second.latitude) + BOUNDARY_EPSILON
    }

    internal companion object {
        private const val BOUNDARY_EPSILON = 1e-12
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        fun from(vertices: List<PolygonCoordinate>): PolygonGeometry {
            val canonical = if (vertices.size > 1 && vertices.first() == vertices.last()) {
                vertices.dropLast(1)
            } else {
                vertices
            }

            require(canonical.size >= 3) { "polygon requires at least three vertices" }
            require(canonical.distinct().size >= 3) { "polygon requires at least three distinct vertices" }
            require(canonical.all { abs(it.latitude) <= MAXIMUM_ABSOLUTE_LATITUDE }) {
                "polygons above the supported latitude are unsupported"
            }
            canonical.forEachIndexed { index, current ->
                val next = canonical[(index + 1) % canonical.size]
                require(current != next) { "polygon cannot contain a zero-length edge" }
                require(abs(current.longitude - next.longitude) <= 180.0) {
                    "antimeridian-crossing polygons are unsupported"
                }
            }
            require(canonical.hasNonCollinearVertices()) { "polygon cannot have zero area" }
            require(!canonical.hasSelfIntersection()) { "polygon cannot intersect itself" }

            return PolygonGeometry(canonical.toList())
        }

        /**
         * [from] without the throw, for the callers that must isolate one unusable ring rather than
         * fail around it: wire mapping (one bad record costs itself) and ranking (a region whose
         * stored ring no longer validates is skipped, not ranked as its enclosing circle).
         *
         * Validation is identical — only the failure signal differs.
         */
        fun fromOrNull(vertices: List<PolygonCoordinate>): PolygonGeometry? = try {
            from(vertices)
        } catch (_: IllegalArgumentException) {
            null
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

        // Keeps the coordinate-linear ring inside the conservative geodesic trigger circle.
        // Near a pole an edge interior can be farther from the projected center than every vertex.
        private const val MAXIMUM_ABSOLUTE_LATITUDE = 85.0
    }
}
