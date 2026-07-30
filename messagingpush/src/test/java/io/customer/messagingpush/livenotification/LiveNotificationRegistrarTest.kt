package io.customer.messagingpush.livenotification

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.communication.Event
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [LiveNotificationRegistrar]'s identity gate.
 *
 * The registrar registers push-to-start tokens only for identified users, deriving identity from
 * [Event.UserChangedEvent] (which carries the userId synchronously) rather than the data pipeline's
 * asynchronously-updated `isUserIdentified` flag — that flag can still read false on the login turn,
 * which used to drop the registration with no retry (the G5 race).
 */
@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationRegistrarTest : IntegrationTest() {

    private val client: LiveNotificationLifecycleClient = mockk(relaxed = true)
    private val store: LiveNotificationStore = mockk(relaxed = true)
    private val type = "io.customer.livenotifications.segments"

    private lateinit var registrar: LiveNotificationRegistrar

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder()
                .enableLiveNotificationTypes(LiveNotificationType.SEGMENTS)
                .build()
        ).attachToSDKComponent()
        every { store.registrationSignature(any()) } returns null
        every { client.registerPushToStart(any(), any()) } returns true
        registrar = LiveNotificationRegistrar(client, store)
    }

    @Test
    fun register_firesForIdentifiedUser_evenIfPipelineFlagWouldLag() {
        registrar.onDeviceTokenChanged("fcm-tok")
        // Identify: the event carries the userId synchronously even though the pipeline's
        // isUserIdentified flag may still read false at this instant. Registration must fire.
        registrar.onUserChanged(Event.UserChangedEvent(userId = "u1", anonymousId = "anon"))

        verify(exactly = 1) { client.registerPushToStart(type, "fcm-tok") }
        verify { store.setRegistrationSignature(type, "fcm-tok|u1") }
    }

    @Test
    fun register_skippedForAnonymousUser() {
        registrar.onDeviceTokenChanged("fcm-tok")
        registrar.onUserChanged(Event.UserChangedEvent(userId = null, anonymousId = "anon"))

        verify(exactly = 0) { client.registerPushToStart(any(), any()) }
    }

    @Test
    fun onReset_skipsRegistrationUntilNextIdentify() {
        // Local lifecycle events gate on pipeline.isUserIdentified (cleared synchronously by
        // clearIdentify), so the registrar no longer feeds identity to the client. It still
        // drops its own identity on reset so a held token isn't re-registered while anonymous.
        registrar.onDeviceTokenChanged("fcm-tok")
        registrar.onUserChanged(Event.UserChangedEvent(userId = "u1", anonymousId = "anon"))

        registrar.onReset()
        registrar.onDeviceTokenChanged("fcm-tok-2")

        // The token arriving after reset (while anonymous) must not register.
        verify(exactly = 0) { client.registerPushToStart(type, "fcm-tok-2") }
    }

    @Test
    fun register_defersUntilIdentified_whenTokenArrivesFirstWhileAnonymous() {
        // Token before any identify → nothing registered yet (still anonymous).
        registrar.onDeviceTokenChanged("fcm-tok")
        verify(exactly = 0) { client.registerPushToStart(any(), any()) }

        // Later identify → the held token registers.
        registrar.onUserChanged(Event.UserChangedEvent(userId = "u1", anonymousId = "anon"))
        verify(exactly = 1) { client.registerPushToStart(type, "fcm-tok") }
    }

    @Test
    fun register_skipped_whenNoTokenYet() {
        registrar.onUserChanged(Event.UserChangedEvent(userId = "u1", anonymousId = "anon"))
        verify(exactly = 0) { client.registerPushToStart(any(), any()) }
    }

    @Test
    fun tokenDeleted_clearsRegistrationSignatures() {
        // Otherwise re-registering the same token later would be deduped away.
        registrar.onDeviceTokenDeleted()

        verify { store.clearRegistrations() }
    }
}
