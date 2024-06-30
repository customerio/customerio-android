package io.customer.commontest.core

import android.app.Application
import android.content.Context
import io.customer.commontest.config.DIGraphConfiguration
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.setupAndroidSDKComponent
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.overrideDependency
import io.customer.commontest.util.UnitTestLogger
import io.customer.sdk.core.util.Logger

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

    fun DIGraphConfiguration.registerAndroidSDKComponent() {
        setupAndroidSDKComponent(application = applicationMock)
    }
}
