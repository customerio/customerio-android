package io.customer.sdk.data.store

import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.data.model.DeviceTokenType
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlobalPreferenceStoreTest : RobolectricTest() {

    private val store: GlobalPreferenceStore = GlobalPreferenceStoreImpl(contextMock)

    @Test
    fun saveDeviceToken_givenType_expectTokenAndTypeStored() {
        store.saveDeviceToken("fid-value", DeviceTokenType.FID)

        store.getDeviceToken() shouldBeEqualTo "fid-value"
        store.getDeviceTokenType() shouldBeEqualTo DeviceTokenType.FID
    }

    @Test
    fun saveDeviceToken_givenNoType_expectPreviousTypeCleared() {
        store.saveDeviceToken("fid-value", DeviceTokenType.FID)

        store.saveDeviceToken("customer-token", null)

        store.getDeviceToken() shouldBeEqualTo "customer-token"
        store.getDeviceTokenType() shouldBeEqualTo null
    }

    @Test
    fun removeDeviceToken_expectTokenAndTypeRemoved() {
        store.saveDeviceToken("legacy-token", DeviceTokenType.TOKEN)

        store.removeDeviceToken()

        store.getDeviceToken() shouldBeEqualTo null
        store.getDeviceTokenType() shouldBeEqualTo null
    }
}
