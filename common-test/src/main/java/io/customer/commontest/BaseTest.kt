package io.customer.commontest

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.CustomerIOShared
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.Client
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.di.CustomerIOSharedComponent
import io.customer.sdk.di.CustomerIOStaticComponent
import io.customer.sdk.module.CustomerIOModule
import io.customer.sdk.util.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import retrofit2.HttpException
import retrofit2.Response

/**
 * Base class for a test class to subclass. Do not inherit this class directly.
 * - If you want to create unit tests, use [BaseUnitTest].
 * - If you want to create integration tests, use [BaseIntegrationTest].
 * Meant to provide convenience to test classes with properties and functions tests may use.
 */
abstract class BaseTest {

    protected val siteId: String
        get() = "test-site-id"

    protected abstract val context: Context
    protected abstract val application: Application

    protected lateinit var cioConfig: CustomerIOConfig

    protected lateinit var deviceStore: DeviceStore
    protected lateinit var dispatchersProviderStub: DispatchersProviderStub

    protected lateinit var staticDIComponent: CustomerIOStaticComponent
    protected lateinit var sharedDIComponent: CustomerIOSharedComponent
    protected lateinit var di: CustomerIOComponent
    protected val jsonAdapter: JsonAdapter
        get() = di.jsonAdapter

    protected lateinit var mockWebServer: MockWebServer
    protected lateinit var dateUtilStub: DateUtilStub

    // convenient method for test functions to test a failed HTTP request
    protected fun getHttpError(code: Int, body: String = "{}"): HttpException {
        return HttpException(Response.error<String>(code, body.toResponseBody()))
    }

    protected fun createConfig(
        client: Client = Client.fromRawValue(source = "AndroidTest", sdkVersion = "1.0.0-alpha.6"),
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
        modules: Map<String, CustomerIOModule<*>> = emptyMap()
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
        modules = modules
    )

    // override in test class to override SDK config for all test functions in the class.
    protected open fun setupConfig() = createConfig()

    // default @Before for tests using a default SDK config set
    @Before
    open fun setup() {
        setup(cioConfig = setupConfig())
    }

    // Call inside of test function to override the SDK config for just 1 test function.
    @SuppressLint("VisibleForTests")
    open fun setup(cioConfig: CustomerIOConfig) {
        this.cioConfig = cioConfig

        // Initialize the mock web server before constructing DI graph as dependencies may require information such as hostname.
        mockWebServer = MockWebServer().apply {
            start()
        }
        cioConfig.trackingApiUrl = mockWebServer.url("/").toString()
        if (!cioConfig.trackingApiUrl!!.contains("localhost")) {
            throw RuntimeException("server didn't' start ${cioConfig.trackingApiUrl}")
        }

        // Create any stubs or mocks here
        dateUtilStub = DateUtilStub()
        deviceStore = DeviceStoreStub().getDeviceStore(cioConfig)
        dispatchersProviderStub = DispatchersProviderStub()

        // Create DI graph instances
        staticDIComponent = CustomerIOStaticComponent()
        sharedDIComponent = CustomerIOSharedComponent(context)
        // Initialize the SDK with injected DI graphs
        CustomerIOShared.createInstance(staticDIComponent).apply {
            diSharedGraph = sharedDIComponent
        }
        di = CustomerIOComponent(
            staticComponent = staticDIComponent,
            sdkConfig = cioConfig,
            context = application
        )
    }

    @After
    open fun teardown() {
        mockWebServer.shutdown()
        staticDIComponent.reset()
        sharedDIComponent.reset()
        di.reset()
    }
}
