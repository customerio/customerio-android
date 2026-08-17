package io.customer.geofence.polygon

/**
 * Stands in for the opt-in the polygon runtime supplies.
 *
 * Production code in this module has no implementation that answers `true` — the runtime PR brings
 * one. Tests use this to prove the difference is the opt-in itself: the same input that is dropped
 * with [PolygonSupport.Disabled] maps and ranks with this.
 */
internal object EnabledPolygonSupport : PolygonSupport {
    override val isPolygonMonitoringEnabled: Boolean = true
}
