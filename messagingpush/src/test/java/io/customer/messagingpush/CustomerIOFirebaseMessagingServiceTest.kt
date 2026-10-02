package io.customer.messagingpush

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomerIOFirebaseMessagingServiceTest : IntegrationTest() {

    private val mockEventBus = mockk<EventBus>(relaxed = true)
    private val mockGlobalPreferenceStore = mockk<GlobalPreferenceStore>(relaxed = true)
    private val mockPushLogger = mockk<PushNotificationLogger>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<EventBus>(mockEventBus)
                        overrideDependency<PushNotificationLogger>(mockPushLogger)
                    }
                    android { overrideDependency<GlobalPreferenceStore>(mockGlobalPreferenceStore) }
                }
            }
        )
    }

    @Test
    fun onRegistered_givenNewInstallationId_expectDeviceTokenRegistered() {
        every { mockGlobalPreferenceStore.getDeviceToken() } returns "legacy-token"

        CustomerIOFirebaseMessagingService.onRegistered(contextMock, INSTALLATION_ID)

        assertCalledOnce { mockEventBus.publish(Event.RegisterDeviceTokenEvent(INSTALLATION_ID)) }
    }

    @Test
    fun onRegistered_givenNoStoredToken_expectDeviceTokenRegistered() {
        every { mockGlobalPreferenceStore.getDeviceToken() } returns null

        CustomerIOFirebaseMessagingService.onRegistered(contextMock, INSTALLATION_ID)

        assertCalledOnce { mockEventBus.publish(Event.RegisterDeviceTokenEvent(INSTALLATION_ID)) }
    }

    @Test
    fun onRegistered_givenInstallationIdAlreadyStored_expectNoEvent() {
        every { mockGlobalPreferenceStore.getDeviceToken() } returns INSTALLATION_ID

        CustomerIOFirebaseMessagingService.onRegistered(contextMock, INSTALLATION_ID)

        assertCalledNever { mockEventBus.publish(any()) }
        assertCalledOnce { mockPushLogger.logInstallationIdUnchanged(INSTALLATION_ID) }
    }

    @Test
    fun serviceOnRegistered_givenNewInstallationId_expectDeviceTokenRegistered() {
        every { mockGlobalPreferenceStore.getDeviceToken() } returns null

        buildService().onRegistered(INSTALLATION_ID)

        assertCalledOnce { mockEventBus.publish(Event.RegisterDeviceTokenEvent(INSTALLATION_ID)) }
    }

    @Test
    fun serviceOnUnregistered_expectLogOnly() {
        buildService().onUnregistered(INSTALLATION_ID)

        assertCalledOnce { mockPushLogger.logInstallationIdUnregistered(INSTALLATION_ID) }
        assertCalledNever { mockEventBus.publish(any()) }
    }

    private fun buildService(): CustomerIOFirebaseMessagingService =
        Robolectric.buildService(CustomerIOFirebaseMessagingService::class.java).create().get()

    private companion object {
        const val INSTALLATION_ID = "cAbCdEfGhIjKlMnOpQrStU"
    }
}
