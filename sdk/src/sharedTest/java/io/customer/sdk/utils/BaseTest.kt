package io.customer.sdk.utils

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.di.CustomerIOComponent
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.mockito.MockitoAnnotations
import retrofit2.HttpException
import retrofit2.Response

/**
 * Base class for test classes to subclass. Meant to provide convenience to test classes with properties and functions tests may use.
 */
abstract class BaseTest {

    open fun provideTestClass(): Any = this

    protected val siteId: String
        get() = "test-site-id"

    protected val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    protected val cioConfig: CustomerIOConfig
        get() = CustomerIOConfig(siteId, "xyz", Region.EU, 100, null, true, 30)

    internal val di: CustomerIOComponent
        get() = CustomerIOComponent(cioConfig, context)

    // convenient HttpException for test functions to test a failed HTTP request
    protected val http500Error: HttpException
        get() = HttpException(Response.error<String>(500, "{}".toResponseBody()))

    @Before
    open fun setup() {
        // if this doesn't work, you can use the mockito test rule: MockitoJUnit.rule().
        MockitoAnnotations.initMocks(provideTestClass())

        di.fileStorage.deleteAllSdkFiles()
    }
}
