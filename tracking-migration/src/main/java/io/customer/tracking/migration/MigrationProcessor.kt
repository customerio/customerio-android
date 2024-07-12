package io.customer.tracking.migration

import io.customer.tracking.migration.request.MigrationTask

/**
 * Interface for delegating the migration tasks to the appropriate processor.
 * The processor will be responsible for processing the migration tasks in desired order
 * to make sure the migration is successful.
 */
interface MigrationProcessor {
    /**
     * Method responsible for processing the migration task.
     * Processor class should be able to handle the task and process it accordingly
     * and return result of processing so the queue can decide if the task should
     * be deleted or not.
     */
    suspend fun processTask(task: MigrationTask): Result<Unit>
}
