package io.customer.geofence.polygon

/**
 * Fail-closed opt-in that decides whether a decoded polygon record may become a *monitored* region.
 *
 * Decoding a polygon record and monitoring it are separate capabilities. A build that can only
 * decode must never let a polygon reach OS registration or business transitions: the backend-provided
 * enclosing circle is a coarse proximity trigger wider than the ring, so
 * registering it as a business fence would report ENTER for an area the polygon does not describe.
 *
 * Every seam that turns wire data into monitored state takes one of these and defaults to
 * [Disabled]: a caller that forgets to thread the opt-in — or a path added later that never learns
 * about it — gets the safe behaviour. [Enabled] is the single production opt-in, and the graph
 * ([io.customer.geofence.di.polygonSupport]) hands the same instance to every seam.
 *
 * The backend capability request derives from the same value ([requestedCapabilities]) so the two
 * halves cannot drift apart. Asking for `polygon-v1` while the mapper drops every polygon would make
 * the backend spend response slots on records this build then discards — a partial path that a
 * separate capability constant would allow and this one does not.
 */
internal interface PolygonSupport {
    /** True only when a runtime able to evaluate polygon containment is installed. */
    val isPolygonMonitoringEnabled: Boolean

    /**
     * Geometry capabilities advertised on `POST /geofences/nearest`.
     *
     * Derived, never overridden: a build only asks for a shape it will actually monitor.
     */
    val requestedCapabilities: List<String>
        get() = if (isPolygonMonitoringEnabled) listOf(POLYGON_V1_CAPABILITY) else emptyList()

    companion object {
        /** Wire name of the responsive polygon runtime this SDK implements. */
        const val POLYGON_V1_CAPABILITY = "polygon-v1"

        /** The default at every seam: polygon records are decoded, never monitored. */
        val Disabled: PolygonSupport = object : PolygonSupport {
            override val isPolygonMonitoringEnabled: Boolean = false
        }

        /**
         * The production opt-in for this build: the responsive runtime in
         * [io.customer.geofence.polygon] can evaluate polygon containment, so polygons may be
         * requested, mapped, ranked and registered.
         *
         * Best-effort by design — see [PolygonLocationEngine] for exactly what the low-power
         * responsive path can and cannot observe.
         */
        val Enabled: PolygonSupport = object : PolygonSupport {
            override val isPolygonMonitoringEnabled: Boolean = true
        }
    }
}
