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
}

internal class DeviceTokenManagerImpl(
    private val globalPreferenceStore: GlobalPreferenceStore
) : DeviceTokenManager {

    private val _deviceTokenFlow = MutableStateFlow<String?>(null)
    private val backgroundScope: CoroutineScope = SDKComponent.scopeProvider.backgroundScope

    init {
        // Load existing token from storage in background
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
        // Update memory immediately
        _deviceTokenFlow.value = token

        // Save to storage asynchronously
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
}
