package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.sdk.DataPipelinesLogger
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.amshove.kluent.internal.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests [ContextPlugin] behavior. Uses the test SDK's default dispatcher.
 */
class ContextPluginBehaviorTest : JUnitTest() {
    private lateinit var deviceStore: DeviceStore

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
    }

    /**
     * Verifies that [ContextPlugin.execute] reads `deviceToken` freshly on every call and stamps
     * the current value into the event's context — i.e., a per-event read, not a cached snapshot.
     *
     * Note this test does *not* verify the `@Volatile` annotation on `ContextPlugin.deviceToken`.
     * The [CyclicBarrier] used to fence each round already establishes happens-before between the
     * writer and reader threads, so cross-thread visibility is guaranteed by the barrier itself.
     * Asserting `@Volatile` deterministically from a unit test isn't feasible; that property is a
     * defensive declaration whose removal would be caught by code review, not by this test.
     *
     * The original version of this test queued events through the asynchronous analytics pipeline
     * and tried to correlate events to writes by wall-clock timestamps, racing two threads for
     * five seconds. The pipeline batches and reorders, so on slow CI the correlation window broke
     * down. We now exercise the per-call-read contract synchronously: the writer sets
     * `contextPlugin.deviceToken` directly, the reader calls `contextPlugin.execute(event)`
     * directly, and a [CyclicBarrier] fences every round so the read happens-after the write.
     * Deterministic across [ROUND_COUNT] rounds.
     */
    @Test
    fun execute_whenDeviceTokenIsSetFromAnotherThread_thenAddsCorrectTokenToEvent() {
        val rounds = ROUND_COUNT
        // #689 added a required `installationId` to ContextPlugin; the deterministic test
        // only asserts on deviceToken propagation, so a fixed stub id is sufficient.
        val contextPlugin = ContextPlugin(deviceStore, installationId = "test-installation-id")
        // We exercise contextPlugin.execute(event) in isolation — no need to
        // attach to analytics. ContextPlugin.execute does not touch the
        // `analytics` lateinit; attaching it under JUnitTest's StandardTestDispatcher
        // can queue setup work that never runs without an explicit `runCurrent`.

        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        val captured = CopyOnWriteArrayList<Pair<String, String?>>()
        val writerError = AtomicReference<Throwable?>()
        val readerError = AtomicReference<Throwable?>()

        // Helper to fence a round half — if our partner threw and bailed,
        // resetting the barrier breaks both threads out instead of timing out.
        fun safeAwait() {
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                barrier.reset()
                throw t
            }
        }

        try {
            val writer = executor.submit {
                try {
                    for (i in 1..rounds) {
                        safeAwait() // start of round
                        contextPlugin.deviceToken = "$TOKEN_PREFIX$i"
                        safeAwait() // publish complete
                    }
                } catch (t: Throwable) {
                    writerError.set(t); barrier.reset()
                }
            }
            val reader = executor.submit {
                try {
                    for (i in 1..rounds) {
                        safeAwait() // start of round
                        safeAwait() // wait for write to complete
                        val event = TrackEvent(properties = emptyJsonObject, event = "$TEST_EVENT_PREFIX$i").apply {
                            // BaseEvent has `context` and `integrations` as lateinit JsonObject.
                            // The analytics pipeline normally initializes them via `applyBaseEventData`;
                            // when we feed events directly to ContextPlugin we must initialize here.
                            context = emptyJsonObject
                            integrations = emptyJsonObject
                        }
                        val processed = contextPlugin.execute(event) as TrackEvent
                        captured.add(processed.event to processed.context.deviceToken)
                    }
                } catch (t: Throwable) {
                    readerError.set(t); barrier.reset()
                }
            }
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            reader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        writerError.get()?.let { throw AssertionError("writer thread threw", it) }
        readerError.get()?.let { throw AssertionError("reader thread threw", it) }

        assertEquals(rounds, captured.size, "expected one processed event per round")
        for ((eventName, tokenSeen) in captured) {
            val roundIndex = eventName.removePrefix(TEST_EVENT_PREFIX).toInt()
            val expectedToken = "$TOKEN_PREFIX$roundIndex"
            assertEquals(
                expected = expectedToken,
                actual = tokenSeen,
                message = "Event $eventName should carry the token written immediately before it"
            )
        }
    }

    private companion object {
        const val ROUND_COUNT = 50
        const val TOKEN_PREFIX = "test-token-"
        const val TEST_EVENT_PREFIX = "test-event-"
        const val TIMEOUT_SECONDS = 10L
    }
}
