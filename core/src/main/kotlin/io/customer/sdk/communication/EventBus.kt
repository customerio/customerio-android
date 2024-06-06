package io.customer.sdk.communication

import io.customer.sdk.core.di.SDKComponent
import kotlin.reflect.KClass
import kotlin.reflect.safeCast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * Event class to be used for communication between different modules.
 */
interface EventBus {
    val flow: SharedFlow<Event>
    fun publish(event: Event)
    fun removeAllSubscriptions()
    fun <T : Event> subscribe(type: KClass<T>, action: suspend (T) -> Unit): Job
}

inline fun <reified T : Event> EventBus.subscribe(noinline action: (T) -> Unit) = subscribe(T::class, action)

/**
 * Implementation of [EventBus] using [SharedFlow] for event handling.
 * param scope: [CoroutineScope] to be used for event handling.
 * param flow: [SharedFlow] to be used for event handling and buffer for replay.
 */
class EventBusImpl(
    private val sharedFlow: MutableSharedFlow<Event> = MutableSharedFlow(replay = 100)
) : EventBus {

    override val flow: SharedFlow<Event> get() = sharedFlow

    val jobs = mutableListOf<Job>()

    val scope: CoroutineScope = SDKComponent.scopeProvider.eventBusScope

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

    override fun removeAllSubscriptions() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    override fun <T : Event> subscribe(type: KClass<T>, action: suspend (T) -> Unit): Job {
        val job = scope.launch {
            flow.mapNotNull { type.safeCast(it) }.collect { event ->
                action(event)
            }
        }
        jobs.add(job)
        return job
    }
}
