package io.customer.sdk.communication

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

class EventBus(
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val sharedFlow: MutableSharedFlow<Event> = MutableSharedFlow(replay = 10)
) {

    val flow: SharedFlow<Event> = sharedFlow

    // Publish an event
    fun publish(event: Event) {
        scope.launch {
            sharedFlow.emit(event)
        }
    }

    // Subscription (SharedFlow)
    inline fun <reified T : Event> subscribe(crossinline action: suspend (T) -> Unit): Job {
        return scope.launch {
            flow.filterIsInstance<T>().collect { event ->
                action(event)
            }
        }
    }

    // Cancel a specific job
    fun cancel(job: Job) {
        job.cancel()
    }

    // Cancel all jobs
    fun cancelAll() {
        scope.cancel()
    }
}
