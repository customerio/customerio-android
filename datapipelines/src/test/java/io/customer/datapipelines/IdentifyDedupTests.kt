package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.identifyEvents
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.DataPipelinesLogger
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * Tests for the per-session identify dedup behavior added in `CustomerIO.identifyImpl`.
 *
 * Because the public `identify(userId, traits: Map<String, Any?> = emptyMap())` API exposes a
 * single method with a defaulted parameter, `identify("alice")` and `identify("alice", emptyMap())`
 * compile to the same call site at the impl layer — there is no runtime way to tell them apart.
 * To keep the public API stable, both cases are deduped when the userId matches the last
 * successfully identified userId in this SDK session. Server-side merge of empty traits is a
 * no-op, so the behavioral collapse is immaterial in practice.
 *
 * See [IdentifyDedupRegressionTests] for the regression guard that the first identify still
 * publishes UserChangedEvent on the EventBus and re-registers the device token.
 */
class IdentifyDedupTests : JUnitTest() {
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    private val mockDataPipelinesLogger = mockk<DataPipelinesLogger>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<DataPipelinesLogger>(mockDataPipelinesLogger)
                    }
                }
            }
        )

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    @Test
    fun identify_givenFirstIdentifyNoTraits_callsAnalytics() {
        val givenIdentifier = String.random

        sdkInstance.identify(givenIdentifier)

        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 1
        outputReaderPlugin.identifyEvents.last().userId shouldBeEqualTo givenIdentifier
        analytics.userId() shouldBeEqualTo givenIdentifier
    }

    @Test
    fun identify_givenSecondIdentifySameUserIdNoTraits_doesNotCallAnalytics() {
        val givenIdentifier = String.random

        sdkInstance.identify(givenIdentifier)
        outputReaderPlugin.reset()

        sdkInstance.identify(givenIdentifier)

        // Second call is deduped; no new identify event is emitted to analytics.
        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 0
        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 0
        analytics.userId() shouldBeEqualTo givenIdentifier
    }

    @Test
    fun identify_givenSecondIdentifySameUserIdEmptyTraits_doesNotCallAnalytics() {
        val givenIdentifier = String.random

        sdkInstance.identify(givenIdentifier)
        outputReaderPlugin.reset()

        // Explicit empty map is indistinguishable from no traits at the impl layer because the
        // public API exposes a single method with a defaulted parameter. Both are deduped.
        sdkInstance.identify(givenIdentifier, emptyMap())

        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 0
        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 0
        analytics.userId() shouldBeEqualTo givenIdentifier
    }

    @Test
    fun identify_givenSecondIdentifySameUserIdWithTraits_callsAnalytics() {
        val givenIdentifier = String.random
        val givenTraits = mapOf("first_name" to "Dana", "ageInYears" to 30)

        sdkInstance.identify(givenIdentifier)
        outputReaderPlugin.reset()

        sdkInstance.identify(givenIdentifier, givenTraits)

        // Non-empty traits never dedup — the call must reach analytics so server-side
        // attributes are updated for the existing profile.
        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 1
        outputReaderPlugin.identifyEvents.last().userId shouldBeEqualTo givenIdentifier
    }

    @Test
    fun identify_givenDifferentUserIdNoTraits_callsAnalytics() {
        val firstIdentifier = String.random
        val secondIdentifier = String.random

        sdkInstance.identify(firstIdentifier)
        outputReaderPlugin.reset()

        sdkInstance.identify(secondIdentifier)

        // Different userId is a profile change — dedup must NOT fire.
        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 1
        outputReaderPlugin.identifyEvents.last().userId shouldBeEqualTo secondIdentifier
        analytics.userId() shouldBeEqualTo secondIdentifier
    }

    @Test
    fun identify_givenSameUserIdAfterClearIdentify_callsAnalytics() {
        val givenIdentifier = String.random

        sdkInstance.identify(givenIdentifier)
        sdkInstance.clearIdentify()
        outputReaderPlugin.reset()

        sdkInstance.identify(givenIdentifier)

        // clearIdentify() must reset the dedup marker so that re-identifying the same user
        // afterwards flows through to analytics again.
        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 1
        outputReaderPlugin.identifyEvents.last().userId shouldBeEqualTo givenIdentifier
        analytics.userId() shouldBeEqualTo givenIdentifier
    }
}
