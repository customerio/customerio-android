package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.utilities.putInContextUnderKey
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.flushCoroutines
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.extensions.getStringAtPath
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.DataPipelinesLogger
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

/**
 * Tests [ContextPlugin] behavior using [StandardTestDispatcher] to simulate realistic coroutine
 * scheduling and timing.
 */
class ContextPluginBehaviorTest : JUnitTest(dispatcher = StandardTestDispatcher()) {
    private val testScope get() = delegate.testScope

    private lateinit var deviceStore: DeviceStore
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                diGraph {
                    sdk {
                        overrideDependency<DataPipelinesLogger>(mockk(relaxed = true))
                        overrideDependency<DeviceStore>(mockk(relaxed = true))
                        overrideDependency<GlobalPreferenceStore>(mockk(relaxed = true))
                    }
                }
            }
        )

        val androidSDKComponent = SDKComponent.android()
        deviceStore = androidSDKComponent.deviceStore
        every { deviceStore.buildUserAgent() } returns "test-user-agent"

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)

        // Run all pending coroutines to ensure analytics is initialized and ready to process events
        @Suppress("OPT_IN_USAGE")
        testScope.runCurrent()
    }

    /**
     * Verifies that the plugin correctly adds the expected device token to the event context
     * when the token is accessed from a different thread, including within coroutine dispatchers.
     * This test should fail intermittently if token value is not correctly synchronized across threads.
     */
    @Test
    fun execute_whenDeviceTokenIsSetFromAnotherThread_thenAddsCorrectTokenToEvent() = runTest {
        // Define test parameters for easier configuration
        val readerExecutionTimeMillis = 5000
        val writerCutoffTimeMillis = readerExecutionTimeMillis - 200 // ensure writer ends before reader execution
        val minThreadWaitTime = 50
        val maxThreadWaitTime = 100
        val tokenPrefix = "test-token-"
        val currentNanoTime = { System.nanoTime() }
        // Setup context plugin with a custom processor to track execution time
        val contextPluginProcessor = object : ContextPluginEventProcessor {
            val defaultProcessor = DefaultContextPluginEventProcessor()
            override fun execute(event: BaseEvent, deviceStore: DeviceStore, deviceTokenProvider: () -> String?): BaseEvent {
                // Add execution time to context for verification later
                event.putInContextUnderKey("test", "executionStartTime", currentNanoTime())
                val result = defaultProcessor.execute(event, deviceStore, deviceTokenProvider)
                event.putInContextUnderKey("test", "executionEndTime", currentNanoTime())
                return result
            }
        }
        val contextPlugin = ContextPlugin(deviceStore, contextPluginProcessor)
        analytics.add(contextPlugin)
        // Set initial value for test
        val writerLog = mutableMapOf<Long, String>() // (timestamp, read)
        // Set initial device token to skip unnecessary null checks and ensure value is fetched for initial events
        writerLog[currentNanoTime()] = ""
        // Prepare for concurrent execution
        val executor = Executors.newFixedThreadPool(2)
        val testStartTimeMs = currentNanoTime().nanosToMillis()

        // Writer thread: writes tokens at random intervals
        val writerThread = executor.submit {
            var counter = 1
            while (true) {
                val nowMs = currentNanoTime().nanosToMillis()
                if (nowMs - testStartTimeMs >= writerCutoffTimeMillis) break

                val newToken = "${tokenPrefix}${counter++}"
                waitUntil(nowMs + Random.nextInt(minThreadWaitTime, maxThreadWaitTime))

                sdkInstance.registerDeviceToken(newToken).flushCoroutines(testScope)
                writerLog[currentNanoTime()] = newToken
            }
        }

        // Reader thread: executes events with the current device token at random intervals
        val readerThread = executor.submit {
            var counter = 1
            // Ensure writer has started
            Thread.sleep(maxThreadWaitTime.toLong())
            while (true) {
                val nowMs = currentNanoTime().nanosToMillis()
                if (nowMs - testStartTimeMs >= readerExecutionTimeMillis) break

                waitUntil(nowMs + Random.nextInt(minThreadWaitTime, maxThreadWaitTime))
                // Track an event with so that the context is updated with the current device token
                sdkInstance.track(name = "test-event-${counter++}").flushCoroutines(testScope)
                // Yield to allow other thread to run
                Thread.yield()
            }
        }

        // Wait for both threads to finish
        writerThread.get(readerExecutionTimeMillis + 500L, TimeUnit.MILLISECONDS)
        readerThread.get(readerExecutionTimeMillis + 500L, TimeUnit.MILLISECONDS)
        executor.shutdown()

        // For each event executed by SDK, verify writer token that was active during the event's execution
        val sortedWrites = writerLog.entries.sortedBy { it.key }
        val mismatches = outputReaderPlugin.trackEvents.mapNotNull { event ->
            val executionStartTime = event.context.getStringAtPath("test.executionStartTime")?.toLong().shouldNotBeNull()
            val executionEndTime = event.context.getStringAtPath("test.executionEndTime")?.toLong().shouldNotBeNull()
            val actualToken = event.context.deviceToken

            // Find the index of the latest write logged before the event finished.
            // Note: registerDeviceToken sets the @Volatile field BEFORE writerLog
            // records the timestamp, so on slow CI the actual token may be several
            // writes ahead of the latest log entry. We allow a window of up to 3
            // entries beyond the latest logged write to account for this gap.
            val latestBeforeIndex = sortedWrites.indexOfLast { it.key <= executionEndTime }
            val windowStart = latestBeforeIndex.coerceAtLeast(0)
            val windowEnd = (latestBeforeIndex + 3).coerceAtMost(sortedWrites.size - 1)
            val validTokens = (windowStart..windowEnd).map { sortedWrites[it].value }.toSet()

            // If the actual token is not in valid tokens, it's a mismatch
            if (actualToken !in validTokens) {
                return@mapNotNull Triple("$executionStartTime..$executionEndTime", actualToken, validTokens.joinToString(" or "))
            }
            return@mapNotNull null
        }

        assertEquals(
            expected = 0,
            actual = mismatches.size,
            message = buildString {
                append("Event processed with incorrect device token:\n")
                append(
                    mismatches.joinToString("\n") { (time, actual, expected) ->
                        "- At $time NS: saw `$actual`, expected `$expected`"
                    }
                )
            }
        )
    }

    private fun waitUntil(timeMs: Long) {
        val sleepTime = timeMs - System.nanoTime().nanosToMillis()
        assert(sleepTime > 0) { "Cannot wait for past time: $timeMs" }
        Thread.sleep(sleepTime)
    }

    private fun Long.nanosToMillis(): Long {
        return this / 1_000_000
    }
}
