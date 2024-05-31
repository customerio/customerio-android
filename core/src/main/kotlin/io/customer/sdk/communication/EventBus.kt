package io.customer.sdk.communication

import io.customer.sdk.core.di.SDKComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Event class to be used for communication between different modules.
 */
interface EventBus {
    val flow: SharedFlow<Event>
    fun publish(event: Event)
    fun removeAllSubscriptions()
}

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

    val scope: CoroutineScope = SDKComponent.androidSDKComponent?.scopeProvider?.eventBusScope
        ?: CoroutineScope(Dispatchers.Default + SupervisorJob())

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
}
