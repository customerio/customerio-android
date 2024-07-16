package io.customer.tracking.migration

import io.customer.sdk.core.di.SDKComponent
import io.customer.tracking.migration.di.MigrationSDKComponent
import io.customer.tracking.migration.queue.Queue
import io.customer.tracking.migration.repository.preference.SitePreferenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Class responsible for migrating the existing tracking data to the new data
 * pipelines implementation.
 */
class MigrationAssistant private constructor(
    private val migrationProcessor: MigrationProcessor,
    migrationSiteId: String
) {
    private val migrationSDKComponent = MigrationSDKComponent(
        migrationProcessor = migrationProcessor,
        migrationSiteId = migrationSiteId
    )
    private val sitePreferences: SitePreferenceRepository = migrationSDKComponent.sitePreferences
    private val queue: Queue = migrationSDKComponent.queue
    private val logger = SDKComponent.logger
    private val dispatchersProvider = SDKComponent.dispatchersProvider

    /**
     * Starts the migration process by migrating the existing tracking data to the new
     * data pipelines implementation using provided migration processor.
     * The code is placed in init block to start the migration as soon as possible and
     * to avoid any manual call to replay migration.
     */
    init {
        logger.debug("Starting migration tracking data...")
        CoroutineScope(dispatchersProvider.background).launch {
            // Re-identify old profile to new implementation
            logger.debug("Migrating existing token and profile...")
            // token goes first since it is used to identify the profile
            migrateExistingDevice()
            migrateExistingProfile()
            // Run the queue to process any pending events
            logger.debug("Requesting migration queue to run...")
            queue.run()
            // Log completion of migration
            logger.debug("Migration completed successfully")
        }
    }

    private fun migrateExistingProfile() {
        // If there is no old identifier, then either the profile was never identified or it was already migrated
        val oldIdentifier = sitePreferences.getIdentifier() ?: return

        logger.debug("Migrating existing profile with identifier: $oldIdentifier")
        // If the migration is successful, remove the old identifier to prevent re-migration
        if (migrationProcessor.processProfileMigration(oldIdentifier).isSuccess) {
            sitePreferences.removeIdentifier(oldIdentifier)
        }
    }

    private fun migrateExistingDevice() {
        // If there is no old device token, then either the device was never added or it was already migrated
        val oldDeviceIdentifier = sitePreferences.getDeviceToken() ?: return

        if (migrationProcessor.processDeviceMigration(oldDeviceIdentifier).isSuccess) {
            // remove the old device token to prevent updating GlobalPreferenceStore again
            sitePreferences.removeDeviceToken()
        }
    }

    companion object {
        /**
         * Starts the migration process by initializing the [MigrationAssistant]
         * with the provided dependencies.
         * The method is added to improve code readability and to provide a single
         * entry point for starting the migration.
         */
        fun start(
            migrationProcessor: MigrationProcessor,
            migrationSiteId: String
        ) = MigrationAssistant(migrationProcessor, migrationSiteId)
    }
}
