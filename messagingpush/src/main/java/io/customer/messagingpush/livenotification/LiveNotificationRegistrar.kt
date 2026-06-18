package io.customer.messagingpush.livenotification

import io.customer.messagingpush.di.pushModuleConfig
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus

/**
 * Sends `register_push_to_start` track events for the live-notification activity
 * types the host app enabled via `MessagingPushModuleConfig.setLiveNotificationTypes`.
 * No types enabled ⇒ nothing is registered.
 *
 * Registration is (re)attempted whenever the device token rotates
 * ([Event.RegisterDeviceTokenEvent]) or the user changes
 * ([Event.UserChangedEvent]), and is deduped per activity type via
 * [LiveNotificationStore] (signature = `token|userId`). The signature is only
 * stored when the event is actually emitted, so a registration skipped while
 * anonymous re-fires once the user is identified. Token deletion / reset clears
 * the stored signatures so the next token re-registers.
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
            token = null
            store.clearRegistrations()
        }
        eventBus.subscribe(Event.ResetEvent::class) {
            store.clearRegistrations()
        }
    }

    private fun registerAll() {
        val currentToken = token ?: return
        val signature = "$currentToken|$userId"
        for (activityType in enabledTypes) {
            if (store.registrationSignature(activityType) == signature) continue
            val emitted = client.registerPushToStart(activityType, currentToken)
            if (emitted) {
                store.setRegistrationSignature(activityType, signature)
            } else {
                SDKComponent.logger.debug(
                    "Live notification registration skipped for '$activityType'; will retry on next token/user change."
                )
            }
        }
    }
}
