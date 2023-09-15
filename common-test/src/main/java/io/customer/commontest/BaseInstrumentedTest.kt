package io.customer.commontest

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.sdk.CustomerIOConfig

/**
 * Base class for a instrumentation test class to subclass. If you want to create local unit tests, use [BaseLocalTest].
 * Meant to provide convenience to test classes with properties and functions tests may use.
 *
 * This class should avoid overriding dependencies as much as possible. The more *real* (not mocked) dependencies executed in these
 * integration test functions, the closer the tests are to the production environment.
 */
abstract class BaseInstrumentedTest : BaseTest() {

    override val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    override val application: Application
        get() = ApplicationProvider.getApplicationContext()

    // Call this function again in your integration test function if you need to modify the SDK configuration
    override fun setup(cioConfig: CustomerIOConfig) {
        super.setup(cioConfig)

        di.fileStorage.deleteAllSdkFiles()
        di.sitePreferenceRepository.clearAll()
    }
}
