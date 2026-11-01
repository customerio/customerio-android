package io.customer.datapipelines.handoff

import android.content.pm.PackageManager
import io.customer.sdk.core.di.SDKComponent

/**
 * Gates the hand-off log ([DataPipelinesLogTail]), together with the SDK's debug log level. A manifest flag,
 * not an API, like geofence's diagnostics: the lines carry user ids and device tokens, so a
 * customer app shouldn't turn them on from code.
 *
 * Enable with, in the app's `AndroidManifest.xml`:
 * ```xml
 * <meta-data android:name="io.customer.datapipelines.diagnostics" android:value="true" />
 * ```
 */
internal object DataPipelinesDiagnostics {
    const val MANIFEST_KEY = "io.customer.datapipelines.diagnostics"

    @Volatile
    private var override: Boolean? = null

    @Volatile
    private var cached: Boolean? = null

    val isEnabled: Boolean
        get() = read() ?: false

    /**
     * The flag, or null while the context isn't reachable yet. Null is left uncached so a read after
     * SDK init gets the real answer.
     */
    fun read(): Boolean? {
        override?.let { return it }
        cached?.let { return it }
        val value = readManifest() ?: return null
        cached = value
        return value
    }

    /** Test hook; `null` restores the manifest value. The log reads it on each check until the next initialize. */
    fun setEnabledForTesting(value: Boolean?) {
        override = value
        DataPipelinesLogTail.unresolve()
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
