package io.customer.sdk.data.store

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test

internal class DeviceStoreTest {

    lateinit var deviceStore: DeviceStore

    @Before
    fun setup() {
        deviceStore = DeviceStoreImp(
            buildStore = object : BuildStore {
                override val deviceBrand: String
                    get() = "Google"
                override val deviceModel: String
                    get() = "Pixel 6"
                override val deviceManufacturer: String
                    get() = "Google"
                override val deviceOSVersion: Int
                    get() = 30
            },
            applicationStore = object : ApplicationStore {
                override val customerAppName: String
                    get() = "User App"
                override val customerAppVersion: String
                    get() = "1.0"
            },
            version = "1.0.0-alpha.6"
        )
    }

    @Test
    fun verifyBuildAttributesInDevice() {
        deviceStore.deviceBrand shouldBeEqualTo "Google"
        deviceStore.deviceModel shouldBeEqualTo "Pixel 6"
        deviceStore.deviceManufacturer shouldBeEqualTo "Google"
        deviceStore.deviceOSVersion shouldBeEqualTo 30
    }

    @Test
    fun verifyHostApplicationAttributesInDevice() {
        deviceStore.customerAppName shouldBeEqualTo "User App"
        deviceStore.customerAppVersion shouldBeEqualTo "1.0"
    }

    @Test
    fun verifyUseragentIsCreatedCorrectly() {
        deviceStore.buildUserAgent() shouldBeEqualTo
            "Customer.io Android Client/1.0.0-alpha.6 (Google Pixel 6; 30) User App/1.0"
    }
}
