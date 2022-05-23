package io.customer.common_test

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.util.CioLogLevel
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.JsonAdapter
import kotlinx.coroutines.test.TestCoroutineDispatcher
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
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

    protected lateinit var cioConfig: CustomerIOConfig

    protected val deviceStore: DeviceStore = DeviceStoreStub().deviceStore

    // when you need a CoroutineDispatcher in a test function, use this as it runs your tests synchronous.
    protected val testDispatcher = TestCoroutineDispatcher()

    protected lateinit var di: CustomerIOComponent
    protected val jsonAdapter: JsonAdapter
        get() = di.jsonAdapter

    // convenient HttpException for test functions to test a failed HTTP request
    protected val http500Error: HttpException
        get() = HttpException(Response.error<String>(500, "{}".toResponseBody()))

    protected lateinit var mockWebServer: MockWebServer
    protected lateinit var dateUtilStub: DateUtilStub

    @Before
    open fun setup() {
        cioConfig = CustomerIOConfig(siteId, "xyz", Region.EU, 100, null, true, true, 10, 30.0, CioLogLevel.DEBUG, null)

        // Initialize the mock web server before constructing DI graph as dependencies may require information such as hostname.
        mockWebServer = MockWebServer().apply {
            start()
        }
        cioConfig.trackingApiUrl = mockWebServer.url("/").toString()

        di = CustomerIOComponent(
            sdkConfig = cioConfig,
            context = application
        )
        di.fileStorage.deleteAllSdkFiles()
        di.sharedPreferenceRepository.clearAll()

        dateUtilStub = DateUtilStub().also {
            di.overrideDependency(DateUtil::class.java, it)
        }
    }

    @After
    open fun teardown() {
        mockWebServer.shutdown()
    }
}
