package io.customer.sdk.insights

import io.customer.sdk.Version
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.ScopeProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Global diagnostics singleton for easy access throughout the SDK.
 * Delegates to the registered instance in SDKComponent.
 *
 * Usage:
 * ```
 * Diagnostics.record("push_delivered", mapOf("delivery_id" to "123"))
 * Diagnostics.flush()
 * ```
 *
 * This is a convenience wrapper around `SDKComponent.diagnostics` for cleaner API.
 */
object Diagnostics : DiagnosticsRecorder {
    /**
     * Get the underlying diagnostics instance from SDKComponent.
     * Returns NoOpDiagnostics if not initialized.
     */
    @PublishedApi
    internal val instance: DiagnosticsRecorder
        get() = SDKComponent.diagnostics

    override var isEnabled: Boolean
        get() = instance.isEnabled
        set(value) {
            instance.isEnabled = value
        }

    override fun isEnabledForEvent(event: String): Boolean {
        return isEnabled
    }

    override fun record(event: DiagnosticEvent) {
        instance.record(event)
    }

    /**
     * Optimized version that defers event creation until diagnostics is confirmed enabled.
     * Use this for any diagnostic event to avoid unnecessary object allocations when disabled.
     *
     * The entire DiagnosticEvent (including data) is only created if diagnostics is enabled,
     * providing zero overhead when disabled.
     *
     * Example:
     * ```
     * // With metadata
     * Diagnostics.record(PushDiagnosticEvents.PUSH_DELIVERED) {
     *     metadata {
     *         put("delivery_id", JsonPrimitive(deliveryId))
     *         put("device_token", JsonPrimitive(deviceToken))
     *     }
     * }
     *
     * // Without metadata
     * Diagnostics.record(PushDiagnosticEvents.PUSH_NOTIFICATION_RENDER_STARTED)
     * ```
     */
    inline fun record(
        name: String,
        block: DiagnosticBuilderScope.() -> Unit = {}
    ) {
        if (!isEnabled || !isEnabledForEvent(name)) return

        val scope = DiagnosticBuilderScope(name)
        scope.block()
        val (eventName, metadata) = scope.build()

        record(
            event = DiagnosticEvent(
                name = eventName,
                data = buildJsonObject(builderAction = metadata ?: {})
            )
        )
    }

    override fun flush() {
        instance.flush()
    }

    override fun clear() {
        instance.clear()
    }
}

/**
 * Implementation of DiagnosticsRecorder.
 * For easier usage, use the [Diagnostics] object singleton instead of creating instances directly.
 */
class DiagnosticsImpl(
    private val store: DiagnosticsStore,
    private val uploader: DiagnosticsUploader,
    private val logger: Logger,
    private val scopeProvider: ScopeProvider,
    private val deviceStore: io.customer.sdk.data.store.DeviceStore
) : DiagnosticsRecorder {

    private val enabled = AtomicBoolean(false)
    private val sdkVersion = Version.version

    // Cache device context - these don't change during app lifecycle
    private val deviceContext: Map<String, String?> by lazy {
        mapOf(
            "device_os" to "Android",
            "device_os_version" to deviceStore.deviceOSVersion?.toString(),
            "device_model" to deviceStore.deviceModel,
            "device_manufacturer" to deviceStore.deviceManufacturer,
            "app_version" to deviceStore.customerAppVersion,
            "app_package" to deviceStore.customerPackageName
        ).filterValues { it != null }
    }

    override var isEnabled: Boolean
        get() = enabled.get()
        set(value) {
            this.enabled.set(value)
        }

    override fun isEnabledForEvent(event: String): Boolean {
        return isEnabled
    }

    override fun record(event: DiagnosticEvent) {
        if (!isEnabled) return

        val enrichedEvent = enrichEvent(event)
        scopeProvider.eventBusScope.launch {
            store.save(enrichedEvent)
            logEvent(enrichedEvent)
        }
    }

    override fun flush() {
        if (!isEnabled) return

        scopeProvider.eventBusScope.launch {
            val events = store.getAll()
            if (events.isNotEmpty()) {
                uploader.upload(events)
                store.clear()
            }
        }
    }

    override fun clear() {
        scopeProvider.eventBusScope.launch {
            store.clear()
        }
    }

    private fun enrichEvent(event: DiagnosticEvent): DiagnosticEvent {
        val enrichedData = buildJsonObject {
            // Add all existing data
            event.data.forEach { (key, value) ->
                put(key, value)
            }

            // Add SDK version if not already present
            if (!event.data.containsKey("sdk_version")) {
                put("sdk_version", JsonPrimitive(sdkVersion))
            }

            // Add device context if not already present
            deviceContext.forEach { (key, value) ->
                if (!event.data.containsKey(key)) {
                    put(key, JsonPrimitive(value))
                }
            }
        }
        return event.copy(data = enrichedData)
    }

    private fun logEvent(event: DiagnosticEvent) {
        logger.debug("[Diagnostics] ${event.name} @${event.timestamp} → ${event.data}")
    }

    private fun convertMapToJsonObject(map: Map<String, Any?>): JsonObject {
        return buildJsonObject {
            map.forEach { (key, value) ->
                when (value) {
                    null -> put(key, JsonPrimitive(null as String?))
                    is String -> put(key, JsonPrimitive(value))
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
    }
}
