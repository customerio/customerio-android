package io.customer.sdk.queue

// Make sure implementation is thread-safe
interface QueueRequestManager {
    /**
     * Have the queue call this when it completes running. This frees up the SDK to run the request again on the next request.
     */
    fun queueRunRequestComplete()

    /**
     * Have the queue call this when it wants to start a new request. Manager will remember that the queue is running a request.
     *
     * @return if an existing run request is currently running. If true, the queue should not run the queue.
     */
    fun startRequest(): Boolean
}

/**
Simple singleton (separate singleton for each siteId) that helps assert that the background queue is only running
one run request at one time (1 run request per site id since each site id can have it's own background queue).
When the background queue wants to run it's tasks, it's important that the queue only have 1 concurrent runner
running at one time to prevent race conditions and tasks running multiple times.
This class is small and separate from the rest of the queue logic for some readability/scalability value but
mostly memory safety.
We want to avoid making our queue classes singletons because these classes may have lots of
dependencies inside of them (especially the runner). We want to avoid keeping all of these dependencies sitting in
memory.
 */
class QueueRequestManagerImpl : QueueRequestManager {

    @Volatile var isRunningRequest: Boolean = false

    override fun queueRunRequestComplete() {
        synchronized(this) {
            isRunningRequest = false
        }
    }

    override fun startRequest(): Boolean {
        synchronized(this) {
            val isQueueRunningRequest = isRunningRequest

            if (!isQueueRunningRequest) {
                isRunningRequest = true
            }

            // return the isRunningRequest value before modification or we will
            // *always* return true (since we modify to true or ignore)
            return isQueueRunningRequest
        }
    }
}
