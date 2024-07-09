package io.customer.tracking.migration.type

// We only care about if the task ran successfully or not so Unit was chosen.
typealias QueueRunTaskResult = Result<Unit>
