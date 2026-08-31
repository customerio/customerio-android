package io.customer.sdk.core.util

import io.customer.base.internal.InternalCustomerIOApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Internal diagnostics switches.
 *
 * Marked [InternalCustomerIOApi], which is a compile **error** to use without an explicit opt-in,
 * so a host app cannot reach this by accident. Unlike iOS — where the equivalent lives in a module
 * that is not shipped as a package product — Android has no way to expose something to our own
 * sample apps without it appearing in the ABI, so the opt-in annotation is the strongest fence
 * available.
 */
@InternalCustomerIOApi
object CioDiagnostics {
    private val preciseLocation = AtomicBoolean(false)
    private val warningClaimed = AtomicBoolean(false)

    /**
     * Whether geofence diagnostics may include the device's precise position.
     *
     * **Default `false`, and it must stay that way.**
     *
     * Geofence logs carry a lot that is safe at debug level — region identifiers, transition types,
     * counts, ranking positions, reasons, durations, and how good a fix was. Precise coordinates
     * are different in kind. "User entered geofence X" reveals coarse location and is inherent to
     * the feature; it is what the SDK already reports to the backend. A latitude and longitude to
     * five decimal places is strictly extra, and it is what a host app would leak if it shipped
     * with debug logging left on and a crash reporter capturing Logcat.
     *
     * Gates `lat`, `lon`, `alt`, `spd` and `brg` only. Fix *quality* — accuracy, age, provenance —
     * is never gated: it says how good a fix is, never where it is.
     *
     * Leaking anything therefore takes two deliberate actions: this flag set *and* the log level at
     * `DEBUG`, which is not the production default.
     */
    @JvmStatic
    var logPreciseLocation: Boolean
        get() = preciseLocation.get()
        set(value) {
            preciseLocation.set(value)
            // Re-arm the warning so a host app that toggles the flag back on is told again.
            if (!value) warningClaimed.set(false)
        }

    /**
     * Claims the right to log the "precise location is enabled" warning, returning `true` exactly
     * once per enablement rather than on every fix.
     */
    @JvmStatic
    fun claimPreciseLocationWarning(): Boolean = warningClaimed.compareAndSet(false, true)
}
