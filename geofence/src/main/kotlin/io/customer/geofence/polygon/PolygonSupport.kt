package io.customer.geofence.polygon

/**
 * Fail-closed opt-in deciding whether a decoded polygon record may become a monitored region.
 *
 * Without it a polygon must never reach OS registration or business transitions: its enclosing
 * circle is wider than the ring, so registering it as a business fence would report ENTER for an
 * area the polygon does not describe. Every seam defaults to [Disabled] so a caller that forgets
 * the opt-in stays safe; the graph ([io.customer.geofence.di.polygonSupport]) supplies [Enabled].
 */
internal interface PolygonSupport {
    /** True only when a runtime able to evaluate polygon containment is installed. */
    val isPolygonMonitoringEnabled: Boolean

    companion object {
        /** The default at every seam: polygon records are decoded, never monitored. */
        val Disabled: PolygonSupport = object : PolygonSupport {
            override val isPolygonMonitoringEnabled: Boolean = false
        }

        /**
         * The production opt-in: polygons may be mapped, ranked and registered.
         * Detection is best-effort; see [PolygonLocationEngine].
         */
        val Enabled: PolygonSupport = object : PolygonSupport {
            override val isPolygonMonitoringEnabled: Boolean = true
        }
    }
}
