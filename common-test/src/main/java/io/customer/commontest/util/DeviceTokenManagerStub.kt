package io.customer.commontest.util

import io.customer.sdk.data.store.DeviceTokenManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceTokenManagerStub : DeviceTokenManager {
    private val _deviceTokenFlow = MutableStateFlow<String?>(null)

    override val deviceToken: String?
        get() = _deviceTokenFlow.value

    override val deviceTokenFlow: Flow<String?>
        get() = _deviceTokenFlow.asStateFlow()

    override fun setDeviceToken(token: String?) {
        _deviceTokenFlow.value = token
    }

    override fun clearDeviceToken() {
        _deviceTokenFlow.value = null
    }

    override fun replaceToken(newToken: String?, onOldTokenDelete: (String) -> Unit) {
        val currentToken = _deviceTokenFlow.value

        // If there's an existing token and it's different from the new one, notify about deletion
        if (currentToken != null && currentToken != newToken) {
            onOldTokenDelete(currentToken)
        }

        // Set the new token
        _deviceTokenFlow.value = newToken
    }

    override fun cleanup() {
        // No-op for stub
    }

    fun reset() {
        _deviceTokenFlow.value = null
    }
}
