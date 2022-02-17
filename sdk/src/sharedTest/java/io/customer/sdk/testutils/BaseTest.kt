package io.customer.sdk.testutils

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.di.CustomerIOComponent
import org.junit.Before
import org.mockito.MockitoAnnotations

/**
 * Base class for test classes to subclass. Meant to provide convenience to test classes with properties and functions tests may use.
 */
abstract class BaseTest {

    open fun provideTestClass(): Any = this

    val siteId: String
        get() = "test-site-id"

    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    val cioConfig: CustomerIOConfig
        get() = CustomerIOConfig(siteId, "xyz", Region.EU, 100, null, true, 30)

    internal val di: CustomerIOComponent
        get() = CustomerIOComponent(cioConfig, context)

    @Before
    open fun setup() {
        // if this doesn't work, you can use the mockito test rule: MockitoJUnit.rule().
        MockitoAnnotations.initMocks(provideTestClass())

        di.fileStorage.deleteAllSdkFiles()
    }
}
