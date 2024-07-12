package io.customer.tracking.migration

import android.content.Context
import io.customer.tracking.migration.di.MigrationSDKComponent
import io.customer.tracking.migration.queue.Queue
import io.customer.tracking.migration.repository.preference.SitePreferenceRepository

/**
 * Class responsible for migrating the existing tracking data to the new data
 * pipelines implementation.
 */
class MigrationAssistant(
    context: Context,
    private val migrationProcessor: MigrationProcessor,
    migrationSiteId: String
) {
    private val migrationSDKComponent = MigrationSDKComponent(
        applicationContext = context,
        migrationProcessor = migrationProcessor,
        migrationSiteId = migrationSiteId
    )
    private val sitePreferences: SitePreferenceRepository = migrationSDKComponent.sitePreferences
    private val queue: Queue = migrationSDKComponent.queue

    /**
     * Starts the migration process by migrating the existing tracking data to the new
     * data pipelines implementation using provided migration processor.
     */
    fun migrate() {
        // Re-identify old profile to new implementation
        migrateExistingProfile()
        // Run the queue to process any pending events
        queue.runAsync()
    }

    private fun migrateExistingProfile() {
        // If there is no old identifier, then either the profile was never identified or it was already migrated
        val oldIdentifier = sitePreferences.getIdentifier() ?: return

        // If the migration is successful, remove the old identifier to prevent re-migration
        if (migrationProcessor.processProfileMigration(oldIdentifier).isSuccess) {
            sitePreferences.removeIdentifier(oldIdentifier)
        }
    }
}
