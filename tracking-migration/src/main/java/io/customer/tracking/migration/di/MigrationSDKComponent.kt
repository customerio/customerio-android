package io.customer.tracking.migration.di

import android.content.Context
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.DiGraph
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.tracking.migration.MigrationProcessor
import io.customer.tracking.migration.queue.Queue
import io.customer.tracking.migration.queue.QueueImpl
import io.customer.tracking.migration.queue.QueueQueryRunner
import io.customer.tracking.migration.queue.QueueQueryRunnerImpl
import io.customer.tracking.migration.queue.QueueRunRequest
import io.customer.tracking.migration.queue.QueueRunRequestImpl
import io.customer.tracking.migration.queue.QueueRunner
import io.customer.tracking.migration.queue.QueueRunnerImpl
import io.customer.tracking.migration.queue.QueueStorage
import io.customer.tracking.migration.queue.QueueStorageImpl
import io.customer.tracking.migration.repository.preference.SitePreferenceRepository
import io.customer.tracking.migration.repository.preference.SitePreferenceRepositoryImpl
import io.customer.tracking.migration.store.FileStorage
import io.customer.tracking.migration.util.JsonAdapter
import kotlinx.coroutines.CoroutineScope

/**
 * Migration SDK component responsible for providing the necessary dependencies for migration.
 * This component makes easier to access dependencies required by migration module.
 * The graph is not registered in SDKComponent as it is not required outside of migration module.
 */
class MigrationSDKComponent(
    androidSDKComponent: AndroidSDKComponent = SDKComponent.android(),
    internal val migrationProcessor: MigrationProcessor,
    internal val migrationSiteId: String
) : DiGraph() {
    internal val applicationContext: Context = androidSDKComponent.applicationContext
    internal val logger: Logger = SDKComponent.logger

    val migrationQueueScope: CoroutineScope
        get() = singleton<CoroutineScope> {
            CoroutineScope(SDKComponent.dispatchersProvider.background)
        }
    val sitePreferences: SitePreferenceRepository
        get() = singleton<SitePreferenceRepository> {
            SitePreferenceRepositoryImpl(applicationContext, migrationSiteId)
        }
    internal val jsonAdapter: JsonAdapter
        get() = singleton<JsonAdapter> { JsonAdapter() }
    internal val fileStorage: FileStorage
        get() = singleton<FileStorage> {
            FileStorage(
                siteId = migrationSiteId,
                context = applicationContext,
                logger = logger
            )
        }
    internal val queueQueryRunner: QueueQueryRunner
        get() = singleton<QueueQueryRunner> {
            QueueQueryRunnerImpl(logger = logger)
        }
    internal val queueRunner: QueueRunner
        get() = singleton<QueueRunner> {
            QueueRunnerImpl(
                jsonAdapter = jsonAdapter,
                logger = logger,
                migrationProcessor = migrationProcessor
            )
        }
    internal val queueRunRequest: QueueRunRequest
        get() = singleton<QueueRunRequest> {
            QueueRunRequestImpl(
                runner = queueRunner,
                queueStorage = queueStorage,
                logger = logger,
                queryRunner = queueQueryRunner
            )
        }
    internal val queueStorage: QueueStorage
        get() = singleton<QueueStorage> {
            QueueStorageImpl(
                fileStorage = fileStorage,
                jsonAdapter = jsonAdapter,
                logger = logger
            )
        }
    val queue: Queue
        get() = singleton<Queue> {
            QueueImpl(
                logger = logger,
                runRequest = queueRunRequest
            )
        }
}
