package io.customer.sdk.core.util

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.util.DispatchersProviderStub
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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

        // Message must carry the exception itself: custom log dispatchers only receive the string.
        verify(exactly = scopes.size) {
            mockLogger.error(
                match { it.contains("IllegalStateException") && it.contains("uncaught SDK failure") },
                any(),
                any<IllegalStateException>()
            )
        }
    }

    @Test
    fun scope_givenCoroutineThrows_expectSiblingCoroutinesUnaffected() = runTest {
        val scope = scopeProvider.geofenceScope
        var siblingRan = false

        scope.launch { throw IllegalStateException("uncaught SDK failure") }.join()
        scope.launch { siblingRan = true }.join()

        siblingRan.shouldBeTrue()
    }

    @Test
    fun cancellingOneScope_givenAnotherScope_expectOtherScopeUnaffected() = runTest {
        val oneShotScope = scopeProvider.geofenceScope
        val persistentScope = scopeProvider.eventBusScope

        oneShotScope.cancel()
        var persistentScopeRan = false
        persistentScope.launch { persistentScopeRan = true }.join()

        persistentScopeRan.shouldBeTrue()
    }

    @Test
    fun shutdown_givenActiveCoroutines_expectAllSdkWorkCancelled() = runTest {
        val jobs = listOf(
            scopeProvider.eventBusScope,
            scopeProvider.lifecycleListenerScope,
            scopeProvider.inAppLifecycleScope,
            scopeProvider.locationScope,
            scopeProvider.geofenceScope
        ).map { scope ->
            scope.launch { awaitCancellation() }
        }

        scopeProvider.shutdown()

        jobs.all { it.isCancelled }.shouldBeTrue()
    }
}
