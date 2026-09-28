package io.customer.geofence

import android.content.pm.PackageManager
import io.customer.sdk.core.di.SDKComponent

/**
 * Gates the diagnostic tail. A manifest flag, not an API: a customer app can switch on anything
 * reachable from code, and this gate keeps coordinates out of production logs.
 *
 * Enable with, in the app's `AndroidManifest.xml`:
 * ```xml
 * <meta-data android:name="io.customer.geofence.diagnostics" android:value="true" />
 * ```
 */
internal object GeofenceDiagnostics {
    const val MANIFEST_KEY = "io.customer.geofence.diagnostics"

    @Volatile
    private var override: Boolean? = null

    @Volatile
    private var cached: Boolean? = null

    val isEnabled: Boolean
        get() {
            override?.let { return it }
            cached?.let { return it }
            // Null: context not reachable yet. Left uncached so a read after SDK init gets the real
            // answer.
            val value = readManifest() ?: return false
            cached = value
            return value
        }

    /** Test hook; `null` restores the manifest value. */
    fun setEnabledForTesting(value: Boolean?) {
        override = value
    }

    private fun readManifest(): Boolean? = runCatching {
        val context = SDKComponent.android().applicationContext
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        info.metaData?.getBoolean(MANIFEST_KEY, false) ?: false
    }.getOrNull()
}
