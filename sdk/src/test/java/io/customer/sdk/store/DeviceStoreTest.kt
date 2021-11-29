package io.customer.sdk.store

import io.customer.sdk.data.store.ApplicationStore
import io.customer.sdk.data.store.BuildStore
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.DeviceStoreImp
import org.amshove.kluent.`should be equal to`
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
    fun `verify build attributes in device`() {
        deviceStore.deviceBrand `should be equal to` "Google"
        deviceStore.deviceModel `should be equal to` "Pixel 6"
        deviceStore.deviceManufacturer `should be equal to` "Google"
        deviceStore.deviceOSVersion `should be equal to` 30
    }

    @Test
    fun `verify host application attributes in device`() {
        deviceStore.customerAppName `should be equal to` "User App"
        deviceStore.customerAppVersion `should be equal to` "1.0"
    }

    @Test
    fun `verify user-agent is created correctly`() {
        deviceStore.buildUserAgent() `should be equal to`
            "Customer.io Android Client/1.0.0-alpha.6 (Google Pixel 6; 30) User App/1.0"
    }
}
