package io.customer.sdk

import io.customer.sdk.data.moshi.CustomerIOParser
import io.customer.sdk.data.moshi.CustomerIOParserImpl
import io.customer.sdk.data.store.ApplicationStore
import io.customer.sdk.data.store.BuildStore
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.DeviceStoreImp
import io.customer.sdk.repository.AttributesRepository
import io.customer.sdk.repository.MoshiAttributesRepositoryImp
import org.junit.Before
import java.util.*

internal open class BaseTest {

    lateinit var deviceStore: DeviceStore
    private val parser: CustomerIOParser = CustomerIOParserImpl()
    lateinit var attributesRepository: AttributesRepository

    @Before
    fun baseSetup() {
        attributesRepository = MoshiAttributesRepositoryImp(parser)

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
                override val deviceLocale: String
                    get() = Locale.US.language
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
}
