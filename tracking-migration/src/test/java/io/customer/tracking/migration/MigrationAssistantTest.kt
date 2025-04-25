package io.customer.tracking.migration

import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.commontest.util.UnitTestLogger
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.tracking.migration.queue.Queue
import io.customer.tracking.migration.repository.preference.SitePreferenceRepository
import io.customer.tracking.migration.testutils.core.JUnitTest
import io.customer.tracking.migration.testutils.core.testConfiguration
import io.customer.tracking.migration.testutils.extensions.migrationSDKComponent
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

    private lateinit var migrationProcessorMock: MigrationProcessor
    private lateinit var sitePreferencesMock: SitePreferenceRepository
    private lateinit var queueMock: Queue

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                diGraph {
                    migrationSDKComponent {
                        overrideDependency<CoroutineScope>(testCoroutineScope)
                        overrideDependency<SitePreferenceRepository>(mockk())
                        // Use relaxed unit mock instead of full relaxed mocks to minimize false positives
                        overrideDependency<Queue>(mockk(relaxUnitFun = true))
                        overrideDependency<Logger>(UnitTestLogger())
                    }
                }
                migrationSiteId(migrationSiteId)
            }
        )

        val migrationSDKComponent = SDKComponent.migrationSDKComponent
        migrationProcessorMock = migrationSDKComponent.migrationProcessor
        queueMock = migrationSDKComponent.queue
        sitePreferencesMock = migrationSDKComponent.sitePreferences

        every { sitePreferencesMock.getIdentifier() } returns null
        every { sitePreferencesMock.getDeviceToken() } returns null
    }

    private fun initializeAssistant(): MigrationAssistant {
        return MigrationAssistant(
            migrationProcessor = migrationProcessorMock,
            migrationSiteId = migrationSiteId,
            migrationSDKComponent = SDKComponent.migrationSDKComponent
        )
    }

    @Test
    fun initializeAssistant_givenDefaultValues_expectAssistantRunsInOrder() {
        initializeAssistant()

        coVerifySequence {
            sitePreferencesMock.getDeviceToken()
            sitePreferencesMock.getIdentifier()
            queueMock.run()
        }
    }

    @Test
    fun initializeAssistant_givenNoProfileIdentified_expectDoNotMigrateProfile() {
        every { sitePreferencesMock.getIdentifier() } returns null

        initializeAssistant()

        assertCalledNever {
            migrationProcessorMock.processProfileMigration(any())
            sitePreferencesMock.removeIdentifier(any())
        }
    }

    @Test
    fun initializeAssistant_givenProfileIdentifiedAndMigrationFails_expectDoNotClearProfile() {
        val givenIdentifier = String.random
        every { sitePreferencesMock.getIdentifier() } returns givenIdentifier
        every { migrationProcessorMock.processProfileMigration(givenIdentifier) } returns Result.failure(mockk())

        initializeAssistant()

        assertCalledOnce { migrationProcessorMock.processProfileMigration(givenIdentifier) }
        assertCalledNever { sitePreferencesMock.removeIdentifier(any()) }
    }

    @Test
    fun initializeAssistant_givenProfileIdentified_expectMigrateProfile() {
        val givenIdentifier = String.random
        every { sitePreferencesMock.getIdentifier() } returns givenIdentifier
        every { migrationProcessorMock.processProfileMigration(givenIdentifier) } returns Result.success(Unit)

        initializeAssistant()

        assertCalledOnce {
            migrationProcessorMock.processProfileMigration(givenIdentifier)
            sitePreferencesMock.removeIdentifier(givenIdentifier)
        }
    }

    @Test
    fun initializeAssistant_givenNoDeviceIdentified_expectDoNotMigrateDevice() {
        every { sitePreferencesMock.getDeviceToken() } returns null

        initializeAssistant()

        assertCalledNever {
            migrationProcessorMock.processDeviceMigration(any())
            sitePreferencesMock.removeDeviceToken()
        }
    }

    @Test
    fun initializeAssistant_givenDeviceIdentifiedAndMigrationFails_expectDoNotClearDevice() {
        val givenToken = String.random
        every { sitePreferencesMock.getDeviceToken() } returns givenToken
        every { migrationProcessorMock.processDeviceMigration(givenToken) } returns Result.failure(mockk())

        initializeAssistant()

        assertCalledOnce { migrationProcessorMock.processDeviceMigration(givenToken) }
        assertCalledNever { sitePreferencesMock.removeDeviceToken() }
    }

    @Test
    fun initializeAssistant_givenDeviceIdentified_expectMigrateDevice() {
        val givenToken = String.random
        every { sitePreferencesMock.getDeviceToken() } returns givenToken
        every { migrationProcessorMock.processDeviceMigration(givenToken) } returns Result.success(Unit)

        initializeAssistant()

        assertCalledOnce {
            migrationProcessorMock.processDeviceMigration(givenToken)
            sitePreferencesMock.removeDeviceToken()
        }
    }
}
