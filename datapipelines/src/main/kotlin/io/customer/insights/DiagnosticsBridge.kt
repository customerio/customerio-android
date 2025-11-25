package io.customer.insights

import android.content.Context
import com.segment.analytics.kotlin.core.Analytics
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerDiagnostics
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.insights.DiagnosticsImpl
import io.customer.sdk.insights.DiagnosticsStore
import io.customer.sdk.insights.FileDiagnosticsStore
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import sovran.kotlin.Store
import sovran.kotlin.Subscriber

/**
 * Bridges Diagnostics (core module) with Analytics (datapipelines module).
 *
 * Responsibilities:
 * - Creates and manages DiagnosticsImpl instance
 * - Subscribes to Analytics settings updates via Sovran
 * - Monitors server-side sampleRate to enable/disable diagnostics dynamically
 * - Registers diagnostics with SDKComponent for global access
 * - Coordinates diagnostics lifecycle with Analytics
 */
internal class DiagnosticsBridge(
    private val context: Context,
    private val diagnosticsEnabled: Boolean,
    private val logger: Logger = SDKComponent.logger,
    private val dispatchers: DispatchersProvider = SDKComponent.dispatchersProvider,
    private val scopeProvider: ScopeProvider = SDKComponent.scopeProvider
) : Subscriber {

    private var diagnosticsDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // Diagnostics instance - stays in memory and collects events
    private val diagnosticsInstance: DiagnosticsImpl by lazy {
        val store: DiagnosticsStore = FileDiagnosticsStore(
            context = context,
            dispatchers = dispatchers
        )
        val uploader = DiagnosticsHttpUploader(logger = logger)
        val deviceStore = SDKComponent.android().deviceStore

        val instance = DiagnosticsImpl(
            store = store,
            uploader = uploader,
            logger = logger,
            scopeProvider = scopeProvider,
            deviceStore = deviceStore
        )

        // Register with SDKComponent for global access
        SDKComponent.registerDiagnostics(instance)

        return@lazy instance
    }

    internal fun setup(analytics: Analytics) {
        // Only proceed if user has opted in
        if (!diagnosticsEnabled) {
            logger.debug("Diagnostics not enabled in SDK config")
            return
        }

        with(analytics) {
            analyticsScope.launch(analyticsDispatcher) {
                subscribe(store)
            }
        }

        // Subscribe to FlushEvent to coordinate diagnostics flush with Analytics flush
        subscribeToFlush()
    }

    internal suspend fun subscribe(store: Store) {
        store.subscribe(
            this,
            com.segment.analytics.kotlin.core.System::class,
            initialState = true,
            handler = ::systemUpdate,
            queue = diagnosticsDispatcher
        )
    }

    /**
     * Monitor system settings from server to enable/disable diagnostics based on sampleRate
     */
    private suspend fun systemUpdate(system: com.segment.analytics.kotlin.core.System) {
        system.settings?.let { settings ->
            val sampleRate = settings.metrics["sampleRate"]?.jsonPrimitive?.double ?: 0.0

            if (sampleRate > 0.0) {
                // Server allows diagnostics - enable it
                if (!diagnosticsInstance.isEnabled) {
                    logger.debug("Enabling diagnostics with sampleRate: $sampleRate")
                    diagnosticsInstance.isEnabled = true
                }
            } else {
                // sampleRate is 0 - disable diagnostics
                if (diagnosticsInstance.isEnabled) {
                    logger.debug("Disabling diagnostics (sampleRate: $sampleRate)")
                    diagnosticsInstance.isEnabled = false
                }
            }
        }
    }

    /**
     * Subscribe to FlushEvent from Analytics to coordinate diagnostics flush.
     * Diagnostics will flush whenever Analytics flushes (based on FlushPolicies).
     */
    private fun subscribeToFlush() {
        SDKComponent.eventBus.subscribe<Event.FlushEvent> {
            if (diagnosticsInstance.isEnabled) {
                logger.debug("Flushing diagnostics (coordinated with Analytics flush)")
                diagnosticsInstance.flush()
            }
        }
    }

    /**
     * Manually trigger flush.
     * Prefer using EventBus to publish FlushEvent instead:
     * `SDKComponent.eventBus.publish(Event.FlushEvent)`
     */
    fun flush() {
        diagnosticsInstance.flush()
    }

    /**
     * Get the diagnostics instance for manual event recording.
     * Generally not needed - use the global Diagnostics object instead.
     */
    fun diagnostics(): DiagnosticsImpl = diagnosticsInstance
}
