package io.customer.sdk.core.util

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.util.DispatchersProviderStub
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

class SdkScopeProviderTest : JUnit5Test() {

    private val mockLogger: Logger = mockk(relaxed = true)
    private val scopeProvider = SdkScopeProvider(DispatchersProviderStub())

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<Logger>(mockLogger)
                    }
                }
            }
        )
    }

    @Test
    fun allScopes_givenCoroutineThrows_expectLoggedNotCrashed() = runTest {
        val scopes = listOf(
            scopeProvider.eventBusScope,
            scopeProvider.lifecycleListenerScope,
            scopeProvider.inAppLifecycleScope,
            scopeProvider.locationScope,
            scopeProvider.geofenceScope
        )

        scopes.forEach { scope ->
            scope.launch { throw IllegalStateException("uncaught SDK failure") }.join()
        }

        verify(exactly = scopes.size) { mockLogger.error(any(), any(), any<IllegalStateException>()) }
    }

    @Test
    fun scope_givenCoroutineThrows_expectSiblingCoroutinesUnaffected() = runTest {
        val scope = scopeProvider.geofenceScope
        var siblingRan = false

        scope.launch { throw IllegalStateException("uncaught SDK failure") }.join()
        scope.launch { siblingRan = true }.join()

        siblingRan.shouldBeTrue()
    }
}
