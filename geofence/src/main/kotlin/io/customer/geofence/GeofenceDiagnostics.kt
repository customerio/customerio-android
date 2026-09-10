package io.customer.geofence

import android.content.pm.PackageManager
import io.customer.sdk.core.di.SDKComponent

/**
 * Whether the SDK emits the diagnostic tail.
 *
 * Read from the host app's manifest, deliberately not from an API. Anything reachable — even
 * behind an opt-in annotation — is something a customer app can switch on, and the tail is the
 * only thing keeping coordinates out of a production log.
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
            // Null means the context was not reachable yet — leave it uncached so a later read,
            // once the SDK is initialized, still gets the real answer.
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
