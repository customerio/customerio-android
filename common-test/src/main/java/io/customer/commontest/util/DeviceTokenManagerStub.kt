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

    fun reset() {
        _deviceTokenFlow.value = null
    }
}
