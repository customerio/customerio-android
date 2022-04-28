package io.customer.common_test

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.util.JsonAdapter
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import retrofit2.HttpException
import retrofit2.Response

/**
 * Base class for test classes to subclass. Meant to provide convenience to test classes with properties and functions tests may use.
 */
abstract class BaseTest {

    protected val siteId: String
        get() = "test-site-id"

    protected val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    protected val application: Application
        get() = ApplicationProvider.getApplicationContext()

    protected val cioConfig: CustomerIOConfig
        get() = CustomerIOConfig(siteId, "xyz", Region.EU, 100, null, true, true, 10, 30.0)

    protected val deviceStore: DeviceStore = DeviceStoreStub().deviceStore

    protected lateinit var di: CustomerIOComponent
    protected val jsonAdapter: JsonAdapter
        get() = di.jsonAdapter

    // convenient HttpException for test functions to test a failed HTTP request
    protected val http500Error: HttpException
        get() = HttpException(Response.error<String>(500, "{}".toResponseBody()))

    @Before
    open fun setup() {
        di = CustomerIOComponent(
            sdkConfig = cioConfig,
            context = this@BaseTest.context
        )
        di.fileStorage.deleteAllSdkFiles()

        di.sharedPreferenceRepository.clearAll()
    }
}
