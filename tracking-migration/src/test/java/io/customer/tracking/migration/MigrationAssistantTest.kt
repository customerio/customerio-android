package io.customer.tracking.migration

import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.tracking.migration.di.MigrationSDKComponent
import io.customer.tracking.migration.queue.Queue
import io.customer.tracking.migration.repository.preference.SitePreferenceRepository
import io.customer.tracking.migration.testutils.core.JUnitTest
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationAssistantTest : JUnitTest() {
    private val migrationSiteId = TestConstants.Keys.SITE_ID
    private val testCoroutineScope: CoroutineScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var migrationProcessor: MigrationProcessor
    private lateinit var sitePreferenceRepository: SitePreferenceRepository
    private lateinit var migrationSDKComponent: MigrationSDKComponent

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        // Use relaxed unit mocks instead of full relaxed mocks to minimize false positives
        migrationProcessor = mockk(relaxUnitFun = true)
        sitePreferenceRepository = mockk(relaxUnitFun = true)
        every { sitePreferenceRepository.getIdentifier() } returns null
        every { sitePreferenceRepository.getDeviceToken() } returns null

        migrationSDKComponent = MigrationSDKComponent(
            migrationProcessor = migrationProcessor,
            migrationSiteId = migrationSiteId
        )
        migrationSDKComponent.overrideDependency<CoroutineScope>(testCoroutineScope)
        migrationSDKComponent.overrideDependency<SitePreferenceRepository>(sitePreferenceRepository)
        migrationSDKComponent.overrideDependency<Queue>(mockk(relaxed = true))
    }

    private fun initializeAssistant(): MigrationAssistant {
        return MigrationAssistant(
            migrationProcessor = migrationProcessor,
            migrationSiteId = migrationSiteId,
            migrationSDKComponent = migrationSDKComponent
        )
    }

    @Test
    fun initializeAssistant_givenDefaultValues_expectAssistantRunsInOrder() {
        initializeAssistant()

        coVerifySequence {
            sitePreferenceRepository.getDeviceToken()
            sitePreferenceRepository.getIdentifier()
            migrationSDKComponent.queue.run()
            migrationProcessor.onMigrationCompleted()
        }
    }

    @Test
    fun initializeAssistant_givenNoProfileIdentified_expectDoNotMigrateProfile() {
        every { sitePreferenceRepository.getIdentifier() } returns null

        initializeAssistant()

        assertCalledNever {
            migrationProcessor.processProfileMigration(any())
            sitePreferenceRepository.removeIdentifier(any())
        }
    }

    @Test
    fun initializeAssistant_givenProfileIdentifiedAndMigrationFails_expectDoNotClearProfile() {
        val givenIdentifier = String.random
        every { sitePreferenceRepository.getIdentifier() } returns givenIdentifier
        every { migrationProcessor.processProfileMigration(givenIdentifier) } returns Result.failure(mockk())

        initializeAssistant()

        assertCalledOnce { migrationProcessor.processProfileMigration(givenIdentifier) }
        assertCalledNever { sitePreferenceRepository.removeIdentifier(any()) }
    }

    @Test
    fun initializeAssistant_givenProfileIdentified_expectMigrateProfile() {
        val givenIdentifier = String.random
        every { sitePreferenceRepository.getIdentifier() } returns givenIdentifier
        every { migrationProcessor.processProfileMigration(givenIdentifier) } returns Result.success(Unit)

        initializeAssistant()

        assertCalledOnce {
            migrationProcessor.processProfileMigration(givenIdentifier)
            sitePreferenceRepository.removeIdentifier(givenIdentifier)
        }
    }

    @Test
    fun initializeAssistant_givenNoDeviceIdentified_expectDoNotMigrateDevice() {
        every { sitePreferenceRepository.getDeviceToken() } returns null

        initializeAssistant()

        assertCalledNever {
            migrationProcessor.processDeviceMigration(any())
            sitePreferenceRepository.removeDeviceToken()
        }
    }

    @Test
    fun initializeAssistant_givenDeviceIdentifiedAndMigrationFails_expectDoNotClearDevice() {
        val givenToken = String.random
        every { sitePreferenceRepository.getDeviceToken() } returns givenToken
        every { migrationProcessor.processDeviceMigration(givenToken) } returns Result.failure(mockk())

        initializeAssistant()

        assertCalledOnce { migrationProcessor.processDeviceMigration(givenToken) }
        assertCalledNever { sitePreferenceRepository.removeDeviceToken() }
    }

    @Test
    fun initializeAssistant_givenDeviceIdentified_expectMigrateDevice() {
        val givenToken = String.random
        every { sitePreferenceRepository.getDeviceToken() } returns givenToken
        every { migrationProcessor.processDeviceMigration(givenToken) } returns Result.success(Unit)

        initializeAssistant()

        assertCalledOnce {
            migrationProcessor.processDeviceMigration(givenToken)
            sitePreferenceRepository.removeDeviceToken()
        }
    }
}
