package io.customer.sdk.communication

import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Event class to be used for communication between different modules.
 */
interface EventBus {
    val flow: SharedFlow<Event>
    fun publish(event: Event)
    fun cancelAll()
    fun <T : Event> subscribe(type: KClass<T>, action: suspend (T) -> Unit): Job
}

inline fun <reified T : Event> EventBus.subscribe(noinline action: (T) -> Unit) =
    subscribe(T::class, action)

/**
 * Implementation of [EventBus] using [SharedFlow] for event handling.
 * param scope: [CoroutineScope] to be used for event handling.
 * param flow: [SharedFlow] to be used for event handling and buffer for replay.
 */
class EventBusImpl(
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    override val flow: SharedFlow<Event> = MutableSharedFlow(replay = 100)
) : EventBus {

    private val sharedFlow: MutableSharedFlow<Event>
        get() = flow as MutableSharedFlow<Event>

    val jobs = mutableListOf<Job>()

    override fun publish(event: Event) {
        scope.launch {
            sharedFlow.emit(event)
        }
    }

    inline fun <reified T : Event> subscribe(crossinline action: suspend (T) -> Unit): Job {
        val job = scope.launch {
            flow.filterIsInstance<T>().collect { event ->
                action(event)
            }
        }
        jobs.add(job)
        return job
    }

    override fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    override fun <T : Event> subscribe(type: KClass<T>, action: suspend (T) -> Unit): Job {
        val job = scope.launch {
            flow.filter { type.isInstance(it) }.collect { event ->
                action(event as T)
            }
        }
        jobs.add(job)
        return job
    }
}
