package io.customer.datapipelines.migration

import io.customer.base.extenstions.getUnixTimestamp
import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.random
import io.customer.datapipelines.handoff.DataPipelinesDiagnostics
import io.customer.datapipelines.testutils.core.IntegrationTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import io.customer.tracking.migration.request.MigrationTask
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * With the hand-off log on, the log tail reads what an enrichment sets by applying it to a throwaway
 * event. A migrated event's enrichment must therefore not write log lines of its own, or each
 * migrated event is logged once for the probe as well.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingMigrationForwardLogTest : IntegrationTest() {

    private class DebugLines : Logger {
        val lines = mutableListOf<String>()
        override var logLevel: CioLogLevel = CioLogLevel.DEBUG
        override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit
        override fun info(message: String, tag: String?) = Unit
        override fun debug(message: String, tag: String?) {
            lines += message
        }
        override fun error(message: String, tag: String?, throwable: Throwable?) = Unit
    }

    private val debugLines = DebugLines()

    override fun setup(testConfig: TestConfig) {
        // Keep setup empty: each test initializes the SDK itself, with the hand-off log on
    }

    override fun teardown() {
        DataPipelinesDiagnostics.setEnabledForTesting(null)
        super.teardown()
    }

    @Test
    fun migrate_givenHandOffLogOn_expectForwardingLineLoggedOnce() = runTest(testDispatcher) {
        DataPipelinesDiagnostics.setEnabledForTesting(true)
        super.setup(
            testConfiguration {
                diGraph {
                    sdk {
                        overrideDependency<Logger>(debugLines)
                    }
                }
                sdkConfig {
                    logLevel(CioLogLevel.DEBUG)
                    migrationSiteId(TestConstants.Keys.SITE_ID)
                    autoTrackDeviceAttributes(false)
                }
            }
        )
        sdkInstance.identify(String.random)
        debugLines.lines.clear()

        val migrationProcessor = sdkInstance.migrationProcessor ?: error("migration processor not initialized")
        migrationProcessor.processTask(
            MigrationTask.TrackEvent(
                timestamp = Date().getUnixTimestamp(),
                identifier = String.random,
                event = String.random,
                type = "event",
                properties = JSONObject()
            )
        )

        debugLines.lines.count { it.startsWith("forwarding migrated event") } shouldBeEqualTo 1
    }
}
