package io.customer.geofence.polygon

/**
 * Fail-closed opt-in that decides whether a decoded polygon record may become a *monitored* region.
 *
 * This module ships the polygon geometry and the wire contract only. It can decode a polygon record
 * and answer geometric questions about it, but it cannot acquire the fine-grained fixes a polygon
 * needs to be evaluated — so a polygon must never reach OS registration or business transitions from
 * this code alone. The enclosing circle ([PolygonEnclosingCircle]) is a coarse proximity trigger,
 * kilometres wider than the ring; registering it as a business fence would report ENTER for an area
 * the polygon does not describe.
 *
 * Every seam that turns wire data into monitored state takes one of these and defaults to
 * [Disabled]. There is deliberately no enabled implementation in this module and no mutable global
 * to flip: enabling polygon monitoring means passing an implementation that answers `true` at each
 * seam, which the polygon runtime supplies when it lands. A caller that forgets to thread the
 * opt-in — or a path added later that never learns about it — gets the safe behaviour.
 */
internal interface PolygonSupport {
    /** True only when a runtime able to evaluate polygon containment is installed. */
    val isPolygonMonitoringEnabled: Boolean

    companion object {
        /** The default at every seam: polygon records are decoded, never monitored. */
        val Disabled: PolygonSupport = object : PolygonSupport {
            override val isPolygonMonitoringEnabled: Boolean = false
        }
    }
}
