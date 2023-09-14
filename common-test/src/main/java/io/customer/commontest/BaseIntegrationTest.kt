package io.customer.commontest

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.extensions.random

/**
 * Base class for a integration test class to subclass. If you want to create unit tests, use [BaseTest].
 * Meant to provide convenience to test classes with properties and functions tests may use.
 *
 * This class should avoid overriding dependencies as much as possible. The more *real* (not mocked) dependencies executed in these
 * integration test functions, the closer the tests are to the production environment.
 */
abstract class BaseIntegrationTest : BaseTest() {

    override val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    override val application: Application
        get() = ApplicationProvider.getApplicationContext()

    // Call this function again in your integration test function if you need to modify the SDK configuration
    override fun setup(cioConfig: CustomerIOConfig) {
        super.setup(cioConfig)

        di.fileStorage.deleteAllSdkFiles()
        di.sitePreferenceRepository.clearAll()

        // Initialize the SDK but with an injected DI graph.
        // Test setup should use the same SDK initialization that customers do to make test as close to production environment as possible.
        CustomerIO.Builder(
            siteId = siteId,
            apiKey = String.random,
            region = Region.US,
            appContext = application
        ).apply {
            overrideDiGraph = di
        }.build()
    }
}
