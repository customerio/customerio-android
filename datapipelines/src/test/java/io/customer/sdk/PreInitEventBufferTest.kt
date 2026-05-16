package io.customer.sdk

import io.customer.datapipelines.testutils.core.JUnitTest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Unit tests for [PreInitEventBuffer]. Mirrors the iOS coverage —
 * state transitions, drop-most-recent overflow, order preservation,
 * reentrancy, and concurrent enqueueing.
 */
class PreInitEventBufferTest : JUnitTest() {

    private fun makeBuffer(capacity: Int = 100): PreInitEventBuffer =
        // Inject a nil-returning logger provider so tests don't depend on the
        // shared SDKComponent state (which may or may not have a logger
        // registered depending on test-run order).
        PreInitEventBuffer(capacity = capacity, loggerProvider = { null })

    private fun mockInstance(): DataPipelineInstance {
        val instance = mockk<DataPipelineInstance>(relaxed = true)
        every { instance.clearIdentify() } just runs
        return instance
    }

    // Typed helper to disambiguate the `track(name, properties: Map)` overload
    // from the deprecated `track(name, properties: JsonObject)` overload during
    // MockK verification. Without this, MockK's untyped `any()` lets Kotlin
    // resolve to the wrong overload and verification fails.
    private fun DataPipelineInstance.trackString(name: String) =
        track(name = name, properties = emptyMap<String, Any?>())

    private fun DataPipelineInstance.identifyString(userId: String) =
        identify(userId = userId, traits = emptyMap<String, Any?>())

    private fun DataPipelineInstance.screenString(title: String) =
        screen(title = title, properties = emptyMap<String, Any?>())

    @Test
    fun enqueueAccumulatesWhileBuffering() {
        val buffer = makeBuffer()
        buffer.enqueue { }
        buffer.enqueue { }
        buffer.enqueue { }
        buffer.bufferedCount shouldBeEqualTo 3
        buffer.isReady.shouldBeFalse()
    }

    @Test
    fun drainReplaysInOrder() {
        val buffer = makeBuffer()
        val instance = mockInstance()

        buffer.enqueue { it.trackString("one") }
        buffer.enqueue { it.identifyString("alice") }
        buffer.enqueue { it.screenString("Home") }

        buffer.transitionToReady(instance)

        verifyOrder {
            instance.track("one", emptyMap<String, Any?>())
            instance.identify("alice", emptyMap<String, Any?>())
            instance.screen("Home", emptyMap<String, Any?>())
        }
        buffer.isReady.shouldBeTrue()
        buffer.bufferedCount shouldBeEqualTo 0
    }

    @Test
    fun postReadyEnqueueExecutesImmediately() {
        val buffer = makeBuffer()
        val instance = mockInstance()
        buffer.transitionToReady(instance)
        buffer.enqueue { it.trackString("after") }
        verify { instance.track("after", emptyMap<String, Any?>()) }
        buffer.bufferedCount shouldBeEqualTo 0
    }

    @Test
    fun overflowDropsMostRecent() {
        val buffer = makeBuffer(capacity = 3)
        val instance = mockInstance()

        repeat(5) { index ->
            buffer.enqueue { it.trackString("event-$index") }
        }
        buffer.bufferedCount shouldBeEqualTo 3
        buffer.droppedEventCount shouldBeEqualTo 2

        buffer.transitionToReady(instance)

        verifyOrder {
            instance.track("event-0", emptyMap<String, Any?>())
            instance.track("event-1", emptyMap<String, Any?>())
            instance.track("event-2", emptyMap<String, Any?>())
        }
        // Drop counter is reset after drain so subsequent overflow accounting
        // starts clean.
        buffer.droppedEventCount shouldBeEqualTo 0
    }

    @Test
    fun transitionToReadyOnEmptyBufferIsNoop() {
        val buffer = makeBuffer()
        val instance = mockInstance()
        buffer.transitionToReady(instance)
        buffer.isReady.shouldBeTrue()
        verify(exactly = 0) {
            instance.track(any<String>(), any<Map<String, Any?>>())
        }
    }

    @Test
    fun transitionToReadyCalledTwiceIsSafe() {
        val buffer = makeBuffer()
        val first = mockInstance()
        val second = mockInstance()
        buffer.enqueue { it.trackString("first") }
        buffer.transitionToReady(first)
        buffer.transitionToReady(second)
        verify { first.track("first", emptyMap<String, Any?>()) }
        verify(exactly = 0) { second.track(any<String>(), any<Map<String, Any?>>()) }
    }

    @Test
    fun bufferSurvivesAcrossEnqueueCycles() {
        // If initialize() is never called, the buffer should sit at cap and
        // continue dropping new events without crashing or growing.
        val buffer = makeBuffer(capacity = 2)
        buffer.enqueue { }
        buffer.enqueue { }
        buffer.enqueue { }
        buffer.enqueue { }
        buffer.bufferedCount shouldBeEqualTo 2
        buffer.droppedEventCount shouldBeEqualTo 2
    }

    @Test
    fun enqueueDuringDrainIsPickedUp() {
        // Reentrancy: while a buffered block is executing, the block itself
        // enqueues another event. Both events must end up executed in order.
        val buffer = makeBuffer()
        val instance = mockInstance()

        buffer.enqueue { it.trackString("outer") }
        buffer.enqueue {
            buffer.enqueue { inner -> inner.trackString("inner") }
        }
        buffer.transitionToReady(instance)

        verifyOrder {
            instance.track("outer", emptyMap<String, Any?>())
            instance.track("inner", emptyMap<String, Any?>())
        }
    }

    @Test
    fun concurrentEnqueuesArePreservedUpToCap() {
        val buffer = makeBuffer(capacity = 200)
        val instance = mockInstance()

        val threadCount = 4
        val perThread = 25
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { threadIndex ->
            executor.submit {
                repeat(perThread) { eventIndex ->
                    buffer.enqueue { it.trackString("t$threadIndex-$eventIndex") }
                }
                latch.countDown()
            }
        }
        latch.await()
        executor.shutdown()

        buffer.bufferedCount shouldBeEqualTo threadCount * perThread
        buffer.transitionToReady(instance)
        verify(exactly = threadCount * perThread) {
            instance.track(any<String>(), any<Map<String, Any?>>())
        }
    }
}
