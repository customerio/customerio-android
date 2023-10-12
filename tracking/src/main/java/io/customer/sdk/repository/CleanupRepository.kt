package io.customer.sdk.repository

import io.customer.sdk.queue.Queue

/**
 * In charge of cleaning up the SDK. Deleting old data or caches. Keeping the SDK fast and performant.
 */
internal interface CleanupRepository {
    suspend fun cleanup()
}

internal class CleanupRepositoryImpl(
    private val queue: Queue
) : CleanupRepository {

    override suspend fun cleanup() {
        queue.deleteExpiredTasks()
    }
}
