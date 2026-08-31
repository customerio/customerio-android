package io.customer.sdk.core.util

import io.customer.base.internal.InternalCustomerIOApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Switch for the SDK's internal diagnostic instrumentation.
 *
 * Marked [InternalCustomerIOApi], which is a compile **error** to use without an explicit opt-in,
 * so a host app cannot reach this by accident. Unlike iOS — where the equivalent lives in a module
 * that is not shipped as a package product — Android has no way to expose something to our own
 * sample apps without it appearing in the ABI, so the opt-in annotation is the strongest fence
 * available.
 */
@InternalCustomerIOApi
object CioDiagnostics {
    private val diagnosticsEnabled = AtomicBoolean(false)
    private val warningClaimed = AtomicBoolean(false)

    /**
     * Whether the SDK emits machine-readable diagnostic detail alongside its human-readable logs.
     *
     * **Default `false`, and it must stay that way.**
     *
     * This is an audience switch, not a privacy classifier. The `| key=value` tail the geofence
     * logger appends exists for one consumer: our own off-device test harness. No customer reads
     * it, no customer needs it, and no product behaviour depends on it. So the question for any
     * given field is not "is this one sensitive enough to hide" — it is "did we ask for this output
     * here", and in a customer's app the answer is always no.
     *
     * Framing it that way is what makes the guarantee checkable in one line rather than field by
     * field: **with this off, the SDK's log output is byte-identical to what it was before the
     * diagnostics work.** A host app that ships with debug logging left on — the case this protects
     * — sees exactly the prose it saw before, and gains nothing new to leak into a crash reporter
     * or log aggregator.
     *
     * It also means a field added to the tail later needs no privacy review of its own. The
     * alternative, deciding per field whether it reveals position, gets the easy calls right and
     * then quietly gets one wrong.
     *
     * Turning anything on therefore takes two deliberate actions: this flag set *and* the log level
     * at `DEBUG`, which is not the production default.
     */
    @JvmStatic
    var enabled: Boolean
        get() = diagnosticsEnabled.get()
        set(value) {
            diagnosticsEnabled.set(value)
            // Re-arm the warning so a host app that toggles the flag back on is told again.
            if (!value) warningClaimed.set(false)
        }

    /**
     * Claims the right to log the "diagnostics enabled" warning, returning `true` exactly once per
     * enablement rather than on every record.
     */
    @JvmStatic
    fun claimEnabledWarning(): Boolean = warningClaimed.compareAndSet(false, true)
}
