package io.customer.commontest

import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.store.ApplicationStore
import io.customer.sdk.data.store.BuildStore
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.DeviceStoreImp
import java.util.*

class DeviceStoreStub {

    fun getDeviceStore(cioConfig: CustomerIOConfig): DeviceStore {
        return DeviceStoreImp(
            sdkConfig = cioConfig,
            buildStore = object : BuildStore {
                override val deviceBrand: String
                    get() = "Google"
                override val deviceModel: String
                    get() = "Pixel 6"
                override val deviceManufacturer: String
                    get() = "Google"
                override val deviceOSVersion: Int
                    get() = 30
                override val deviceLocale: String
                    get() = Locale.US.toLanguageTag()
            },
            applicationStore = object : ApplicationStore {
                override val customerAppName: String
                    get() = "User App"
                override val customerAppVersion: String
                    get() = "1.0"
                override val customerPackageName: String
                    get() = "io.customer.sdk"
                override val isPushEnabled: Boolean
                    get() = true
            },
            version = cioConfig.client.sdkVersion
        )
    }
}
