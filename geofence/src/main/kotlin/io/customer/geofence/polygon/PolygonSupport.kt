package io.customer.geofence.polygon

/**
 * Fail-closed opt-in: without it a polygon must not reach registration, as its wider enclosing
 * circle would report ENTER for area the ring excludes. Every seam defaults to [Disabled]; the
 * graph ([io.customer.geofence.di.polygonSupport]) supplies [Enabled].
 */
internal interface PolygonSupport {
    val isPolygonMonitoringEnabled: Boolean

    companion object {
        val Disabled: PolygonSupport = object : PolygonSupport {
            override val isPolygonMonitoringEnabled: Boolean = false
        }

        val Enabled: PolygonSupport = object : PolygonSupport {
            override val isPolygonMonitoringEnabled: Boolean = true
        }
    }
}
