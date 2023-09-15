package io.customer.sdk.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceStoreTest : BaseTest() {

    @Test
    fun verifyBuildAttributesInDevice() {
        deviceStore.deviceBrand shouldBeEqualTo "Google"
        deviceStore.deviceModel shouldBeEqualTo "Pixel 6"
        deviceStore.deviceManufacturer shouldBeEqualTo "Google"
        deviceStore.deviceOSVersion shouldBeEqualTo 30
        deviceStore.deviceLocale shouldBeEqualTo "en-US"
    }

    @Test
    fun verifyHostApplicationAttributesInDevice() {
        deviceStore.customerAppName shouldBeEqualTo "User App"
        deviceStore.customerAppVersion shouldBeEqualTo "1.0"
        deviceStore.customerPackageName shouldBeEqualTo "io.customer.sdk"
    }

    @Test
    fun verifyUseragentIsCreatedCorrectly() {
        deviceStore.buildUserAgent() shouldBeEqualTo
            "Customer.io AndroidTest Client/1.0.0-alpha.6 (Google Pixel 6; 30) io.customer.sdk/1.0"
    }

    @Test
    fun verifyDeviceAttributesAreCreatedCorrectly() {
        val resultDeviceAttributes = deviceStore.buildDeviceAttributes()
        val expectedDeviceAttributes = mapOf(
            "device_os" to 30,
            "device_model" to "Pixel 6",
            "device_manufacturer" to "Google",
            "app_version" to "1.0",
            "cio_sdk_version" to "1.0.0-alpha.6",
            "device_locale" to "en-US",
            "push_enabled" to true
        )

        resultDeviceAttributes shouldBeEqualTo expectedDeviceAttributes
    }
}
