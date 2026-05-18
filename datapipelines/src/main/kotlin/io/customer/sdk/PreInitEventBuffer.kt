package io.customer.sdk

import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger

/**
 * A bounded FIFO buffer that absorbs event-shaped public-API calls invoked
 * before the SDK has been initialized.
 *
 * While in the `Buffering` state, calls are stored as closures. Once the
 * owning SDK is ready, [transitionToReady] synchronously replays the buffered
 * calls in order; subsequent enqueues execute immediately.
 *
 * Closures are plain `() -> Unit` lambdas — they capture whatever target they
 * need (typically the surrounding SDK instance) at the call site, so the
 * buffer itself doesn't have to know about the SDK type.
 *
 * Capacity defaults to 100 events. When the buffer is full, **the most recent
 * enqueue is dropped** (the oldest events are preserved) — favouring
 * install-attribution and the first `identify` call, which tend to be the
 * highest-value early events. This is a deliberate divergence from the iOS
 * rewrite (which drops oldest); see the porting plan.
 *
 * Thread safety: state transitions are protected by a `synchronized(lock)`
 * block on a private monitor. Block execution always happens outside the lock
 * to avoid re-entrancy.
 */
internal class PreInitEventBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val loggerProvider: () -> Logger? = { SDKComponent.logger }
) {
    internal companion object {
        internal const val DEFAULT_CAPACITY = 100
        internal const val LOG_TAG = "PreInitEventBuffer"
    }

    private sealed class State {
        data class Buffering(val blocks: MutableList<Block>) : State()
        data class Draining(val pending: MutableList<Block>) : State()
        object Ready : State()
    }

    private val lock = Any()
    private var state: State = State.Buffering(mutableListOf())
    private var droppedCount: Int = 0

    /**
     * Outcome of an [enqueue] call. Carried out of the lock so logging and
     * inline execution happen without re-entrancy concerns.
     */
    private sealed class EnqueueOutcome {
        data class Enqueued(val bufferedCount: Int) : EnqueueOutcome()
        data class Dropped(val totalDropped: Int) : EnqueueOutcome()
        object ExecuteNow : EnqueueOutcome()
    }

    /**
     * Enqueue a call for future replay, or execute it immediately if the buffer
     * is already in the [State.Ready] state. If the buffer is at capacity, the
     * new call is dropped and the running drop counter is incremented (also
     * surfaced in the next [transitionToReady] summary log).
     */
    fun enqueue(block: Block) {
        val outcome: EnqueueOutcome = synchronized(lock) {
            when (val current = state) {
                is State.Buffering -> {
                    if (current.blocks.size >= capacity) {
                        droppedCount++
                        EnqueueOutcome.Dropped(droppedCount)
                    } else {
                        current.blocks.add(block)
                        EnqueueOutcome.Enqueued(current.blocks.size)
                    }
                }
                is State.Draining -> {
                    if (current.pending.size >= capacity) {
                        droppedCount++
                        EnqueueOutcome.Dropped(droppedCount)
                    } else {
                        current.pending.add(block)
                        EnqueueOutcome.Enqueued(current.pending.size)
                    }
                }
                State.Ready -> EnqueueOutcome.ExecuteNow
            }
        }
        when (outcome) {
            is EnqueueOutcome.Enqueued ->
                loggerProvider()?.debug(
                    "Pre-init event buffer accepted event (buffered count: ${outcome.bufferedCount}).",
                    LOG_TAG
                )
            is EnqueueOutcome.Dropped ->
                loggerProvider()?.debug(
                    "Pre-init event buffer is at capacity ($capacity); dropping event. " +
                        "Total dropped this session: ${outcome.totalDropped}.",
                    LOG_TAG
                )
            EnqueueOutcome.ExecuteNow -> block()
        }
    }

    /**
     * Replay all buffered events in order, then transition to [State.Ready].
     * Concurrent enqueues that arrive during the replay are picked up before
     * the transition completes. Safe to call multiple times; subsequent calls
     * are no-ops once ready.
     */
    fun transitionToReady() {
        var totalDrained = 0
        while (true) {
            val blocksToReplay: List<Block>? = synchronized(lock) {
                when (val current = state) {
                    is State.Buffering -> {
                        if (current.blocks.isEmpty()) {
                            // No buffered events; advance straight to ready so
                            // subsequent enqueues run inline.
                            state = State.Ready
                            null
                        } else {
                            val drained = current.blocks.toList()
                            state = State.Draining(mutableListOf())
                            drained
                        }
                    }
                    is State.Draining -> {
                        if (current.pending.isEmpty()) {
                            state = State.Ready
                            null
                        } else {
                            val drained = current.pending.toList()
                            state = State.Draining(mutableListOf())
                            drained
                        }
                    }
                    State.Ready -> null
                }
            }
            if (blocksToReplay.isNullOrEmpty()) {
                break
            }
            for (block in blocksToReplay) {
                block()
            }
            totalDrained += blocksToReplay.size
        }

        val droppedSnapshot = synchronized(lock) {
            val dropped = droppedCount
            droppedCount = 0
            dropped
        }

        loggerProvider()?.debug(
            "Pre-init event buffer transitioned to ready. Drained $totalDrained event(s) " +
                "(dropped due to capacity this session: $droppedSnapshot).",
            LOG_TAG
        )
    }

    // Test-only inspection ------------------------------------------------

    internal val bufferedCount: Int
        get() = synchronized(lock) {
            when (val current = state) {
                is State.Buffering -> current.blocks.size
                is State.Draining -> current.pending.size
                State.Ready -> 0
            }
        }

    internal val isReady: Boolean
        get() = synchronized(lock) { state is State.Ready }

    internal val droppedEventCount: Int
        get() = synchronized(lock) { droppedCount }
}

internal typealias Block = () -> Unit
