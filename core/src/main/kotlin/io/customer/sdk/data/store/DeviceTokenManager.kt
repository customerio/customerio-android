package io.customer.sdk.data.store

import io.customer.sdk.core.di.SDKComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages device token with automatic synchronization between memory and persistent storage.
 *
 * This class provides:
 * - Immediate synchronous access to the current device token
 * - Automatic background persistence to storage
 * - Single source of truth for device token state
 * - Reactive updates via Flow
 * - Automatic initialization from existing storage
 */
interface DeviceTokenManager {
    /**
     * Current device token value. Always returns immediately from memory.
     */
    val deviceToken: String?

    /**
     * Flow of device token changes for reactive programming.
     */
    val deviceTokenFlow: Flow<String?>

    /**
     * Updates the device token. Immediately updates memory and async saves to storage.
     */
    fun setDeviceToken(token: String?)

    /**
     * Clears the device token. Immediately clears memory and async removes from storage.
     */
    fun clearDeviceToken()

    /**
     * Replaces the current token with a new one, handling the transition properly.
     * If there was an existing token different from the new one, calls onOldTokenDelete.
     *
     * @param newToken The new device token to set
     * @param onOldTokenDelete Callback invoked with the old token if a replacement occurred
     */
    fun replaceToken(newToken: String?, onOldTokenDelete: (String) -> Unit)

    /**
     * Cancels any pending operations and cleans up resources.
     * Should be called when the manager is no longer needed.
     */
    fun cleanup()
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class DeviceTokenManagerImpl(
    private val globalPreferenceStore: GlobalPreferenceStore
) : DeviceTokenManager {

    private val _deviceTokenFlow = MutableStateFlow<String?>(null)
    private val backgroundScope: CoroutineScope = SDKComponent.scopeProvider.backgroundScope
    private val logger = SDKComponent.logger

    // Single-threaded dispatcher for all token operations to eliminate race conditions
    private val tokenOperationScope = CoroutineScope(
        backgroundScope.coroutineContext +
            Dispatchers.Default.limitedParallelism(1) +
            SupervisorJob()
    )

    init {
        tokenOperationScope.launch {
            try {
                val existingToken = globalPreferenceStore.getDeviceToken()
                _deviceTokenFlow.value = existingToken
            } catch (e: Exception) {
                logger.error("Error during DeviceTokenManager initialization: ${e.message}")
                // Continue with null token - SDK must not crash the host app
                _deviceTokenFlow.value = null
            }
        }
    }

    override val deviceToken: String?
        get() = _deviceTokenFlow.value

    override val deviceTokenFlow: Flow<String?>
        get() = _deviceTokenFlow.asStateFlow()

    override fun setDeviceToken(token: String?) {
        tokenOperationScope.launch {
            // Update memory state immediately
            _deviceTokenFlow.value = token

            // Persist to storage asynchronously with proper error handling
            try {
                if (token != null) {
                    globalPreferenceStore.saveDeviceToken(token)
                } else {
                    globalPreferenceStore.removeDeviceToken()
                }
            } catch (e: Exception) {
                logger.error("Error saving device token: ${e.message}")
                // Memory state is already updated, SDK must not crash the host app
            }
        }
    }

    override fun clearDeviceToken() {
        setDeviceToken(null)
    }

    override fun replaceToken(newToken: String?, onOldTokenDelete: (String) -> Unit) {
        tokenOperationScope.launch {
            val currentToken = _deviceTokenFlow.value

            // If there's an existing token and it's different from the new one, notify about deletion
            if (currentToken != null && currentToken != newToken) {
                onOldTokenDelete(currentToken)
            }

            // Update memory state immediately
            _deviceTokenFlow.value = newToken

            // Persist to storage asynchronously with proper error handling
            try {
                if (newToken != null) {
                    globalPreferenceStore.saveDeviceToken(newToken)
                } else {
                    globalPreferenceStore.removeDeviceToken()
                }
            } catch (e: Exception) {
                logger.error("Error saving replaced device token: ${e.message}")
                // Memory state is already updated, SDK must not crash the host app
            }
        }
    }

    override fun cleanup() {
        tokenOperationScope.cancel()
    }
}
