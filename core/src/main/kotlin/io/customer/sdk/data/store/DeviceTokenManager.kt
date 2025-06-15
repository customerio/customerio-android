package io.customer.sdk.data.store

import io.customer.sdk.core.di.SDKComponent
import kotlinx.coroutines.CoroutineScope
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
}

internal class DeviceTokenManagerImpl(
    private val globalPreferenceStore: GlobalPreferenceStore
) : DeviceTokenManager {

    private val _deviceTokenFlow = MutableStateFlow<String?>(null)
    private val backgroundScope: CoroutineScope = SDKComponent.scopeProvider.backgroundScope

    init {
        backgroundScope.launch {
            val existingToken = globalPreferenceStore.getDeviceToken()
            _deviceTokenFlow.value = existingToken
        }
    }

    override val deviceToken: String?
        get() = _deviceTokenFlow.value

    override val deviceTokenFlow: Flow<String?>
        get() = _deviceTokenFlow.asStateFlow()

    override fun setDeviceToken(token: String?) {
        _deviceTokenFlow.value = token

        backgroundScope.launch {
            if (token != null) {
                globalPreferenceStore.saveDeviceToken(token)
            } else {
                globalPreferenceStore.removeDeviceToken()
            }
        }
    }

    override fun clearDeviceToken() {
        setDeviceToken(null)
    }

    override fun replaceToken(newToken: String?, onOldTokenDelete: (String) -> Unit) {
        val currentToken = _deviceTokenFlow.value

        // If there's an existing token and it's different from the new one, notify about deletion
        if (currentToken != null && currentToken != newToken) {
            onOldTokenDelete(currentToken)
        }

        setDeviceToken(newToken)
    }
}
