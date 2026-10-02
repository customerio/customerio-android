package io.customer.sdk.data.store

import android.content.Context
import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.data.model.DeviceTokenType
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlobalPreferenceStoreTest : RobolectricTest() {

    private val store = GlobalPreferenceStoreImpl(contextMock)

    private fun writeRaw(vararg values: Pair<String, String>) {
        contextMock.getSharedPreferences(store.prefsName, Context.MODE_PRIVATE).edit().apply {
            values.forEach { (key, value) -> putString(key, value) }
        }.commit()
    }

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
    fun getDeviceTokenType_givenTokenStoredBySdkWithoutType_expectNullType() {
        writeRaw("device_token" to "legacy-token")

        store.getDeviceToken() shouldBeEqualTo "legacy-token"
        store.getDeviceTokenType() shouldBeEqualTo null
    }

    @Test
    fun getDeviceTokenType_givenUnknownStoredValue_expectNullType() {
        writeRaw("device_token" to "some-token", "device_token_type" to "UNKNOWN")

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
