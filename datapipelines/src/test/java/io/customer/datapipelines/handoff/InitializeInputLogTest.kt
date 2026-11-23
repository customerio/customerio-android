package io.customer.datapipelines.handoff

import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * The `api.initialize` line is how a replay tells calls made before init from calls made after.
 * A published SDK starts with its logger at the default level, so the line is only written if
 * initialize sets the configured level before it logs.
 */
class InitializeInputLogTest : JUnitTest() {

    /** A logger that starts at the default level of a published SDK and drops debug lines below `DEBUG`. */
    private class ReleaseLikeLogger : Logger {
        val debugLines = mutableListOf<String>()
        override var logLevel: CioLogLevel = CioLogLevel.ERROR
        override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit
        override fun info(message: String, tag: String?) = Unit
        override fun debug(message: String, tag: String?) {
            if (logLevel == CioLogLevel.DEBUG) debugLines += message
        }
        override fun error(message: String, tag: String?, throwable: Throwable?) = Unit
    }

    // Not named `logger`: inside the `sdk { }` block that name is the component's own logger
    private val releaseLikeLogger = ReleaseLikeLogger()

    override fun setup(testConfig: TestConfig) {
        DataPipelinesDiagnostics.setEnabledForTesting(true)
        super.setup(
            testConfiguration {
                diGraph {
                    sdk {
                        overrideDependency<Logger>(releaseLikeLogger)
                    }
                }
                sdkConfig {
                    logLevel(CioLogLevel.DEBUG)
                }
            }
        )
    }

    override fun teardown() {
        DataPipelinesDiagnostics.setEnabledForTesting(null)
        super.teardown()
    }

    @Test
    fun initialize_givenDebugLevelAndDiagnosticsOn_expectInitializeInputLoggedFirst() {
        val inputLines = releaseLikeLogger.debugLines.filter { it.contains("io=in") }

        inputLines.firstOrNull()?.contains("ev=api.initialize") shouldBeEqualTo true
    }
}
