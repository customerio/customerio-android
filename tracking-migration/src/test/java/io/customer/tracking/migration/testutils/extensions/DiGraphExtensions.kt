package io.customer.tracking.migration.testutils.extensions

import io.customer.sdk.core.di.SDKComponent
import io.customer.tracking.migration.MigrationProcessor
import io.customer.tracking.migration.di.MigrationSDKComponent
import io.customer.tracking.migration.testutils.core.TrackingMigrationTestConfig

/**
 * Configures [MigrationSDKComponent] with provided [config].
 *
 * Only intended to be used in tests for setting up [MigrationSDKComponent] and accessing it conveniently.
 */
fun SDKComponent.configureMigrationSDKComponent(
    config: TrackingMigrationTestConfig
) = registerMigrationSDKComponent(
    migrationProcessor = config.migrationProcessor,
    migrationSiteId = config.migrationSiteId
).also(config.migrationSDKComponent)

/**
 * Registers [MigrationSDKComponent] in [SDKComponent] with given parameters and
 * returns the component.
 *
 * Only intended to be used in tests for setting up [MigrationSDKComponent] and accessing it conveniently.
 */
fun SDKComponent.registerMigrationSDKComponent(
    migrationProcessor: MigrationProcessor,
    migrationSiteId: String
) = registerDependency<MigrationSDKComponent> {
    MigrationSDKComponent(
        migrationProcessor = migrationProcessor,
        migrationSiteId = migrationSiteId
    )
}

/**
 * Gets [MigrationSDKComponent] from [SDKComponent].
 *
 * Only intended to be used in tests for setting up [MigrationSDKComponent] and accessing it conveniently.
 */
val SDKComponent.migrationSDKComponent: MigrationSDKComponent
    get() = getOrNull() ?: throw IllegalStateException("MigrationSDKComponent not found in SDKComponent")
