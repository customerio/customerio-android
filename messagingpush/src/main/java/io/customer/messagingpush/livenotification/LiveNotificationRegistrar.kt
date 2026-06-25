package io.customer.messagingpush.livenotification

import io.customer.messagingpush.di.pushModuleConfig
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus

/**
 * Emits `register_push_to_start` track events for the enabled live-notification
 * types so Customer.io can remotely start them.
 *
 * Registration is a CDP track event, so the data pipeline owns batching, retry
 * and delivery — this type does not retry itself. It only decides WHEN to emit:
 * whenever the device token ([Event.RegisterDeviceTokenEvent]) or user
 * ([Event.UserChangedEvent]) changes, for each enabled type whose
 * `token|userId` signature isn't already registered ([LiveNotificationStore]).
 *
 * The signature dedup means re-identifying the same user with the same token —
 * e.g. on every app launch — does NOT re-send the same registrations. A new
 * token or a new user yields a new signature and re-registers. Live
 * notifications are auth-only: events for anonymous users are dropped by
 * [LiveNotificationLifecycleClient] (no signature stored), so a registration
 * skipped while anonymous re-fires once the user is identified.
 */
internal class LiveNotificationRegistrar(
    private val client: LiveNotificationLifecycleClient,
    private val store: LiveNotificationStore
) {

    @Volatile
    private var token: String? = null

    @Volatile
    private var userId: String = ""

    private val enabledTypes: Set<String>
        get() = SDKComponent.pushModuleConfig.liveNotificationTypes

    fun start() {
        // Drop dedup entries for activities that ended long ago without an explicit `end`.
        store.trimStaleTimestamps()

        eventBus.subscribe(Event.RegisterDeviceTokenEvent::class) { event ->
            token = event.token
            registerAll()
        }
        eventBus.subscribe(Event.UserChangedEvent::class) { event ->
            userId = event.userId ?: event.anonymousId
            registerAll()
        }
        eventBus.subscribe(Event.DeleteDeviceTokenEvent::class) {
            // No token ⇒ nothing to register; a new token re-registers via its own signature.
            // We deliberately do NOT clear stored signatures here: doing so made the routine
            // delete+re-register token cycle on identify re-send every registration on each launch.
            token = null
        }
    }

    private fun registerAll() {
        val currentToken = token ?: return
        val signature = "$currentToken|$userId"
        for (activityType in enabledTypes) {
            if (store.registrationSignature(activityType) == signature) continue
            if (client.registerPushToStart(activityType, currentToken)) {
                store.setRegistrationSignature(activityType, signature)
            }
        }
    }
}
