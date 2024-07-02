package io.customer.commontest.core

import android.app.Application
import android.content.Context
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.util.UnitTestLogger
import io.customer.sdk.core.util.Logger

/**
 * Unit test base class for all unit tests in the project.
 * This class is responsible for basic setup and teardown of unit test environment, like
 * overriding logger dependency with test logger so logs can be captured in tests without using Android Logcat.
 *
 * Unit test classes should extend [JUnit5Test] instead of this class.
 * Integration test classes should extend [RobolectricTest] instead of this class.
 */
abstract class UnitTest : BaseTest() {
    abstract val applicationMock: Application
    abstract val contextMock: Context

    private val defaultTestConfiguration: TestConfig = testConfigurationDefault {
        diGraph {
            sdk {
                // Override logger dependency with test logger so logs can be captured in tests
                // This also makes logger independent of Android Logcat
                overrideDependency<Logger>(UnitTestLogger())
            }
        }
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(defaultTestConfiguration + testConfig)
    }
}
