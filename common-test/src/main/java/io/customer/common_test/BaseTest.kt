package io.customer.common_test

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.di.CustomerIOComponent
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
        get() = CustomerIOConfig(siteId, "xyz", Region.EU, 100, null, true, 30)

    protected val di: CustomerIOComponent
        get() = CustomerIOComponent.getInstance(siteId).apply {
            sdkConfig = cioConfig
            context = this@BaseTest.context
        }

    // convenient HttpException for test functions to test a failed HTTP request
    protected val http500Error: HttpException
        get() = HttpException(Response.error<String>(500, "{}".toResponseBody()))

    @Before
    open fun setup() {
        di.fileStorage.deleteAllSdkFiles()
        CustomerIOComponent.reset()
    }
}
