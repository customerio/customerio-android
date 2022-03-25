package io.customer.sdk.store

import io.customer.sdk.BaseTest
import org.amshove.kluent.`should be equal to`
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

internal class DeviceStoreTest : BaseTest() {

    @Test
    fun `verify build attributes in device`() {
        deviceStore.deviceBrand `should be equal to` "Google"
        deviceStore.deviceModel `should be equal to` "Pixel 6"
        deviceStore.deviceManufacturer `should be equal to` "Google"
        deviceStore.deviceOSVersion `should be equal to` 30
        deviceStore.deviceLocale `should be equal to` "en-US"
    }

    @Test
    fun `verify host application attributes in device`() {
        deviceStore.customerAppName `should be equal to` "User App"
        deviceStore.customerAppVersion `should be equal to` "1.0"
        deviceStore.customerPackageName shouldBeEqualTo "io.customer.sdk"
    }

    @Test
    fun `verify user-agent is created correctly`() {
        deviceStore.buildUserAgent() `should be equal to`
            "Customer.io Android Client/1.0.0-alpha.6 (Google Pixel 6; 30) io.customer.sdk/1.0"
    }

    @Test
    fun `verify device attributes are created correctly`() {
        val resultDeviceAttributes = deviceStore.buildDeviceAttributes()
        val expectedDeviceAttributes = mapOf(
            "device_os" to 30,
            "device_model" to "Pixel 6",
            "app_version" to "1.0",
            "cio_sdk_version" to "1.0.0-alpha.6",
            "device_locale" to "en-US",
            "push_enabled" to true
        )

        resultDeviceAttributes shouldBeEqualTo expectedDeviceAttributes
    }
}
