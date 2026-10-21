package io.customer.geofence

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Experiments the host turns on with `<meta-data android:name="KEY" android:value="true" />` in its
 * manifest. Read from the package rather than SDK config, so OS wakes that run before initialization
 * see them. Each is off unless declared `true`, including when the manifest cannot be read.
 */
internal class GeofenceExperiments(private val context: Context) {
    // Once per process: the manifest cannot change without the process restarting. The flags
    // overload replacing the deprecated call needs API 33, and minSdk is 21.
    @Suppress("DEPRECATION")
    private val metaData: Bundle? by lazy {
        runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        }.getOrNull()
    }

    /** See [DwellDiagnosticJournal]. */
    fun dwellDiagnosticJournalEnabled(): Boolean =
        metaData?.getBoolean(KEY_DWELL_DIAGNOSTIC_JOURNAL, false) == true

    /**
     * Lets a pending polygon visit re-add its enclosing circle with a native DWELL wake. The wake
     * only asks for polygon evidence and proves nothing; whether the same-id re-add restarts the
     * GMS loiter clock is unmeasured.
     */
    fun polygonDwellWakeEnabled(): Boolean =
        metaData?.getBoolean(KEY_POLYGON_DWELL_WAKE, false) == true

    companion object {
        const val KEY_DWELL_DIAGNOSTIC_JOURNAL = "io.customer.geofence.experimental.DWELL_DIAGNOSTIC_JOURNAL"
        const val KEY_POLYGON_DWELL_WAKE = "io.customer.geofence.experimental.POLYGON_DWELL_WAKE"
    }
}
