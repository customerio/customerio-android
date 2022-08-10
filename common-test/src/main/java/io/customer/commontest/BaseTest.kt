package io.customer.commontest

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.Client
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.module.CustomerIOModuleConfig
import io.customer.sdk.util.*
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

    protected lateinit var deviceStore: DeviceStore
    protected lateinit var dispatchersProviderStub: DispatchersProviderStub

    protected lateinit var di: CustomerIOComponent
    protected val jsonAdapter: JsonAdapter
        get() = di.jsonAdapter

    // convenient HttpException for test functions to test a failed HTTP request
    protected val http500Error: HttpException
        get() = HttpException(Response.error<String>(500, "{}".toResponseBody()))

    protected lateinit var mockWebServer: MockWebServer
    protected lateinit var dateUtilStub: DateUtilStub

    protected fun createConfig(
        client: Client = Client.Android,
        siteId: String = this.siteId,
        apiKey: String = "xyz",
        region: Region = Region.EU,
        timeout: Long = 100,
        autoTrackScreenViews: Boolean = true,
        autoTrackDeviceAttributes: Boolean = true,
        backgroundQueueMinNumberOfTasks: Int = 10,
        backgroundQueueSecondsDelay: Double = 30.0,
        backgroundQueueTaskExpiredSeconds: Double = Seconds.fromDays(3).value,
        logLevel: CioLogLevel = CioLogLevel.DEBUG,
        trackingApiUrl: String? = null,
        targetSdkVersion: Int = android.os.Build.VERSION_CODES.R,
        configurations: Map<String, CustomerIOModuleConfig> = emptyMap()
    ) = CustomerIOConfig(
        client = client,
        siteId = siteId,
        apiKey = apiKey,
        region = region,
        timeout = timeout,
        autoTrackScreenViews = autoTrackScreenViews,
        autoTrackDeviceAttributes = autoTrackDeviceAttributes,
        backgroundQueueMinNumberOfTasks = backgroundQueueMinNumberOfTasks,
        backgroundQueueSecondsDelay = backgroundQueueSecondsDelay,
        backgroundQueueTaskExpiredSeconds = backgroundQueueTaskExpiredSeconds,
        logLevel = logLevel,
        trackingApiUrl = trackingApiUrl,
        targetSdkVersion = targetSdkVersion,
        configurations = configurations
    )

    protected open fun setupConfig() = createConfig()

    @Before
    open fun setup() {
        cioConfig = setupConfig()

        // Initialize the mock web server before constructing DI graph as dependencies may require information such as hostname.
        mockWebServer = MockWebServer().apply {
            start()
        }
        cioConfig.trackingApiUrl = mockWebServer.url("/").toString()
        if (!cioConfig.trackingApiUrl!!.contains("localhost")) {
            throw RuntimeException("server didnt' start ${cioConfig.trackingApiUrl}")
        }

        di = CustomerIOComponent(
            sdkConfig = cioConfig,
            context = application
        )
        di.fileStorage.deleteAllSdkFiles()
        di.sharedPreferenceRepository.clearAll()

        dateUtilStub = DateUtilStub().also {
            di.overrideDependency(DateUtil::class.java, it)
        }
        deviceStore = DeviceStoreStub().getDeviceStore(cioConfig)
        dispatchersProviderStub = DispatchersProviderStub().also {
            di.overrideDependency(DispatchersProvider::class.java, it)
        }
    }

    @After
    open fun teardown() {
        mockWebServer.shutdown()
        di.reset()
    }
}
