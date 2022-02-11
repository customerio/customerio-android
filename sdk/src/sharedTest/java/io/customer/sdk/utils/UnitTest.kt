package io.customer.sdk.utils

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.di.CustomerIOComponent

/**
 * Base class for test classes to subclass. Meant to provide convenience to test classes with properties and functions tests may use.
 */
abstract class UnitTest {

    protected val siteId: String
        get() = "test-site-id"

    protected val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    protected val cioConfig: CustomerIOConfig
        get() = CustomerIOConfig("", "", Region.EU, 100, null, true)

    internal val di: CustomerIOComponent
        get() = CustomerIOComponent(cioConfig, context)

}
