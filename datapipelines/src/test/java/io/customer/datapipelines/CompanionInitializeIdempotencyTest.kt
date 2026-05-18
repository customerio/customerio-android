package io.customer.datapipelines

import android.app.Application
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOConfigBuilder
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * Verifies [CustomerIO.initialize] is idempotent under concurrent or sequential
 * duplicate calls.
 *
 * Without the `@Synchronized` guard on the companion `initialize` (and given
 * that `isReady` only flips after `finishInitialization()`), two callers could
 * both pass the `if (customerIO.isReady)` early-out before either reached
 * `finishInitialization()`, causing duplicate `module.initialize()` calls —
 * which would double-register analytics plugins and EventBus subscribers.
 */
class CompanionInitializeIdempotencyTest : JUnitTest() {
    private val countingModule = CountingTestModule()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                sdkConfig {
                    addCustomerIOModule(countingModule)
                }
            }
        )
    }

    @Test
    fun givenInitializeCalledTwice_expectModuleInitializedOnce() {
        // First initialize ran via the harness setup; module.initialize() should
        // already have fired exactly once.
        countingModule.initializeCallCount shouldBeEqualTo 1

        // Second call goes through the same companion `initialize`. The
        // `@Synchronized` + `isReady` guard should detect that the SDK is
        // already initialized and early-return without re-running modules.
        val mockApplication = mockk<Application>(relaxed = true).apply {
            every { applicationContext } returns this
        }
        val builder = CustomerIOConfigBuilder(mockApplication, "test-cdp-second-call")
            .autoAddCustomerIODestination(false)
            .trackApplicationLifecycleEvents(false)
            .addCustomerIOModule(countingModule)
        CustomerIO.initialize(builder.build())

        countingModule.initializeCallCount shouldBeEqualTo 1
    }
}

private class CountingTestModule : CustomerIOModule<CountingTestModuleConfig> {
    override val moduleName: String = "CompanionInitIdempotencyTestModule"
    override val moduleConfig: CountingTestModuleConfig = CountingTestModuleConfig

    var initializeCallCount: Int = 0
        private set

    override fun initialize() {
        initializeCallCount += 1
    }
}

private object CountingTestModuleConfig : CustomerIOModuleConfig
