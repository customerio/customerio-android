package io.customer.sdk

import io.customer.sdk.data.moshi.CustomerIOParser
import io.customer.sdk.data.moshi.CustomerIOParserImpl
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.repository.AttributesRepository
import io.customer.sdk.repository.MoshiAttributesRepositoryImp
import io.customer.sdk.utils.DeviceStoreStub
import org.junit.Before

internal open class BaseTest {

    val deviceStore: DeviceStore = DeviceStoreStub().deviceStore
    private val parser: CustomerIOParser = CustomerIOParserImpl()
    lateinit var attributesRepository: AttributesRepository

    @Before
    fun baseSetup() {
        attributesRepository = MoshiAttributesRepositoryImp(parser)
    }
}
